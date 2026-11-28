package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class ImportedVoiceSample(
    val displayName: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Float,
    val previewWavPath: String
)

/**
 * 100% Offline Standard Android Audio File Decoder (`MediaExtractor` + `MediaCodec`).
 *
 * - Decodes ALL imported audio formats (`.wav`, `.mp3`, `.m4a`, `.aac`, `.ogg`, `.flac`) exclusively
 *   via Android's standard [MediaExtractor] + [MediaCodec] (ZERO manual WAV header parsing).
 * - Reads real `sampleRate`, `channelCount`, and `pcmEncoding` from [MediaFormat] (updating live on
 *   [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED]).
 * - Explicitly downmixes Stereo (`channelCount == 2`) via `(left + right) / 2.0f` and multi-channel
 *   via average across channels so duration & pitch are NEVER distorted.
 * - Performs exact linear resampling from original `sampleRate` (e.g. 44100/48000 Hz) to exact
 *   `16000 Hz` Mono Float32 `[-1.0f, 1.0f]` ready for `neucodec_encoder.onnx`.
 */
object AudioFileDecoder {

    const val SPEAKER_ENCODER_SAMPLE_RATE = 16_000
    private const val MAX_DECODE_SECONDS = 25.0
    private const val MIN_DECODE_SECONDS = 2.0
    const val CLONE_MIN_WINDOW_SECONDS = 3.0f
    const val CLONE_OPTIMAL_WINDOW_SECONDS = 6.0f
    const val CLONE_MAX_WINDOW_SECONDS = 7.0f
    const val CLONE_MAX_SAMPLES_16K = 112_000
    const val ABSOLUTE_SILENCE_THRESHOLD = 0.005f
    const val MAX_EDGE_SILENCE_TRIM_SECONDS = 0.15f
    const val MIN_VALID_AUDIO_RMS = 0.001f

    const val INVALID_AUDIO_USER_MESSAGE =
        "Không giải mã được âm thanh từ file này, vui lòng chọn file rõ tiếng dài 5-8 giây"

    private data class DecodedRawMono(
        val monoFloat: FloatArray,
        val sampleRate: Int
    )

