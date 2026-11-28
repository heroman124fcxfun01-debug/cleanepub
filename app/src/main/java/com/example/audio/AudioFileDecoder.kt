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

data class ImportedVoiceSample(
    val displayName: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Float,
    val previewWavPath: String
)

/**
 * 100% Offline Android Audio File Decoder (`MediaExtractor` + `MediaCodec` + direct RIFF/WAV parser).
 *
 * - Decodes MP3, M4A, AAC, OGG, FLAC, and WAV (8/16/24/32-bit PCM & 32-bit IEEE Float) cleanly to raw PCM.
 * - Downmixes multi-channel audio accurately to 1-channel Mono and resamples to exact 16,000 Hz
 *   normalized `FloatArray` in `[-1.0f, 1.0f]` ready for `speaker_encoder.onnx`.
 * - Simultaneously writes a standard 16-bit PCM Mono WAV file (with full 44-byte RIFF/WAVE header)
 *   so "Nghe thử" plays the exact original voice smoothly via `MediaPlayer` or `AudioTrack`.
 */
object AudioFileDecoder {

    const val SPEAKER_ENCODER_SAMPLE_RATE = 16_000
    private const val MAX_DECODE_SECONDS = 25.0
    private const val MIN_DECODE_SECONDS = 2.0
    const val CLONE_MIN_WINDOW_SECONDS = 3.0f
    const val CLONE_OPTIMAL_WINDOW_SECONDS = 6.0f
    const val CLONE_MAX_WINDOW_SECONDS = 8.0f

    private data class DecodedRawMono(
        val monoFloat: FloatArray,
        val sampleRate: Int
    )

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

            // 1. Try direct RIFF/WAVE PCM parser first if file starts with "RIFF...WAVE"
            //    (handles 8/16/24/32-bit PCM & 32-bit Float WAV without codec quirks).
            val rawDecoded: DecodedRawMono = tryDecodeUncompressedWav(context, uri)
                ?: decodeWithMediaExtractorAndCodec(context, uri)

            if (rawDecoded.monoFloat.isEmpty()) {
                error("Không giải mã được sóng âm PCM từ tệp đã chọn.")
            }

            val rawDurationSec = rawDecoded.monoFloat.size.toFloat() /
                rawDecoded.sampleRate.coerceAtLeast(8_000).toFloat()
            if (rawDurationSec < MIN_DECODE_SECONDS) {
                error("Tệp âm thanh quá ngắn (${String.format("%.1f", rawDurationSec)}s — cần tối thiểu 2–8 giây giọng nói rõ ràng).")
            }

            // 2. Cap to MAX_DECODE_SECONDS, remove DC offset, normalize [-1f, 1f], and resample to 16kHz Mono
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

            // Automatically trim silence & select the cleanest 5-8s speech segment for NeuCodec Encoder ONNX
            val cleanest16k = extractCleanestSpeechWindow16k(
                samples = normalized16k,
                sampleRate = safeTargetRate,
                minSeconds = CLONE_MIN_WINDOW_SECONDS,
                maxSeconds = CLONE_MAX_WINDOW_SECONDS
            )

            val finalDuration = cleanest16k.size.toFloat() / safeTargetRate.toFloat()

            // 3. Write a standard 16-bit PCM Mono WAV file with full 44-byte RIFF/WAVE header for clean preview playback
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
     * Decodes any Android-supported compressed/uncompressed audio file (MP3, M4A, AAC, OGG, FLAC, WAV)
     * via [MediaExtractor] + [MediaCodec], respecting dynamic [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED]
     * and all PCM encodings (16-bit, 32-bit Float, 8-bit, 24-bit, 32-bit int).
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
                error("Không tìm thấy luồng âm thanh hợp lệ trong tệp đã chọn.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Định dạng âm thanh không được hỗ trợ.")

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
            var pcmEncoding = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            // Dynamic growable float buffer (prevents premature cutoff if INFO_OUTPUT_FORMAT_CHANGED alters sampleRate)
            var monoBuffer = FloatArray((sourceSampleRate * 4).coerceAtLeast(32_000))
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

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var emptyPolls = 0

            while (!sawOutputEos && totalMonoSamples < maxAllowedSamples() && emptyPolls < 400) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
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
                        emptyPolls = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.get(chunk)

