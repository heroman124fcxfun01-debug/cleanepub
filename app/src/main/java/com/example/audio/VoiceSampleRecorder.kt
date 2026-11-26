package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

data class VoiceRecordingUiState(
    val isRecording: Boolean = false,
    val isPlayingPreview: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudeLevel: Float = 0f,
    val recordedSamples: FloatArray? = null,
    val previewWavPath: String? = null,
    val sampleRate: Int = VoiceSampleRecorder.DEFAULT_TARGET_SAMPLE_RATE,
    val errorMessage: String? = null
) {
    val elapsedSeconds: Float
        get() = elapsedMs / 1000f

    val canStopEarly: Boolean
        get() = isRecording && elapsedMs >= VoiceSampleRecorder.MIN_DURATION_MS

    val hasValidSample: Boolean
        get() = !isRecording &&
            recordedSamples != null &&
            recordedSamples.isNotEmpty() &&
            elapsedMs >= VoiceSampleRecorder.MIN_DURATION_MS
}

/**
 * 100% Offline Native Android Audio Sample Recorder & Preview Player for AI Voice Cloning.
 * - Captures Mono PCM audio via [AudioRecord] on [Dispatchers.IO].
 * - Enforces 3.0s minimum & 8.0s automatic stop.
 * - Normalizes & resamples captured PCM cleanly to Mono 16kHz [FloatArray] (`[-1.0f, 1.0f]`)
 *   and saves a standard 44-byte 16-bit PCM WAV file for crystal-clear preview playback.
 * - Plays back WAV previews via [MediaPlayer] (with properly drained [AudioTrack] fallback)
 *   so audio NEVER cuts off into a short glitch.
 */
