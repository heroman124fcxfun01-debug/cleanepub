package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.min

data class ImportedVoiceSample(
    val displayName: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Float
)

/**
 * 100% Offline Android Audio File Decoder (`MediaExtractor` + `MediaCodec`).
 * Imports user-selected `.wav`, `.mp3`, `.m4a`, `.aac`, `.ogg`, `.flac` files via SAF,
 * downmixes to Mono, caps cleanly at 8 seconds (minimum 3 seconds), and resamples to
 * normalized Mono 16kHz / 24kHz [FloatArray] ready for ONNX speaker embedding extraction.
 */
object AudioFileDecoder {

    private const val MAX_DECODE_SECONDS = 8.0
    private const val MIN_DECODE_SECONDS = 2.0

    suspend fun decodeAudioUriToMonoFloat(
        context: Context,
        uri: Uri,
        targetSampleRate: Int = VoiceSampleRecorder.DEFAULT_TARGET_SAMPLE_RATE
    ): Result<ImportedVoiceSample> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(context, uri)
                ?.substringBeforeLast('.')
                ?.replace('_', ' ')
                ?.trim()
                ?.take(36)
                ?.ifBlank { "Giọng đã nhập" }
                ?: "Giọng đã nhập"

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
                    trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44_100
                }
                var channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else {
                    1
                }

                val maxMonoSamples = (sourceSampleRate * MAX_DECODE_SECONDS).toInt().coerceAtLeast(16_000)
                val monoAccumulator = ShortArray(maxMonoSamples)
                var totalMonoSamples = 0

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(trackFormat, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var iterations = 0

                while (!sawOutputEos && totalMonoSamples < maxMonoSamples && iterations < 4000) {
                    iterations++
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
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
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val shortView = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val totalShorts = shortView.remaining()
                                val frames = totalShorts / channelCount
                                val framesToCopy = min(frames, maxMonoSamples - totalMonoSamples)
                                for (f in 0 until framesToCopy) {
                                    var sum = 0
                                    for (c in 0 until channelCount) {
                                        sum += shortView.get().toInt()
                                    }
                                    monoAccumulator[totalMonoSamples++] = (sum / channelCount)
                                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                        .toShort()
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }

                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sourceSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            }
                        }
                    }
                }

                if (totalMonoSamples <= 0) {
                    error("Không giải mã được dữ liệu âm thanh từ tệp này.")
                }

                val durationSec = totalMonoSamples.toFloat() / sourceSampleRate.coerceAtLeast(8000).toFloat()
                if (durationSec < MIN_DECODE_SECONDS) {
                    error("Tệp âm thanh quá ngắn (cần tối thiểu 2–8 giây giọng nói rõ ràng).")
                }

                val trimmedPcm = monoAccumulator.copyOf(totalMonoSamples)
                val normalizedFloat = VoiceSampleRecorder.normalizeAndResampleToMonoFloat(
                    pcm16 = trimmedPcm,
                    sourceRate = sourceSampleRate,
                    targetRate = targetSampleRate
                )
                val finalDuration = normalizedFloat.size.toFloat() / targetSampleRate.toFloat()

                ImportedVoiceSample(
                    displayName = displayName,
                    samples = normalizedFloat,
                    sampleRate = targetSampleRate,
                    durationSeconds = finalDuration
                )
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
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
