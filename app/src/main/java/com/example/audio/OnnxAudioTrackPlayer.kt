package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native Android [AudioTrack] / [MediaPlayer] player for VieNeu-TTS 24kHz waveforms
 * and cached instant 44-byte 16-bit PCM WAV previews.
 *
 * Supports two playback modes:
 * 1. **Continuous Single-Track Streaming** (`openContinuousStream` -> `writeContinuousChunkBlocking`
 *    -> `finishContinuousStreamAndDrain`): keeps ONE `AudioTrack` (`MODE_STREAM`, 24kHz Mono PCM16)
 *    alive across every sentence and paragraph in a chapter so there is ZERO hardware teardown/setup
 *    gap between chunks.
 * 2. **One-shot / Instant Preview** (`playWavFileBlocking` / `playBlocking`): plays cached
 *    `preview_<voice_id>.wav` in <50ms without running GGUF/NeuCodec.
 */
class OnnxAudioTrackPlayer {

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var continuousTrack: AudioTrack? = null

    @Volatile
    private var continuousSampleRate: Int = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE

    @Volatile
    private var continuousSpeed: Float = 1.0f

    @Volatile
    private var continuousBufferBytes: Int = 16_384

    @Volatile
    private var continuousFramesWritten: Long = 0L

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    @Volatile
    private var stopped = false

    /**
     * Opens ONE continuous `MODE_STREAM` 24kHz Mono PCM16 [AudioTrack] for gapless paragraph-to-paragraph
     * playback. Subsequent chunks are written directly via [writeContinuousChunkBlocking] without ever
     * stopping or recreating the hardware track.
     */
    fun openContinuousStream(
        sampleRate: Int = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE,
        speed: Float = 1.0f
    ): Boolean {
        if (sampleRate <= 0) return false
        closeContinuousTrackOnly()
        stopped = false

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val streamBufBytes = maxOf(minBuf * 3, 16_384)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(streamBufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return false
        }

        val clampedSpeed = speed.coerceIn(0.8f, 1.5f)
        if (abs(clampedSpeed - 1.0f) >= 0.01f) {
            runCatching {
                track.playbackParams = PlaybackParams()
                    .setSpeed(clampedSpeed)
                    .setPitch(1.0f)
            }
        }

        return runCatching {
            track.play()
            continuousTrack = track
            continuousSampleRate = sampleRate
            continuousSpeed = clampedSpeed
            continuousBufferBytes = streamBufBytes
            continuousFramesWritten = 0L
            true
        }.getOrElse {
            runCatching { track.release() }
            false
        }
    }