class VoiceSampleRecorder(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext

    companion object {
        const val MIN_DURATION_MS = 3_000L
        const val MAX_DURATION_MS = 8_000L
        const val DEFAULT_TARGET_SAMPLE_RATE = 16_000
        const val HIGH_TARGET_SAMPLE_RATE = 24_000
        private val CAPTURE_CANDIDATE_RATES = intArrayOf(16_000, 24_000, 44_100, 48_000)

        /**
         * Anti-aliased resampler + DC-offset removal + peak normalization to Mono Float32 `[-1f, 1f]`.
         */
        fun normalizeAndResampleToMonoFloat(
            pcm16: ShortArray,
            sourceRate: Int,
            targetRate: Int
        ): FloatArray {
            if (pcm16.isEmpty()) return FloatArray(0)
            val rawFloat = FloatArray(pcm16.size) { i -> pcm16[i] / 32768f }
            return AudioFileDecoder.normalizeAndResampleMonoFloat(
                monoFloat = rawFloat,
                sourceRate = sourceRate,
                targetRate = targetRate
            )
        }

        /**
         * Converts normalized Float32 audio (`[-1f, 1f]`) to 16-bit PCM [ShortArray] for [AudioTrack]/WAV.
         */
        fun floatToPcm16(samples: FloatArray): ShortArray {
            return ShortArray(samples.size) { i ->
                val clamped = samples[i].coerceIn(-1f, 1f)
                (clamped * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _uiState = MutableStateFlow(VoiceRecordingUiState())
    val uiState: StateFlow<VoiceRecordingUiState> = _uiState.asStateFlow()

    @Volatile
    private var stopRequested = false
    private var recordingJob: Job? = null
    private var previewJob: Job? = null

    @Volatile
    private var activePreviewTrack: AudioTrack? = null

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    /**
     * Starts recording up to 8 seconds of Mono speech.
     * Caller MUST have verified `RECORD_AUDIO` runtime permission beforehand.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE) {
        stopPreview()
        stopRequested = false
        recordingJob?.cancel()

        // Always normalize speaker-encoder input to 16kHz Mono Float32 unless explicitly 24kHz
        val safeTargetRate = if (targetSampleRate == HIGH_TARGET_SAMPLE_RATE) {
            HIGH_TARGET_SAMPLE_RATE
        } else {
            DEFAULT_TARGET_SAMPLE_RATE
        }

        recordingJob = scope.launch {
            var audioRecord: AudioRecord? = null
            var captureRate = safeTargetRate
            var minBufferBytes = 0

            val candidateOrder = buildList {
                add(safeTargetRate)
                CAPTURE_CANDIDATE_RATES.forEach { if (it != safeTargetRate) add(it) }
            }

            for (rate in candidateOrder) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val rec = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        (minBuf * 3).coerceAtLeast(rate / 2)
                    )
                }.getOrNull() ?: runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        (minBuf * 3).coerceAtLeast(rate / 2)
                    )
                }.getOrNull()

                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = rec
                    captureRate = rate
                    minBufferBytes = minBuf
                    break
                } else {
                    runCatching { rec?.release() }
                }
            }

            if (audioRecord == null) {
                _uiState.value = VoiceRecordingUiState(
                    errorMessage = "Không thể khởi động micro trên thiết bị này."
                )
                return@launch
            }

            val maxSamples = ((captureRate * MAX_DURATION_MS) / 1000L).toInt()
            val capturedPcm = ShortArray(maxSamples)
            var totalCaptured = 0
            val chunkSize = (minBufferBytes / 2).coerceIn(512, 2048)
            val readBuffer = ShortArray(chunkSize)

            _uiState.value = VoiceRecordingUiState(
                isRecording = true,
                elapsedMs = 0L,
                amplitudeLevel = 0f,
                recordedSamples = null,
                previewWavPath = null,
                sampleRate = safeTargetRate,
                errorMessage = null
            )

            try {
                audioRecord.startRecording()
                val startTimeMs = System.currentTimeMillis()

                while (currentCoroutineContext().isActive && !stopRequested && totalCaptured < maxSamples) {
                    val remaining = maxSamples - totalCaptured
                    val toRead = min(readBuffer.size, remaining)
                    val read = audioRecord.read(readBuffer, 0, toRead)
                    if (read > 0) {
                        System.arraycopy(readBuffer, 0, capturedPcm, totalCaptured, read)
                        totalCaptured += read

                        var sqSum = 0.0
                        for (i in 0 until read) {
                            val norm = readBuffer[i] / 32768.0
                            sqSum += norm * norm
                        }
                        val rms = sqrt(sqSum / read).toFloat()
                        val displayLevel = (rms * 4.2f).coerceIn(0.04f, 1.0f)
                        val elapsed = (System.currentTimeMillis() - startTimeMs)
                            .coerceAtMost(MAX_DURATION_MS)

                        _uiState.value = _uiState.value.copy(
                            isRecording = true,
                            elapsedMs = elapsed,
                            amplitudeLevel = displayLevel
                        )

                        if (elapsed >= MAX_DURATION_MS) {
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                _uiState.value = VoiceRecordingUiState(
                    errorMessage = "Lỗi khi ghi âm mẫu giọng: ${e.message ?: "không xác định"}"
                )
                return@launch
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
            }

            val actualDurationMs = ((totalCaptured.toLong() * 1000L) / captureRate)
                .coerceAtMost(MAX_DURATION_MS)

            if (totalCaptured <= 0 || actualDurationMs < MIN_DURATION_MS) {
                _uiState.value = VoiceRecordingUiState(
                    isRecording = false,
                    elapsedMs = actualDurationMs,
                    amplitudeLevel = 0f,
                    recordedSamples = null,
                    previewWavPath = null,
                    sampleRate = safeTargetRate,
                    errorMessage = "Mẫu giọng cần tối thiểu 3 giây để trích xuất đặc trưng rõ nét."
                )
                return@launch
            }

            val trimmed = capturedPcm.copyOf(totalCaptured)
            val normalizedFloat = normalizeAndResampleToMonoFloat(
                pcm16 = trimmed,
                sourceRate = captureRate,
                targetRate = safeTargetRate
            )

            // Save a standard 44-byte 16-bit PCM WAV file for crystal-clear MediaPlayer preview
            val wavPath = appContext?.let { ctx ->
                runCatching {
                    val dir = File(ctx.cacheDir, "voice_previews").apply { mkdirs() }
                    val wavFile = File(dir, "rec_${System.currentTimeMillis()}.wav")
                    AudioFileDecoder.writePcm16MonoWav(wavFile, normalizedFloat, safeTargetRate)
                    wavFile.absolutePath
                }.getOrNull()
            }

            _uiState.value = VoiceRecordingUiState(
                isRecording = false,
                isPlayingPreview = false,
                elapsedMs = actualDurationMs,
                amplitudeLevel = 0f,
                recordedSamples = normalizedFloat,
                previewWavPath = wavPath,
                sampleRate = safeTargetRate,
                errorMessage = null
            )
        }
    }

    fun stopRecording() {
        stopRequested = true
    }

    fun clearSample() {
        stopPreview()
        stopRequested = true
        recordingJob?.cancel()
        _uiState.value = VoiceRecordingUiState()
    }

    /**
     * Plays back a standard 44-byte WAV preview via [MediaPlayer] (preferred for guaranteed smooth playback),
     * or falls back to a properly drained streaming [AudioTrack] for in-memory [FloatArray] audio.
     */
    fun togglePreviewRecordedSample(
        samplesOverride: FloatArray? = null,
        sampleRateOverride: Int? = null,
        wavPathOverride: String? = null
    ) {
        if (_uiState.value.isPlayingPreview) {
            stopPreview()
            return
        }

        val resolvedWavPath = wavPathOverride?.takeIf { File(it).exists() && File(it).length() > 44L }
            ?: _uiState.value.previewWavPath?.takeIf { samplesOverride == null && File(it).exists() && File(it).length() > 44L }

        val samples = samplesOverride ?: _uiState.value.recordedSamples
        val rate = (sampleRateOverride ?: _uiState.value.sampleRate).coerceAtLeast(8_000)

        if (resolvedWavPath == null && (samples == null || samples.isEmpty())) return

        previewJob?.cancel()
        previewJob = scope.launch {
            _uiState.value = _uiState.value.copy(isPlayingPreview = true, errorMessage = null)
            try {
                // Preferred path: write or reuse a 44-byte PCM-16 WAV and play cleanly with MediaPlayer
                val wavFileToPlay: File? = when {
                    resolvedWavPath != null -> File(resolvedWavPath)
                    samples != null && appContext != null -> runCatching {
                        val dir = File(appContext.cacheDir, "voice_previews").apply { mkdirs() }
                        val tmpWav = File(dir, "preview_tmp.wav")
                        AudioFileDecoder.writePcm16MonoWav(tmpWav, samples, rate)
                        tmpWav
                    }.getOrNull()
                    else -> null
                }

                if (wavFileToPlay != null && wavFileToPlay.exists() && wavFileToPlay.length() > 44L) {
                    val playedWithMediaPlayer = playWavFileWithMediaPlayer(wavFileToPlay)
                    if (playedWithMediaPlayer) return@launch
                }

                // Fallback path: properly drained streaming AudioTrack (small ring buffer + playbackHeadPosition wait)
                if (samples != null && samples.isNotEmpty()) {
                    playFloatWithStreamingAudioTrack(samples, rate)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Lỗi phát nghe thử: ${e.message ?: "không xác định"}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isPlayingPreview = false)
            }
        }
    }

    private suspend fun playWavFileWithMediaPlayer(wavFile: File): Boolean {
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
            val durationMs = player.duration.coerceIn(500, 30_000).toLong()
            val deadline = System.currentTimeMillis() + durationMs + 1_200L
            player.start()
            while (currentCoroutineContext().isActive && !finished && System.currentTimeMillis() < deadline) {
                delay(40L)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.reset() }
            runCatching { player.release() }
            if (activeMediaPlayer === player) {
                activeMediaPlayer = null
            }
        }
    }

    private suspend fun playFloatWithStreamingAudioTrack(samples: FloatArray, rate: Int) {
        val pcm = floatToPcm16(samples)
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        // IMPORTANT: Use a small streaming buffer (minBuf * 2), NEVER the entire clip size,
        // AND wait for playbackHeadPosition to drain all written frames before stopping!
        val streamBufBytes = maxOf(minBuf * 2, 4096)
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
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(streamBufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            return
        }

        activePreviewTrack = track
        try {
            track.play()
            var offset = 0
            val chunk = 1024
            while (currentCoroutineContext().isActive && offset < pcm.size) {
                val toWrite = min(chunk, pcm.size - offset)
                val written = track.write(pcm, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            // Wait until the hardware audio sink has actually played all frames
            val totalFrames = pcm.size
            val maxDrainMs = ((totalFrames * 1000L) / rate) + 600L
            val drainStart = System.currentTimeMillis()
            while (currentCoroutineContext().isActive &&
                track.playbackHeadPosition < totalFrames &&
                System.currentTimeMillis() - drainStart < maxDrainMs
            ) {
                delay(25L)
            }
            delay(40L)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (activePreviewTrack === track) {
                activePreviewTrack = null
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        val mp = activeMediaPlayer
        activeMediaPlayer = null
        if (mp != null) {
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        val track = activePreviewTrack
        activePreviewTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        if (_uiState.value.isPlayingPreview) {
            _uiState.value = _uiState.value.copy(isPlayingPreview = false)
        }
    }

    fun release() {
        stopPreview()
        stopRequested = true
        recordingJob?.cancel()
        scope.cancel()
    }
}
