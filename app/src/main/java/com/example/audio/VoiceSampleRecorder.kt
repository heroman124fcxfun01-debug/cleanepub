package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class VoiceRecordingUiState(
    val isRecording: Boolean = false,
    val isPlayingPreview: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudeLevel: Float = 0f,
    val recordedSamples: FloatArray? = null,
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
 * - Normalizes & resamples captured PCM cleanly to Mono 16kHz/24kHz [FloatArray] (`[-1.0f, 1.0f]`).
 * - Plays back recorded samples directly via native [AudioTrack] for instant "Nghe thử" verification.
 */
class VoiceSampleRecorder {

    companion object {
        const val MIN_DURATION_MS = 3_000L
        const val MAX_DURATION_MS = 8_000L
        const val DEFAULT_TARGET_SAMPLE_RATE = 16_000
        const val HIGH_TARGET_SAMPLE_RATE = 24_000
        private val CAPTURE_CANDIDATE_RATES = intArrayOf(16_000, 24_000, 44_100, 48_000)

        /**
         * Linear-interpolation resampler + DC-offset removal + peak normalization to Mono Float32.
         */
        fun normalizeAndResampleToMonoFloat(
            pcm16: ShortArray,
            sourceRate: Int,
            targetRate: Int
        ): FloatArray {
            if (pcm16.isEmpty()) return FloatArray(0)
            // 1. Convert PCM-16 to Float32 [-1f, 1f] & remove DC offset
            var sum = 0.0
            val rawFloat = FloatArray(pcm16.size) { i ->
                val v = pcm16[i] / 32768f
                sum += v
                v
            }
            val dcOffset = (sum / rawFloat.size).toFloat()
            var peak = 0f
            for (i in rawFloat.indices) {
                val centered = rawFloat[i] - dcOffset
                rawFloat[i] = centered
                val a = abs(centered)
                if (a > peak) peak = a
            }
            // Gentle peak normalization to -1 dBFS (0.89) if speech is quiet
            if (peak > 0.005f) {
                val gain = (0.89f / peak).coerceAtMost(6.0f)
                for (i in rawFloat.indices) {
                    rawFloat[i] = (rawFloat[i] * gain).coerceIn(-1f, 1f)
                }
            }

            if (sourceRate == targetRate || sourceRate <= 0 || targetRate <= 0) {
                return rawFloat
            }

            // 2. Clean linear interpolation resample to targetRate (16kHz / 24kHz)
            val ratio = sourceRate.toDouble() / targetRate.toDouble()
            val outLen = ((rawFloat.size.toDouble() / ratio).toInt()).coerceAtLeast(1)
            val out = FloatArray(outLen)
            val lastIdx = rawFloat.lastIndex
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx0 = floor(srcPos).toInt().coerceIn(0, lastIdx)
                val idx1 = min(idx0 + 1, lastIdx)
                val frac = (srcPos - idx0).toFloat()
                out[i] = rawFloat[idx0] * (1f - frac) + rawFloat[idx1] * frac
            }
            return out
        }

        /**
         * Converts normalized Float32 audio (`[-1f, 1f]`) to 16-bit PCM [ShortArray] for [AudioTrack].
         */
        fun floatToPcm16(samples: FloatArray): ShortArray {
            return ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
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

    /**
     * Starts recording up to 8 seconds of Mono speech.
     * Caller MUST have verified `RECORD_AUDIO` runtime permission beforehand.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE) {
        stopPreview()
        stopRequested = false
        recordingJob?.cancel()

        val safeTargetRate = if (targetSampleRate == HIGH_TARGET_SAMPLE_RATE) {
            HIGH_TARGET_SAMPLE_RATE
        } else {
            DEFAULT_TARGET_SAMPLE_RATE
        }

        recordingJob = scope.launch {
            var audioRecord: AudioRecord? = null
            var captureRate = safeTargetRate
            var minBufferBytes = 0

            // Try preferred target rate first, then fallback standard hardware rates
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

            _uiState.value = VoiceRecordingUiState(
                isRecording = false,
                isPlayingPreview = false,
                elapsedMs = actualDurationMs,
                amplitudeLevel = 0f,
                recordedSamples = normalizedFloat,
                sampleRate = safeTargetRate,
                errorMessage = null
            )
        }
    }

    /**
     * Stops recording cleanly. Allowed from 3.0s onward; if called earlier, cancels cleanly.
     */
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
     * Plays back the captured (or saved) Mono Float32 sample directly through native [AudioTrack].
     */
    fun togglePreviewRecordedSample(
        samplesOverride: FloatArray? = null,
        sampleRateOverride: Int? = null
    ) {
        if (_uiState.value.isPlayingPreview) {
            stopPreview()
            return
        }
        val samples = samplesOverride ?: _uiState.value.recordedSamples ?: return
        if (samples.isEmpty()) return
        val rate = sampleRateOverride ?: _uiState.value.sampleRate

        previewJob?.cancel()
        previewJob = scope.launch {
            _uiState.value = _uiState.value.copy(isPlayingPreview = true, errorMessage = null)
            val pcm = floatToPcm16(samples)
            val bufferBytes = pcm.size * 2
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
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
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(bufferBytes, minBuf))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()

            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track?.release() }
                _uiState.value = _uiState.value.copy(isPlayingPreview = false)
                return@launch
            }

            activePreviewTrack = track
            try {
                track.play()
                var offset = 0
                val chunk = 1024
                while (currentCoroutineContext().isActive && offset < pcm.size) {
                    val toWrite = min(chunk, pcm.size - offset)
                    val written = track.write(pcm, offset, toWrite)
                    if (written <= 0) break
                    offset += written
                }
                // Brief drain wait so the final tail plays cleanly
                val tailMs = ((chunk * 1000L) / rate).coerceIn(40L, 160L)
                kotlinx.coroutines.delay(tailMs)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                if (activePreviewTrack === track) {
                    activePreviewTrack = null
                }
                _uiState.value = _uiState.value.copy(isPlayingPreview = false)
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        val track = activePreviewTrack
        activePreviewTrack = null
        if (track != null) {
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