    /**
     * Writes a silence-trimmed, crossfaded 16-bit PCM chunk straight into the already-playing
     * continuous [AudioTrack]. Blocks gently via `WRITE_BLOCKING` as the hardware ring buffer drains.
     */
    suspend fun writeContinuousChunkBlocking(pcm16: ShortArray): Boolean {
        if (pcm16.isEmpty()) return true
        val track = continuousTrack ?: return false
        return try {
            var offset = 0
            val chunkSize = 1024
            while (currentCoroutineContext().isActive && !stopped && offset < pcm16.size) {
                if (continuousTrack !== track) return false
                val toWrite = min(chunkSize, pcm16.size - offset)
                val written = track.write(pcm16, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) return false
                offset += written
                continuousFramesWritten += written.toLong()
            }
            !stopped && currentCoroutineContext().isActive && continuousTrack === track
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Drains remaining hardware-buffered frames at the end of a chapter, then cleanly closes the
     * continuous [AudioTrack].
     */
    suspend fun finishContinuousStreamAndDrain() {
        val track = continuousTrack ?: return
        try {
            if (!stopped && currentCoroutineContext().isActive && continuousFramesWritten > 0L) {
                // Append 60ms trailing digital silence so AudioTrack flushes final speech cleanly
                val padSamples = (continuousSampleRate * 60 / 1000).coerceIn(480, 2400)
                val silencePad = ShortArray(padSamples)
                val writtenPad = runCatching {
                    track.write(silencePad, 0, silencePad.size, AudioTrack.WRITE_BLOCKING)
                }.getOrDefault(0)
                if (writtenPad > 0) continuousFramesWritten += writtenPad.toLong()

                val expectedDrainMs = ((continuousBufferBytes / 2L) * 1000L /
                    (continuousSampleRate * continuousSpeed)).toLong().coerceIn(100L, 900L)
                val deadline = System.currentTimeMillis() + expectedDrainMs + 220L
                val targetHead = continuousFramesWritten
                while (currentCoroutineContext().isActive && !stopped &&
                    (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < targetHead &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(12L)
                }
            }
        } finally {
            closeContinuousTrackOnly()
        }
    }

    private fun closeContinuousTrackOnly() {
        val track = continuousTrack
        continuousTrack = null
        continuousFramesWritten = 0L
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * Plays a single synthesized Mono Float32 waveform synchronously inside a coroutine on [kotlinx.coroutines.Dispatchers.IO].
     * Returns `true` if playback completed naturally, or `false` if interrupted/stopped.
     */
    suspend fun playBlocking(
        samples: FloatArray,
        sampleRate: Int,
        speed: Float = 1.0f,
        pitch: Float = 1.0f
    ): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        stopped = false
        val pcm16 = VoiceSampleRecorder.floatToPcm16(samples)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val streamBufBytes = maxOf(minBuf * 2, 8192)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(streamBufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return false
        }

        val clampedSpeed = speed.coerceIn(0.8f, 1.5f)
        val clampedPitch = pitch.coerceIn(0.82f, 1.22f)
        if (clampedSpeed != 1.0f || clampedPitch != 1.0f) {
            runCatching {
                track.playbackParams = PlaybackParams()
                    .setSpeed(clampedSpeed)
                    .setPitch(clampedPitch)
            }
        }

        activeTrack = track
        return try {
            track.play()
            var offset = 0
            val chunkSize = 1024
            while (currentCoroutineContext().isActive && !stopped && offset < pcm16.size) {
                val toWrite = min(chunkSize, pcm16.size - offset)
                val written = track.write(pcm16, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (!stopped && currentCoroutineContext().isActive) {
                val totalFrames = pcm16.size
                val expectedRemainingMs = ((streamBufBytes / 2L) * 1000L / (sampleRate * clampedSpeed)).toLong()
                    .coerceIn(80L, 650L)
                val deadline = System.currentTimeMillis() + expectedRemainingMs + 180L
                while (currentCoroutineContext().isActive && !stopped &&
                    track.playbackHeadPosition < totalFrames &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(10L)
                }
                !stopped && currentCoroutineContext().isActive
            } else {
                false
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (activeTrack === track) {
                activeTrack = null
            }
        }
    }

    /**
     * Plays a standard 44-byte 16-bit PCM WAV file instantaneously (<50ms) via [MediaPlayer].
     */
    suspend fun playWavFileBlocking(wavFile: File): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44L) return false
        stopped = false
        val player = MediaPlayer()
        activeMediaPlayer = player
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(wavFile.absolutePath)
            player.prepare()
            var finished = false
            player.setOnCompletionListener { finished = true }
            player.setOnErrorListener { _, _, _ ->
                finished = true
                true
            }
            val durationMs = player.duration.coerceIn(500, 60_000).toLong()
            val deadline = System.currentTimeMillis() + durationMs + 1_200L
            player.start()
            while (currentCoroutineContext().isActive && !stopped && !finished && System.currentTimeMillis() < deadline) {
                delay(30L)
            }
            !stopped && currentCoroutineContext().isActive
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        } finally {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
            if (activeMediaPlayer === player) {
                activeMediaPlayer = null
            }
        }
    }

    fun stopImmediately() {
        stopped = true
        val mp = activeMediaPlayer
        activeMediaPlayer = null
        if (mp != null) {
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        closeContinuousTrackOnly()
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    companion object {
        private const val SILENCE_THRESHOLD = 0.01f

        /**
         * Prepares a synthesized Float32 speech chunk on [kotlinx.coroutines.Dispatchers.IO] for
         * zero-gap continuous streaming into a single [AudioTrack]:
         * 1. **Silence Trimming**: strips leading & trailing near-silent frames (`abs(v) < 0.01f`),
         *    keeping a 6ms soft cushion so initial plosives and final consonants stay intact.
         * 2. **15ms Smooth Cosine Crossfade**: overlaps `prevTail` smoothly into the chunk onset
         *    (if any) and shapes 15ms raised-cosine fade-in/out to eliminate clicks & pops.
         * 3. **Natural Breath Pause (120–180ms)**: appends exact breath silence directly into the
         *    PCM16 stream (0ms if `appendBreathPause == false` for the final chunk), returning both
         *    the ready-to-write `ShortArray` and the 15ms tail for the next chunk.
         */
        fun prepareSeamlessPcm16(
            samples: FloatArray,
            sampleRate: Int,
            breathSilenceMs: Int = 145,
            crossfadeMs: Int = 15,
            prevTail: FloatArray? = null,
            appendBreathPause: Boolean = true
        ): Pair<ShortArray, FloatArray> {
            if (samples.isEmpty() || sampleRate <= 0) {
                return ShortArray(0) to FloatArray(0)
            }

            // 1. Silence Trimming: detect first and last sample exceeding 0.01f
            var firstActive = -1
            var lastActive = -1
            for (i in samples.indices) {
                if (abs(samples[i]) >= SILENCE_THRESHOLD) {
                    firstActive = i
                    break
                }
            }
            if (firstActive < 0) {
                // Entire chunk is digital silence -> keep a brief 120ms breath so playback doesn't stall
                val fallbackLen = (sampleRate * 120 / 1000).coerceAtLeast(1)
                return ShortArray(fallbackLen) to FloatArray(0)
            }
            for (i in samples.lastIndex downTo firstActive) {
                if (abs(samples[i]) >= SILENCE_THRESHOLD) {
                    lastActive = i
                    break
                }
            }

            // Preserve a tiny 6ms soft guard around voiced boundaries so consonants never clip
            val guardSamples = (sampleRate * 6 / 1000).coerceIn(48, 240)
            val trimStart = (firstActive - guardSamples).coerceAtLeast(0)
            val trimEnd = (lastActive + guardSamples).coerceAtMost(samples.lastIndex)
            val trimmedLen = (trimEnd - trimStart + 1).coerceAtLeast(1)

            val trimmed = FloatArray(trimmedLen) { idx -> samples[trimStart + idx] }

            // 2. 15ms Smooth Raised-Cosine Crossfade & Edge Conditioning
            val fadeLen = ((sampleRate * crossfadeMs) / 1000)
                .coerceIn(48, (trimmedLen / 3).coerceAtLeast(1))

            for (i in 0 until fadeLen) {
                val t = i.toDouble() / fadeLen.toDouble()
                val fadeIn = (0.5 * (1.0 - cos(PI * t))).toFloat()
                val tailSample = if (prevTail != null && i < prevTail.size) {
                    prevTail[i] * (1.0f - fadeIn)
                } else {
                    0f
                }
                trimmed[i] = (trimmed[i] * fadeIn + tailSample).coerceIn(-1.0f, 1.0f)
            }

            val nextTail = FloatArray(fadeLen)
            for (i in 0 until fadeLen) {
                val idx = trimmedLen - fadeLen + i
                val t = i.toDouble() / fadeLen.toDouble()
                val fadeOut = (0.5 * (1.0 + cos(PI * t))).toFloat()
                val shaped = trimmed[idx] * fadeOut
                trimmed[idx] = shaped
                // Keep a very soft 15ms residual tail for seam continuity
                nextTail[i] = shaped * 0.25f
            }

            // 3. Append exact natural breath pause (120–180ms) straight into PCM16 buffer
            val breathSamples = if (appendBreathPause) {
                val clampedMs = breathSilenceMs.coerceIn(120, 180)
                (sampleRate * clampedMs) / 1000
            } else {
                0
            }

            val pcm16 = ShortArray(trimmedLen + breathSamples)
            for (i in 0 until trimmedLen) {
                val clamped = trimmed[i].coerceIn(-1.0f, 1.0f)
                pcm16[i] = (clamped * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            // Trailing breathSamples remain 0 (clean digital silence)
            return pcm16 to nextTail
        }
    }
}