                            val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                            val capLimit = maxAllowedSamples()
                            val ch = channelCount.coerceAtLeast(1)

                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val floatCount = chunk.size / 4
                                    val frames = floatCount / ch
                                    for (f in 0 until frames) {
                                        if (totalMonoSamples >= capLimit) break
                                        var sum = 0f
                                        for (c in 0 until ch) {
                                            sum += bb.float
                                        }
                                        appendMonoSample((sum / ch).coerceIn(-1f, 1f))
                                    }
                                }

                                AudioFormat.ENCODING_PCM_8BIT -> {
                                    val frames = chunk.size / ch
                                    for (f in 0 until frames) {
                                        if (totalMonoSamples >= capLimit) break
                                        var sum = 0f
                                        for (c in 0 until ch) {
                                            val u = bb.get().toInt() and 0xFF
                                            sum += (u - 128) / 128f
                                        }
                                        appendMonoSample((sum / ch).coerceIn(-1f, 1f))
                                    }
                                }

                                else -> {
                                    val is24Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                        pcmEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                                    val is32Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                        pcmEncoding == AudioFormat.ENCODING_PCM_32BIT
                                    when {
                                        is24Bit -> {
                                            val frames = chunk.size / (3 * ch)
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
                                                appendMonoSample((sum / ch).coerceIn(-1f, 1f))
                                            }
                                        }
                                        is32Bit -> {
                                            val frames = (chunk.size / 4) / ch
                                            for (f in 0 until frames) {
                                                if (totalMonoSamples >= capLimit) break
                                                var sum = 0f
                                                for (c in 0 until ch) {
                                                    sum += (bb.int.toDouble() / 2_147_483_648.0).toFloat()
                                                }
                                                appendMonoSample((sum / ch).coerceIn(-1f, 1f))
                                            }
                                        }
                                        else -> {
                                            // Default ENCODING_PCM_16BIT (signed 16-bit LE)
                                            val frames = (chunk.size / 2) / ch
                                            for (f in 0 until frames) {
                                                if (totalMonoSamples >= capLimit) break
                                                var sum = 0f
                                                for (c in 0 until ch) {
                                                    sum += bb.short / 32768f
                                                }
                                                appendMonoSample((sum / ch).coerceIn(-1f, 1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }

                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        emptyPolls = 0
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }

                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (sawInputEos) emptyPolls++ else emptyPolls++
                    }
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
     * Direct RIFF/WAVE parser for uncompressed PCM (format 1: 8/16/24/32-bit) and IEEE Float (format 3: 32-bit).
     * Returns `null` if the file is not a standard uncompressed WAV (so [MediaExtractor] handles it).
     */
    private fun tryDecodeUncompressedWav(context: Context, uri: Uri): DecodedRawMono? {
        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                // Read up to 20 MB max for WAV header + audio data
                val cap = 20 * 1024 * 1024
                val buf = ByteArray(16_384)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (total < cap) {
                    val read = input.read(buf, 0, min(buf.size, cap - total))
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    total += read
                }
                out.toByteArray()
            } ?: return null

            if (bytes.size < 44) return null
            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
                bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
                bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
            ) {
                return null
            }

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var audioFormat = -1
            var numChannels = 1
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataSize = 0

            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = bb.getInt(pos + 4).coerceAtLeast(0)
                val chunkStart = pos + 8
                if (id == "fmt " && chunkSize >= 16 && chunkStart + 16 <= bytes.size) {
                    audioFormat = bb.getShort(chunkStart).toInt() and 0xFFFF
                    numChannels = (bb.getShort(chunkStart + 2).toInt() and 0xFFFF).coerceAtLeast(1)
                    sampleRate = bb.getInt(chunkStart + 4)
                    bitsPerSample = bb.getShort(chunkStart + 14).toInt() and 0xFFFF
                } else if (id == "data") {
                    dataOffset = chunkStart
                    dataSize = min(chunkSize, bytes.size - chunkStart)
                    break
                }
                pos = chunkStart + chunkSize + (chunkSize and 1)
            }

            // Only handle linear PCM (1) and IEEE float (3) directly
            if ((audioFormat != 1 && audioFormat != 3) || sampleRate < 8_000 || dataOffset < 0 || dataSize <= 0) {
                return null
            }
            val bytesPerSample = bitsPerSample / 8
            if (bytesPerSample !in setOf(1, 2, 3, 4)) return null

            val frameSize = bytesPerSample * numChannels
            val totalFrames = min(
                dataSize / frameSize,
                (sampleRate * (MAX_DECODE_SECONDS + 0.5)).toInt()
            )
            if (totalFrames <= 0) return null

            val mono = FloatArray(totalFrames)
            var cursor = dataOffset
            for (f in 0 until totalFrames) {
                var sum = 0f
                for (c in 0 until numChannels) {
                    val sample = when {
                        audioFormat == 3 && bitsPerSample == 32 -> bb.getFloat(cursor)
                        bitsPerSample == 8 -> ((bytes[cursor].toInt() and 0xFF) - 128) / 128f
                        bitsPerSample == 16 -> bb.getShort(cursor) / 32768f
                        bitsPerSample == 24 -> {
                            val b0 = bytes[cursor].toInt() and 0xFF
                            val b1 = bytes[cursor + 1].toInt() and 0xFF
                            val b2 = bytes[cursor + 2].toInt()
                            (b0 or (b1 shl 8) or (b2 shl 16)) / 8_388_608f
                        }
                        bitsPerSample == 32 -> (bb.getInt(cursor).toDouble() / 2_147_483_648.0).toFloat()
                        else -> 0f
                    }
                    cursor += bytesPerSample
                    sum += sample
                }
                mono[f] = (sum / numChannels).coerceIn(-1f, 1f)
            }

            DecodedRawMono(monoFloat = mono, sampleRate = sampleRate)
        }.getOrNull()
    }

    /**
     * Removes DC offset, applies gentle peak normalization ([-0.92f, 0.92f]), and resamples cleanly
     * with box-filter anti-aliasing when downsampling (e.g. 48kHz/44.1kHz -> 16kHz).
     */
    fun normalizeAndResampleMonoFloat(
        monoFloat: FloatArray,
        sourceRate: Int,
        targetRate: Int
    ): FloatArray {
        if (monoFloat.isEmpty()) return FloatArray(0)
        // 1. Remove DC offset & find peak
        var sum = 0.0
        for (v in monoFloat) sum += v
        val dc = (sum / monoFloat.size).toFloat()
        val centered = FloatArray(monoFloat.size)
        var peak = 0f
        for (i in monoFloat.indices) {
            val v = monoFloat[i] - dc
            centered[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        // Normalize quietly recorded voices up gently, or clamp hot signals cleanly to 0.92
        if (peak > 0.005f) {
            val gain = (0.92f / peak).coerceAtMost(5.0f)
            for (i in centered.indices) {
                centered[i] = (centered[i] * gain).coerceIn(-1f, 1f)
            }
        }

        if (sourceRate <= 0 || targetRate <= 0 || sourceRate == targetRate) {
            return centered
        }

        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLen = (centered.size.toDouble() / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val lastIdx = centered.lastIndex

        if (ratio > 1.25) {
            // Downsampling (e.g., 44.1kHz/48kHz -> 16kHz): triangular anti-aliasing window prevents aliasing hiss
            val halfWin = ratio * 0.85
            for (i in 0 until outLen) {
                val center = i * ratio
                val start = floor(center - halfWin).toInt().coerceIn(0, lastIdx)
                val end = floor(center + halfWin).toInt().coerceIn(0, lastIdx)
                var weightedSum = 0.0
                var weightTotal = 0.0
                for (j in start..end) {
                    val dist = abs(j - center) / halfWin
                    val w = (1.0 - dist).coerceAtLeast(0.0)
                    weightedSum += centered[j] * w
                    weightTotal += w
                }
                out[i] = if (weightTotal > 1e-6) {
                    (weightedSum / weightTotal).toFloat().coerceIn(-1f, 1f)
                } else {
                    centered[center.toInt().coerceIn(0, lastIdx)]
                }
            }
        } else {
            // Linear interpolation for mild rate adjustments or upsampling
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx0 = floor(srcPos).toInt().coerceIn(0, lastIdx)
                val idx1 = min(idx0 + 1, lastIdx)
                val frac = (srcPos - idx0).toFloat()
                out[i] = (centered[idx0] * (1f - frac) + centered[idx1] * frac).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    /**
     * Trims leading/trailing silence and selects the cleanest [minSeconds]..[maxSeconds] (default 5-8s)
     * active speech segment at [sampleRate] Hz for `neucodec_encoder.onnx` zero-shot voice cloning.
     *
     * Scores candidate windows by active speech ratio, consistent vocal RMS, and low silence/clipping,
     * ensuring 250-400 clean 50Hz FSQ `ref_codes` that fit comfortably inside VieNeu-TTS GGUF's context.
     */
    fun extractCleanestSpeechWindow16k(
        samples: FloatArray,
        sampleRate: Int = SPEAKER_ENCODER_SAMPLE_RATE,
        minSeconds: Float = CLONE_MIN_WINDOW_SECONDS,
        maxSeconds: Float = CLONE_MAX_WINDOW_SECONDS,
        trimEdgeMicNoiseSeconds: Float = 0.25f
    ): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples

        // 0. Strip 0.25s mic-activation / mic-stop transient ("ụp" thump) at both start & end
        // whenever recording is long enough (> 1.5s) so mic clicks are NEVER encoded into ref_codes.
        val edgeTrimSamples = (sampleRate * trimEdgeMicNoiseSeconds.coerceAtLeast(0f)).toInt()
        val workingSamples = if (edgeTrimSamples > 0 && samples.size > edgeTrimSamples * 2 + sampleRate) {
            samples.copyOfRange(edgeTrimSamples, samples.size - edgeTrimSamples)
        } else {
            samples
        }

        val frameLen = (sampleRate / 50).coerceAtLeast(160) // 20ms frame
        val numFrames = workingSamples.size / frameLen
        if (numFrames < 4) return applyGentleEdgeFadeAndNormalize(workingSamples, sampleRate)

        val frameRms = FloatArray(numFrames)
        var maxRms = 0f
        for (f in 0 until numFrames) {
            val base = f * frameLen
            var sq = 0.0
            for (i in 0 until frameLen) {
                val v = workingSamples[base + i].toDouble()
                sq += v * v
            }
            val rms = kotlin.math.sqrt(sq / frameLen).toFloat()
            frameRms[f] = rms
            if (rms > maxRms) maxRms = rms
        }

        if (maxRms < 1e-4f) return applyGentleEdgeFadeAndNormalize(workingSamples, sampleRate)

        // 1. Trim dead leading/trailing silence (keep 80ms = 4 frames natural breath margin)
        val silenceThresh = (maxRms * 0.07f).coerceIn(0.008f, 0.025f)
        var firstSpeechFrame = 0
        while (firstSpeechFrame < numFrames && frameRms[firstSpeechFrame] < silenceThresh) {
            firstSpeechFrame++
        }
        var lastSpeechFrame = numFrames - 1
        while (lastSpeechFrame > firstSpeechFrame && frameRms[lastSpeechFrame] < silenceThresh) {
            lastSpeechFrame--
        }

        val marginFrames = 4 // 80ms
        val trimStartSample = ((firstSpeechFrame - marginFrames).coerceAtLeast(0)) * frameLen
        val trimEndSample = (((lastSpeechFrame + 1 + marginFrames).coerceAtMost(numFrames)) * frameLen)
            .coerceAtMost(workingSamples.size)

        val trimmed = if (trimEndSample - trimStartSample >= sampleRate) {
            workingSamples.copyOfRange(trimStartSample, trimEndSample)
        } else {
            workingSamples
        }

        val maxSamples = (sampleRate * maxSeconds).toInt().coerceAtLeast(sampleRate)
        if (trimmed.size <= maxSamples) {
            return applyGentleEdgeFadeAndNormalize(trimmed, sampleRate)
        }

        // 2. Slide an optimal 6.0s window (within 3-8s range) across trimmed audio to pick the cleanest speech segment
        val targetSeconds = CLONE_OPTIMAL_WINDOW_SECONDS.coerceIn(minSeconds, maxSeconds)
        val winSamples = (sampleRate * targetSeconds).toInt().coerceAtMost(trimmed.size)
        val winFrames = (winSamples / frameLen).coerceAtLeast(1)
        val trimFrames = trimmed.size / frameLen
        val trimFrameRms = FloatArray(trimFrames)
        val trimFrameClip = FloatArray(trimFrames)
        for (f in 0 until trimFrames) {
            val base = f * frameLen
            var sq = 0.0
            var clips = 0
            for (i in 0 until frameLen) {
                val v = trimmed[base + i]
                sq += v.toDouble() * v.toDouble()
                if (abs(v) >= 0.985f) clips++
            }
            trimFrameRms[f] = kotlin.math.sqrt(sq / frameLen).toFloat()
            trimFrameClip[f] = clips.toFloat() / frameLen.toFloat()
        }

        val activeThresh = (maxRms * 0.12f).coerceIn(0.015f, 0.04f)
        val stepFrames = 10 // 200ms stride
        var bestStartFrame = 0
        var bestScore = Float.NEGATIVE_INFINITY

        var startF = 0
        val maxStartF = (trimFrames - winFrames).coerceAtLeast(0)
        while (startF <= maxStartF) {
            var activeCount = 0
            var activeRmsSum = 0f
            var clipSum = 0f
            for (k in 0 until winFrames) {
                val r = trimFrameRms[startF + k]
                if (r >= activeThresh) {
                    activeCount++
                    activeRmsSum += r
                }
                clipSum += trimFrameClip[startF + k]
            }
            val activeRatio = activeCount.toFloat() / winFrames.toFloat()
            val meanActiveRms = if (activeCount > 0) activeRmsSum / activeCount else 0f
            val clipRate = clipSum / winFrames.toFloat()
            // Favor high speech occupancy (0.65..0.95) & clear RMS; penalize clipping and long silences
            val score = activeRatio * 2.2f + meanActiveRms * 1.5f - clipRate * 8.0f
            if (score > bestScore) {
                bestScore = score
                bestStartFrame = startF
            }
            if (startF == maxStartF) break
            startF = (startF + stepFrames).coerceAtMost(maxStartF)
        }

        val bestStartSample = (bestStartFrame * frameLen).coerceIn(0, (trimmed.size - winSamples).coerceAtLeast(0))
        val bestEndSample = (bestStartSample + winSamples).coerceAtMost(trimmed.size)
        return applyGentleEdgeFadeAndNormalize(trimmed.copyOfRange(bestStartSample, bestEndSample), sampleRate)
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
