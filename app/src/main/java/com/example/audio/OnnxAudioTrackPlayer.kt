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
import kotlin.math.min

/**
 * Native Android [AudioTrack] / [MediaPlayer] player for ONNX-synthesized waveforms (24kHz/48kHz Mono)
 * and standard 44-byte 16-bit PCM WAV previews.
 * Properly drains all buffered frames via [AudioTrack.getPlaybackHeadPosition] so sentences never clip.
 */
class OnnxAudioTrackPlayer {

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    @Volatile
    private var stopped = false

    /**
     * Plays a synthesized Mono Float32 waveform synchronously inside a coroutine on [kotlinx.coroutines.Dispatchers.IO].
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
     * Plays a standard 44-byte 16-bit PCM WAV file cleanly via [MediaPlayer].
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
                delay(35L)
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
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}
