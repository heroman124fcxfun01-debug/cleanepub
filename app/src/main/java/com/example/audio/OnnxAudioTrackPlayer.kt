package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

/**
 * Lightweight native Android [AudioTrack] player for ONNX-synthesized Float32 sentence waveforms.
 * Supports smooth playback speed (`0.8x–1.5x`) and instant interruption on pause/skip/stop.
 */
class OnnxAudioTrackPlayer {

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var stopped = false

    /**
     * Plays a synthesized Mono Float32 sentence synchronously inside a coroutine on [kotlinx.coroutines.Dispatchers.IO].
     * Returns `true` if playback completed naturally, or `false` if interrupted/stopped.
     */
    suspend fun playBlocking(
        samples: FloatArray,
        sampleRate: Int,
        speed: Float = 1.0f
    ): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        stopped = false
        val pcm16 = VoiceSampleRecorder.floatToPcm16(samples)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

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
                .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return false
        }

        val clampedSpeed = speed.coerceIn(0.8f, 1.5f)
        runCatching {
            track.playbackParams = PlaybackParams().setSpeed(clampedSpeed)
        }

        activeTrack = track
        return try {
            track.play()
            var offset = 0
            val chunkSize = 1024
            while (currentCoroutineContext().isActive && !stopped && offset < pcm16.size) {
                val toWrite = min(chunkSize, pcm16.size - offset)
                val written = track.write(pcm16, offset, toWrite)
                if (written <= 0) break
                offset += written
            }
            if (!stopped && currentCoroutineContext().isActive) {
                val drainMs = ((chunkSize * 1000L) / (sampleRate * clampedSpeed)).toLong().coerceIn(30L, 140L)
                delay(drainMs)
                true
            } else {
                false
            }
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

    fun stopImmediately() {
        stopped = true
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