    /**
     * Computes Root-Mean-Square (RMS) amplitude of a Float32 PCM array to detect silent/corrupt audio.
     */
    fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sqSum = 0.0
        var finiteCount = 0
        for (v in samples) {
            if (v.isFinite()) {
                val d = v.toDouble()
                sqSum += d * d
                finiteCount++
            }
        }
        if (finiteCount == 0) return 0f
        return sqrt(sqSum / finiteCount).toFloat()
    }

    suspend fun decodeAudioUriToMonoFloat(
        context: Context,
        uri: Uri,
        targetSampleRate: Int = SPEAKER_ENCODER_SAMPLE_RATE
    ): Result<ImportedVoiceSample> = withContext(Dispatchers.IO) {
        runCatching {
            val safeTargetRate = targetSampleRate.takeIf { it in 8_000..48_000 }
                ?: SPEAKER_ENCODER_SAMPLE_RATE

            val displayName = queryDisplayName(context, uri)
                ?.substringBeforeLast('.')
                ?.replace('_', ' ')
                ?.trim()
                ?.take(36)
                ?.ifBlank { "Giọng đã nhập" }
                ?: "Giọng đã nhập"

            // Always decode via standard Android MediaExtractor + MediaCodec (never manual WAV headers)
            val rawDecoded: DecodedRawMono = decodeWithMediaExtractorAndCodec(context, uri)

            if (rawDecoded.monoFloat.isEmpty()) {
                error(INVALID_AUDIO_USER_MESSAGE)
            }

            // Reject silent/corrupt audio BEFORE peak normalization amplifies noise
            val rawRms = computeRms(rawDecoded.monoFloat)
            if (rawRms < MIN_VALID_AUDIO_RMS) {
                error(INVALID_AUDIO_USER_MESSAGE)
            }

            val rawDurationSec = rawDecoded.monoFloat.size.toFloat() /
                rawDecoded.sampleRate.coerceAtLeast(8_000).toFloat()
            if (rawDurationSec < MIN_DECODE_SECONDS) {
                error("Tệp âm thanh quá ngắn (${String.format("%.1f", rawDurationSec)}s — vui lòng chọn file rõ tiếng dài 5-8 giây).")
            }

            // Cap to MAX_DECODE_SECONDS, remove DC offset, normalize [-1f, 1f], and linear-resample to exact 16kHz Mono
            val maxSourceSamples = (rawDecoded.sampleRate * MAX_DECODE_SECONDS).toInt()
                .coerceAtLeast(rawDecoded.sampleRate)
            val cappedMono = if (rawDecoded.monoFloat.size > maxSourceSamples) {
                rawDecoded.monoFloat.copyOf(maxSourceSamples)
            } else {
                rawDecoded.monoFloat
            }

            val normalized16k = normalizeAndResampleMonoFloat(
                monoFloat = cappedMono,
                sourceRate = rawDecoded.sampleRate,
                targetRate = safeTargetRate
            )

            // Trim ONLY absolute silence (< 0.005f, max 0.15s) & cap strictly at 7.0s (112,000 samples @ 16kHz)
            val cleanest16k = extractCleanestSpeechWindow16k(
                samples = normalized16k,
                sampleRate = safeTargetRate,
                minSeconds = CLONE_MIN_WINDOW_SECONDS,
                maxSeconds = CLONE_MAX_WINDOW_SECONDS,
                trimEdgeMicNoiseSeconds = MAX_EDGE_SILENCE_TRIM_SECONDS
            )

            if (cleanest16k.isEmpty() || computeRms(cleanest16k) < MIN_VALID_AUDIO_RMS) {
                error(INVALID_AUDIO_USER_MESSAGE)
            }

            val finalDuration = cleanest16k.size.toFloat() / safeTargetRate.toFloat()

            // Write a standard 16-bit PCM Mono WAV file with full 44-byte RIFF/WAVE header for clean preview playback
            val previewDir = File(context.applicationContext.cacheDir, "voice_previews").apply { mkdirs() }
            cleanupOldTempPreviews(previewDir, keepLatest = 4)
            val previewWavFile = File(previewDir, "import_${System.currentTimeMillis()}.wav")
            writePcm16MonoWav(
                outputFile = previewWavFile,
                samples = cleanest16k,
                sampleRate = safeTargetRate
            )

            ImportedVoiceSample(
                displayName = displayName,
                samples = cleanest16k,
                sampleRate = safeTargetRate,
                durationSeconds = finalDuration,
                previewWavPath = previewWavFile.absolutePath
            )
        }
    }

    /**
     * Writes a strictly compliant 16-bit PCM Mono WAV file with a complete 44-byte RIFF/WAVE header.
     */
    fun writePcm16MonoWav(
        outputFile: File,
        samples: FloatArray,
        sampleRate: Int
    ): File {
        outputFile.parentFile?.mkdirs()
        val pcm16 = VoiceSampleRecorder.floatToPcm16(samples)
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = pcm16.size * 2
        val riffChunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF chunk descriptor (12 bytes)
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(riffChunkSize)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())

            // fmt sub-chunk (24 bytes)
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // Subchunk1Size = 16 for PCM
            putShort(1.toShort()) // AudioFormat = 1 (Linear PCM)
            putShort(numChannels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())

            // data sub-chunk header (8 bytes)
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(dataSize)
        }

        val pcmBytes = ByteArray(dataSize)
        for (i in pcm16.indices) {
            val s = pcm16[i].toInt()
            pcmBytes[i * 2] = (s and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        BufferedOutputStream(FileOutputStream(outputFile)).use { bos ->
            bos.write(header.array())
            bos.write(pcmBytes)
            bos.flush()
        }
        return outputFile
    }

    /**
     * Decodes ANY Android-supported audio file (`.wav`, `.mp3`, `.m4a`, `.aac`, `.ogg`, `.flac`)
     * via standard [MediaExtractor] + [MediaCodec]:
     * - Reads real `sampleRate` and `channelCount` from [MediaFormat] and refreshes them whenever
     *   [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] or `codec.outputFormat` reports updated values.
     * - Defaults output encoding to `ENCODING_PCM_16BIT` (standard MediaCodec PCM output) unless
     *   `codec.outputFormat` explicitly declares a different `KEY_PCM_ENCODING`.
     * - Downmixes Stereo (`channelCount == 2`) strictly via `(left + right) / 2.0f` and multi-channel
     *   via average across channels — NEVER interleaving L/R as consecutive mono samples.
     */
    private fun decodeWithMediaExtractorAndCodec(
        context: Context,
        uri: Uri
    ): DecodedRawMono {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) {
                error(INVALID_AUDIO_USER_MESSAGE)
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: error(INVALID_AUDIO_USER_MESSAGE)

            var sourceSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
            } else {
                44_100
            }
            var channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }
            // MediaCodec always outputs PCM 16-bit unless outputFormat explicitly sets KEY_PCM_ENCODING
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            var monoBuffer = FloatArray((sourceSampleRate * 6).coerceAtLeast(48_000))
            var totalMonoSamples = 0
            fun maxAllowedSamples(): Int = (sourceSampleRate * (MAX_DECODE_SECONDS + 0.5)).toInt()
                .coerceAtLeast(16_000)

            fun appendMonoSample(v: Float) {
                if (totalMonoSamples >= monoBuffer.size) {
                    val newCap = (monoBuffer.size * 2).coerceAtMost(maxAllowedSamples() + 16_384)
                    if (newCap > monoBuffer.size) {
                        monoBuffer = monoBuffer.copyOf(newCap)
                    } else {
                        return
                    }
                }
                monoBuffer[totalMonoSamples++] = v
            }

            fun decodePcmBytesToMono(
                chunk: ByteArray,
                size: Int,
                chCount: Int,
                encoding: Int
            ) {
                if (size <= 0) return
                val ch = chCount.coerceAtLeast(1)
                val capLimit = maxAllowedSamples()
                val bb = ByteBuffer.wrap(chunk, 0, size).order(ByteOrder.LITTLE_ENDIAN)

                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        val frames = (size / 4) / ch
                        for (f in 0 until frames) {
                            if (totalMonoSamples >= capLimit) break
                            val mono = if (ch == 1) {
                                bb.float
                            } else if (ch == 2) {
                                (bb.float + bb.float) / 2.0f
                            } else {
                                var sum = 0f
                                for (c in 0 until ch) sum += bb.float
                                sum / ch.toFloat()
                            }
                            appendMonoSample(mono.coerceIn(-1f, 1f))
                        }
                    }

                    AudioFormat.ENCODING_PCM_8BIT -> {
                        val frames = size / ch
                        for (f in 0 until frames) {
                            if (totalMonoSamples >= capLimit) break
                            val mono = if (ch == 1) {
                                ((bb.get().toInt() and 0xFF) - 128) / 128f
                            } else if (ch == 2) {
                                val left = ((bb.get().toInt() and 0xFF) - 128) / 128f
                                val right = ((bb.get().toInt() and 0xFF) - 128) / 128f
                                (left + right) / 2.0f
                            } else {
                                var sum = 0f
                                for (c in 0 until ch) {
                                    sum += ((bb.get().toInt() and 0xFF) - 128) / 128f
                                }
                                sum / ch.toFloat()
                            }
                            appendMonoSample(mono.coerceIn(-1f, 1f))
                        }
                    }

                    else -> {
                        val is24Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                        val is32Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            encoding == AudioFormat.ENCODING_PCM_32BIT
                        when {
                            is24Bit -> {
                                val frames = size / (3 * ch)
                                var idx = 0
                                for (f in 0 until frames) {
                                    if (totalMonoSamples >= capLimit) break
                                    var sum = 0f
                                    for (c in 0 until ch) {
                                        val b0 = chunk[idx].toInt() and 0xFF
                                        val b1 = chunk[idx + 1].toInt() and 0xFF
                                        val b2 = chunk[idx + 2].toInt()
                                        idx += 3
                                        val sample24 = b0 or (b1 shl 8) or (b2 shl 16)
                                        sum += sample24 / 8_388_608f
                                    }
                                    appendMonoSample((sum / ch.toFloat()).coerceIn(-1f, 1f))
                                }
                            }

                            is32Bit -> {
                                val frames = (size / 4) / ch
                                for (f in 0 until frames) {
                                    if (totalMonoSamples >= capLimit) break
                                    var sum = 0.0
                                    for (c in 0 until ch) {
                                        sum += bb.int.toDouble() / 2_147_483_648.0
                                    }
                                    appendMonoSample((sum / ch.toDouble()).toFloat().coerceIn(-1f, 1f))
                                }
                            }

                            else -> {
                                // Standard MediaCodec ENCODING_PCM_16BIT (signed 16-bit LE)
                                val frames = (size / 2) / ch
                                for (f in 0 until frames) {
                                    if (totalMonoSamples >= capLimit) break
                                    val mono = if (ch == 1) {
                                        bb.short / 32768f
                                    } else if (ch == 2) {
                                        val left = bb.short / 32768f
                                        val right = bb.short / 32768f
                                        (left + right) / 2.0f
                                    } else {
                                        var sum = 0f
                                        for (c in 0 until ch) sum += bb.short / 32768f
                                        sum / ch.toFloat()
                                    }
                                    appendMonoSample(mono.coerceIn(-1f, 1f))
                                }
                            }
                        }
                    }
                }
            }

            // Create and configure MediaCodec decoder for the detected MIME type
            val createdCodec = runCatching {
                MediaCodec.createDecoderByType(mime).also { mc ->
                    mc.configure(trackFormat, null, null, 0)
                    mc.start()
                }
            }.getOrNull()

            if (createdCodec == null) {
                // Edge-device fallback ONLY if audio/raw decoder is absent: read PCM directly from MediaExtractor
                if (mime.equals("audio/raw", ignoreCase = true)) {
                    val rawEnc = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    val bufSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(16_384, 262_144)
                    } else {
                        65_536
                    }
                    val directBuf = ByteBuffer.allocate(bufSize)
                    val byteChunk = ByteArray(bufSize)
                    while (totalMonoSamples < maxAllowedSamples()) {
                        directBuf.clear()
                        val readBytes = extractor.readSampleData(directBuf, 0)
                        if (readBytes <= 0) break
                        directBuf.position(0)
                        directBuf.limit(readBytes)
                        directBuf.get(byteChunk, 0, readBytes)
                        decodePcmBytesToMono(byteChunk, readBytes, channelCount, rawEnc)
                        if (!extractor.advance()) break
                    }
                    return DecodedRawMono(
                        monoFloat = monoBuffer.copyOf(totalMonoSamples),
                        sampleRate = sourceSampleRate
                    )
                }
                error(INVALID_AUDIO_USER_MESSAGE)
            }

            codec = createdCodec

            fun syncOutputFormat(outFmt: MediaFormat) {
                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sourceSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
                }
                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }
                pcmEncoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var outputFormatSynced = false
            var idlePolls = 0

            while (!sawOutputEos && totalMonoSamples < maxAllowedSamples() && idlePolls < 600) {
                var madeProgress = false
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        madeProgress = true
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) {
                            inBuf.clear()
                            extractor.readSampleData(inBuf, 0)
                        } else {
                            -1
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx >= 0 -> {
                        madeProgress = true
                        if (!outputFormatSynced) {
                            runCatching { syncOutputFormat(codec.outputFormat) }
                            outputFormatSynced = true
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.get(chunk)
                            decodePcmBytesToMono(chunk, chunk.size, channelCount, pcmEncoding)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }

                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        madeProgress = true
                        syncOutputFormat(codec.outputFormat)
                        outputFormatSynced = true
                    }

                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!madeProgress) idlePolls++
                    }
                }
                if (madeProgress) {
                    idlePolls = 0
                }
            }

            return DecodedRawMono(
                monoFloat = monoBuffer.copyOf(totalMonoSamples),
                sampleRate = sourceSampleRate
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Removes DC offset, normalizes cleanly to `[-0.92f, 0.92f]`, and performs exact linear resampling
     * from `sourceRate` (e.g. 44100 Hz or 48000 Hz) to `targetRate` (`16000 Hz`), preserving 100% of
     * the speaker's original pitch and speaking speed.
     */
    fun normalizeAndResampleMonoFloat(
        monoFloat: FloatArray,
        sourceRate: Int,
        targetRate: Int
    ): FloatArray {
        if (monoFloat.isEmpty()) return FloatArray(0)
        // 1. Remove DC offset & find peak
        var sum = 0.0
        for (v in monoFloat) sum += if (v.isFinite()) v.toDouble() else 0.0
        val dc = (sum / monoFloat.size).toFloat()
        val centered = FloatArray(monoFloat.size)
        var peak = 0f
        for (i in monoFloat.indices) {
            val v = (if (monoFloat[i].isFinite()) monoFloat[i] else 0f) - dc
            centered[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        // Normalize quietly recorded voices gently, or clamp hot signals cleanly to 0.92
        if (peak > 0.005f) {
            val gain = (0.92f / peak).coerceAtMost(5.0f)
            for (i in centered.indices) {
                centered[i] = (centered[i] * gain).coerceIn(-1f, 1f)
            }
        }

        if (sourceRate <= 0 || targetRate <= 0 || sourceRate == targetRate) {
            return centered
        }

        // Exact linear interpolation resampling: out[i] = sample at i * (sourceRate / targetRate)
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLen = (centered.size.toDouble() / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val lastIdx = centered.lastIndex

        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = floor(srcPos).toInt().coerceIn(0, lastIdx)
            val idx1 = min(idx0 + 1, lastIdx)
            val frac = (srcPos - idx0).toFloat()
            out[i] = (centered[idx0] * (1f - frac) + centered[idx1] * frac).coerceIn(-1f, 1f)
        }
        return out
    }

    /**
     * Prepares reference voice audio at exact `16,000 Hz` Mono Float32 (`[-1.0f, 1.0f]`) for
     * `neucodec_encoder.onnx` zero-shot cloning:
     * 1. Trims **only absolute silence (`abs(sample) < 0.005f`)** up to a **maximum of `0.15s`**
     *    (2,400 samples @ 16kHz) at the start and end so initial Vietnamese syllables are NEVER clipped.
     * 2. Caps duration strictly at **7.0 seconds (`112,000 samples` @ 16kHz)** so the extracted
     *    NeuCodec FSQ `ref_codes` (~350 tokens) fit cleanly inside `n_ctx = 1536` aligned with `ref_text`.
     */
    fun extractCleanestSpeechWindow16k(
        samples: FloatArray,
        sampleRate: Int = SPEAKER_ENCODER_SAMPLE_RATE,
        minSeconds: Float = CLONE_MIN_WINDOW_SECONDS,
        maxSeconds: Float = CLONE_MAX_WINDOW_SECONDS,
        trimEdgeMicNoiseSeconds: Float = MAX_EDGE_SILENCE_TRIM_SECONDS
    ): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples

        // 1. Trim ONLY absolute silence (|sample| < 0.005f) capped strictly at <= 0.15s at start & end
        val maxEdgeTrim = (sampleRate * trimEdgeMicNoiseSeconds.coerceIn(0f, MAX_EDGE_SILENCE_TRIM_SECONDS)).toInt()
        var trimStart = 0
        val startLimit = min(maxEdgeTrim, samples.size / 4)
        while (trimStart < startLimit && abs(samples[trimStart]) < ABSOLUTE_SILENCE_THRESHOLD) {
            trimStart++
        }
        var trimEnd = samples.size
        val minEnd = (samples.size - min(maxEdgeTrim, samples.size / 4)).coerceAtLeast(trimStart + 1)
        while (trimEnd > minEnd && abs(samples[trimEnd - 1]) < ABSOLUTE_SILENCE_THRESHOLD) {
            trimEnd--
        }

        val trimmed = if (trimEnd - trimStart >= sampleRate / 2) {
            samples.copyOfRange(trimStart, trimEnd)
        } else {
            samples
        }

        // 2. Cap strictly at 7.0 seconds (112,000 samples @ 16kHz) preserving the beginning aligned with ref_text
        val maxSamples = min(
            (sampleRate * maxSeconds.coerceAtMost(CLONE_MAX_WINDOW_SECONDS)).toInt().coerceAtLeast(sampleRate),
            if (sampleRate == SPEAKER_ENCODER_SAMPLE_RATE) CLONE_MAX_SAMPLES_16K else (sampleRate * 7)
        )
        if (trimmed.size <= maxSamples) {
            return applyGentleEdgeFadeAndNormalize(trimmed, sampleRate)
        }
        // Keep the opening 7.0s (0..maxSamples) so Ref Text stays 100% aligned with ref_codes!
        return applyGentleEdgeFadeAndNormalize(trimmed.copyOfRange(0, maxSamples), sampleRate)
    }

    private fun applyGentleEdgeFadeAndNormalize(segment: FloatArray, sampleRate: Int): FloatArray {
        if (segment.isEmpty()) return segment
        val out = FloatArray(segment.size)
        var sum = 0.0
        for (v in segment) sum += if (v.isFinite()) v.toDouble() else 0.0
        val dc = (sum / segment.size).toFloat()
        var peak = 0f
        for (i in segment.indices) {
            val v = (if (segment[i].isFinite()) segment[i] else 0f) - dc
            out[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 1e-4f) {
            val gain = (0.92f / peak).coerceAtMost(5.0f)
            for (i in out.indices) {
                out[i] = (out[i] * gain).coerceIn(-1f, 1f)
            }
        }
        // Apply 15ms smooth linear fade-in/out on the 16kHz reference window before NeuCodec encoding
        val fade = (sampleRate * 15 / 1000).coerceAtMost(out.size / 8)
        for (i in 0 until fade) {
            val w = i.toFloat() / fade.coerceAtLeast(1).toFloat()
            out[i] *= w
            out[out.lastIndex - i] *= w
        }
        return out
    }

    private fun cleanupOldTempPreviews(dir: File, keepLatest: Int) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            files.drop(keepLatest).forEach { runCatching { it.delete() } }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
