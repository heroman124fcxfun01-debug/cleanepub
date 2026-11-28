package com.example.audio

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.example.CrashReportStore
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.abs

data class EncodedReferenceVoice(
    val codes: IntArray,
    val voiceEmbedding: FloatArray? = null,
    val durationSeconds: Float = 0f
)

/**
 * Official NeuCodec ONNX Runtime Engine (Encoder + Decoder) for `pnnbao97/VieNeu-TTS` GGUF:
 *
 * 1. **Encoder (`neucodec_encoder.onnx` / `distill_neucodec_encoder.onnx`)**:
 *    - Consumes cleanest 5-8s 16,000 Hz Mono Float32 audio padded to a multiple of 320 samples (`[1, 1, T]` or `[1, T]`).
 *    - Produces discrete 50 Hz FSQ speech token IDs (`IntArray`, values in `0..65535`) for true zero-shot voice cloning.
 *
 * 2. **Decoder (`neucodec_decoder.onnx` / `pnnbao-ump/neucodec-onnx-decoder-int8`)**:
 *    - Consumes discrete FSQ speech token IDs (`[1, 1, F]` or `[1, F]`, `INT32` or `INT64`).
 *    - Reconstructs crystal-clear 24,000 Hz Mono Float32 PCM speech waveforms (`[1, 1, T_24k]`).
 */
class NeuCodecOnnxEngine(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext
    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var speakerEncoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    @Volatile
    var ortEnvironmentStatus: String = "Chưa khởi tạo"
        private set

    val loadedSignatures = LinkedHashMap<String, String>()
    val loadErrors = LinkedHashMap<String, String>()

    val hasEncoder: Boolean
        get() = encoderSession != null

    val hasSpeakerEncoder: Boolean
        get() = speakerEncoderSession != null

    val hasDecoder: Boolean
        get() = decoderSession != null

    @Synchronized
    fun initialize(
        encoderFile: File?,
        decoderFile: File?,
        speakerEncoderFile: File? = null
    ) {
        closeSessions()
        loadedSignatures.clear()
        loadErrors.clear()

        // Safely pre-probe ONNX Runtime native libraries so missing .so never crashes the process
        try {
            System.loadLibrary("onnxruntime")
            runCatching { System.loadLibrary("onnxruntime4j_jni") }
        } catch (t: Throwable) {
            val err = "Không thể nạp thư viện native libonnxruntime.so:\n${OnnxModelAssetHelper.formatFullThrowable(t)}"
            ortEnvironmentStatus = "THIẾU THƯ VIỆN NATIVE ONNX"
            loadErrors["OrtNativeLib"] = err
            Log.e(TAG, err, t)
            return
        }

        val ortEnv = try {
            OrtEnvironment.getEnvironment().also {
                ortEnvironmentStatus = "OK (OrtEnvironment sẵn sàng)"
            }
        } catch (e: Throwable) {
            val err = OnnxModelAssetHelper.formatFullThrowable(e)
            ortEnvironmentStatus = "LỖI: $err"
            loadErrors["OrtEnvironment"] = err
            Log.e(TAG, "OrtEnvironment failed: $err", e)
            return
        }
        env = ortEnv

        val opts = try {
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
        } catch (e: Throwable) {
            val err = "OrtSession.SessionOptions thất bại:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            loadErrors["OrtSessionOptions"] = err
            Log.e(TAG, err, e)
            return
        }

        encoderSession = loadSession(ortEnv, opts, "neucodec_encoder", encoderFile)
        speakerEncoderSession = loadSession(ortEnv, opts, "speaker_encoder", speakerEncoderFile)
        decoderSession = loadSession(ortEnv, opts, "neucodec_decoder", decoderFile)
    }

    private fun loadSession(
        ortEnv: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        role: String,
        file: File?
    ): OrtSession? {
        if (file == null) return null
        if (!file.exists() || file.length() <= 64L) {
            loadErrors[role] = "Thiếu hoặc hỏng file ${file.name} (${file.length()} bytes tại ${file.absolutePath})"
            return null
        }
        CrashReportStore.markNativeOperationStart(
            context = appContext,
            operation = "OrtSession.createSession ($role)",
            detail = "${file.name} (${AiSystemDiagnosticReport.formatMb(file.length())})"
        )
        return try {
            val session = ortEnv.createSession(file.absolutePath, opts)
            val sig = "${file.name} (${AiSystemDiagnosticReport.formatMb(file.length())}) | " +
                "in=[${formatInputs(session)}] | out=[${formatOutputs(session)}]"
            loadedSignatures[role] = sig
            Log.i(TAG, "Loaded $role: $sig")
            session
        } catch (e: Throwable) {
            val err = "OrtSession.createSession(${file.name}) thất bại:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            loadErrors[role] = err
            Log.e(TAG, err, e)
            null
        } finally {
            CrashReportStore.clearNativeOperation(appContext)
        }
    }

    /**
     * Encodes the cleanest 5–8 second 16 kHz Mono Float32 reference voice window into discrete
     * 50 Hz NeuCodec FSQ speech token IDs (`0..65535`, ~250–400 tokens) for 100% offline zero-shot
     * voice cloning. Pads length to a multiple of 320 samples so DistilHuBERT/SQCodec aligns cleanly.
     */
    @Synchronized
    fun encodeReferenceAudio(samples: FloatArray, sampleRate: Int): Result<EncodedReferenceVoice> {
        val ortEnv = env ?: return Result.failure(IllegalStateException("OrtEnvironment chưa được khởi tạo."))
        val session = encoderSession
            ?: return Result.failure(
                IllegalStateException(
                    loadErrors["neucodec_encoder"]
                        ?: "Thiếu file neucodec_encoder.onnx trong thư mục models để trích xuất mã giọng mẫu."
                )
            )

        if (samples.isEmpty()) {
            return Result.failure(IllegalArgumentException("Âm thanh mẫu bị rỗng."))
        }

        return runCatching {
            val mono16k = if (sampleRate != ENCODER_SAMPLE_RATE) {
                AudioFileDecoder.normalizeAndResampleMonoFloat(
                    monoFloat = samples,
                    sourceRate = sampleRate,
                    targetRate = ENCODER_SAMPLE_RATE
                )
            } else {
                samples
            }

            // Pick the cleanest 5-8s speech segment (~250-400 FSQ tokens @ 50Hz) and pad to multiple of 320
            val cleanest16k = AudioFileDecoder.extractCleanestSpeechWindow16k(
                samples = mono16k,
                sampleRate = ENCODER_SAMPLE_RATE,
                minSeconds = AudioFileDecoder.CLONE_MIN_WINDOW_SECONDS,
                maxSeconds = MAX_REF_AUDIO_SECONDS.toFloat()
            )
            val trimmedLen = cleanest16k.size.coerceAtMost(ENCODER_SAMPLE_RATE * MAX_REF_AUDIO_SECONDS)
            val paddedLen = ((trimmedLen + HOP_SAMPLES_16K - 1) / HOP_SAMPLES_16K) * HOP_SAMPLES_16K
            val padded = FloatArray(paddedLen)
            System.arraycopy(cleanest16k, 0, padded, 0, trimmedLen)

            // Peak-normalize gently to 0.92 so encoder sees clean amplitude
            var peak = 0f
            for (v in padded) {
                val a = abs(v)
                if (a > peak) peak = a
            }
            if (peak > 1e-4f) {
                val gain = (0.92f / peak).coerceAtMost(6.0f)
                for (i in padded.indices) {
                    padded[i] = (padded[i] * gain).coerceIn(-1f, 1f)
                }
            }

            val inputs = LinkedHashMap<String, OnnxTensor>()
            val fedShapeDesc = ArrayList<String>()
            try {
                for ((name, nodeInfo) in session.inputInfo) {
                    val tInfo = nodeInfo.info as? TensorInfo
                    val shape = tInfo?.shape
                    val type = tInfo?.type ?: OnnxJavaType.FLOAT
                    val lower = name.lowercase(Locale.ROOT)

                    val (tensor, descShape) = when {
                        type == OnnxJavaType.INT64 || type == OnnxJavaType.INT32 || lower.contains("len") -> {
                            if (type == OnnxJavaType.INT32) {
                                OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(intArrayOf(paddedLen)), longArrayOf(1L)) to "INT32[1]"
                            } else {
                                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(paddedLen.toLong())), longArrayOf(1L)) to "INT64[1]"
                            }
                        }
                        shape != null && shape.size == 2 -> {
                            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded), longArrayOf(1L, paddedLen.toLong())) to "FLOAT[1, $paddedLen]"
                        }
                        shape != null && shape.size == 1 -> {
                            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded), longArrayOf(paddedLen.toLong())) to "FLOAT[$paddedLen]"
                        }
                        else -> {
                            // Standard NeuCodec / Distill-NeuCodec ONNX input: [1, 1, T]
                            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded), longArrayOf(1L, 1L, paddedLen.toLong())) to "FLOAT[1, 1, $paddedLen]"
                        }
                    }
                    inputs[name] = tensor
                    fedShapeDesc.add("$name=$descShape")
                }

                val durSec = trimmedLen.toFloat() / ENCODER_SAMPLE_RATE.toFloat()
                CrashReportStore.markNativeOperationStart(
                    context = appContext,
                    operation = "NeuCodec Encoder session.run",
                    detail = "samples=$paddedLen (${String.format(Locale.US, "%.2fs", durSec)} @ 16kHz) | ${fedShapeDesc.joinToString()}"
                )
                session.run(inputs).use { result ->
                    if (result.size() <= 0) {
                        throw IllegalStateException("neucodec_encoder.onnx không trả về tensor codes.")
                    }
                    val outNames = session.outputInfo.keys.toList()
                    var codesIndex = 0
                    var embIndex = -1
                    for (idx in outNames.indices) {
                        val nameLow = outNames[idx].lowercase(Locale.ROOT)
                        val tType = (session.outputInfo[outNames[idx]]?.info as? TensorInfo)?.type
                        if (tType == OnnxJavaType.INT32 || tType == OnnxJavaType.INT64 ||
                            nameLow.contains("code") || nameLow.contains("token") || nameLow.contains("id")
                        ) {
                            codesIndex = idx
                        } else if (tType == OnnxJavaType.FLOAT &&
                            (nameLow.contains("embed") || nameLow.contains("voice") || nameLow.contains("spk"))
                        ) {
                            embIndex = idx
                        }
                    }

                    val flatCodes = flattenAnyToIntArray(result[codesIndex].value)
                        .filter { it in 0..65535 }
                        .toIntArray()
                    if (flatCodes.size < 8) {
                        throw IllegalStateException(
                            "neucodec_encoder.onnx chỉ trích xuất được ${flatCodes.size} token âm thanh (quá ngắn)."
                        )
                    }
                    val optionalVoiceEmb = if (embIndex in 0 until result.size()) {
                        flattenAnyToFloatArray(result[embIndex].value).takeIf { it.isNotEmpty() }
                    } else {
                        runSpeakerEncoderIfLoaded(ortEnv, padded)
                    }
                    Log.i(
                        TAG,
                        "Encoded reference voice (${String.format(Locale.US, "%.2fs", durSec)} @ 16kHz) -> " +
                            "${flatCodes.size} FSQ codes (unique=${flatCodes.toSet().size}, embDim=${optionalVoiceEmb?.size ?: 0})"
                    )
                    EncodedReferenceVoice(
                        codes = flatCodes,
                        voiceEmbedding = optionalVoiceEmb,
                        durationSeconds = durSec
                    )
                }
            } catch (t: Throwable) {
                val schema = "Encoder schema: in=[${formatInputs(session)}] | out=[${formatOutputs(session)}] | fed=[${fedShapeDesc.joinToString()}]"
                Log.e(TAG, "encodeReferenceAudio failed | $schema", t)
                throw IllegalStateException("$schema\n${t.message ?: t.javaClass.simpleName}", t)
            } finally {
                CrashReportStore.clearNativeOperation(appContext)
                inputs.values.forEach { runCatching { it.close() } }
            }
        }
    }

    private fun runSpeakerEncoderIfLoaded(ortEnv: OrtEnvironment, padded16k: FloatArray): FloatArray? {
        val spkSession = speakerEncoderSession ?: return null
        val spkInputs = LinkedHashMap<String, OnnxTensor>()
        return try {
            for ((name, nodeInfo) in spkSession.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT
                val tensor = when {
                    type == OnnxJavaType.INT64 ->
                        OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(padded16k.size.toLong())), longArrayOf(1L))
                    type == OnnxJavaType.INT32 ->
                        OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(intArrayOf(padded16k.size)), longArrayOf(1L))
                    shape != null && shape.size == 3 ->
                        OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded16k), longArrayOf(1L, 1L, padded16k.size.toLong()))
                    shape != null && shape.size == 1 ->
                        OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded16k), longArrayOf(padded16k.size.toLong()))
                    else ->
                        OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded16k), longArrayOf(1L, padded16k.size.toLong()))
                }
                spkInputs[name] = tensor
            }
            spkSession.run(spkInputs).use { res ->
                if (res.size() > 0) flattenAnyToFloatArray(res[0].value).takeIf { it.isNotEmpty() } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Optional speaker_encoder.onnx inference skipped: ${t.message}")
            null
        } finally {
            spkInputs.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Decodes a 1D sequence of discrete NeuCodec FSQ speech token IDs (`0..65535`) into a
     * Mono 24,000 Hz Float32 PCM waveform (`[-1.0f, 1.0f]`).
     *
     * Dynamically inspects `session.inputInfo` so it seamlessly handles:
     * - Standard NeuCodec ONNX decoder (`codes` [1, 1, F] or [1, F], `INT32`/`INT64`).
     * - Multi-input decoders (`content_ids` + `voice_embedding` / `voice` [1, D] `FLOAT`).
     */
    @Synchronized
    fun decodeSpeechCodes(
        codes: IntArray,
        voiceEmbedding: FloatArray? = null
    ): Result<FloatArray> {
        val ortEnv = env ?: return Result.failure(IllegalStateException("OrtEnvironment chưa được khởi tạo."))
        val session = decoderSession
            ?: return Result.failure(
                IllegalStateException(
                    loadErrors["neucodec_decoder"]
                        ?: "Thiếu file neucodec_decoder.onnx trong thư mục models để giải mã sóng âm 24kHz."
                )
            )
        if (codes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Chuỗi mã speech token trống."))
        }

        return runCatching {
            val inputs = LinkedHashMap<String, OnnxTensor>()
            val feedTrace = mutableListOf<String>()
            val hasIntInput = session.inputInfo.values.any { nodeInfo ->
                val t = (nodeInfo.info as? TensorInfo)?.type
                t == OnnxJavaType.INT32 || t == OnnxJavaType.INT64
            }

            try {
                for ((name, nodeInfo) in session.inputInfo) {
                    val tInfo = nodeInfo.info as? TensorInfo
                    val shape = tInfo?.shape
                    val type = tInfo?.type ?: OnnxJavaType.INT32
                    val lower = name.lowercase(Locale.ROOT)

                    val isVoiceEmbeddingInput = lower.contains("voice") ||
                        lower.contains("embed") ||
                        lower.contains("spk") ||
                        lower.contains("speaker") ||
                        (type == OnnxJavaType.FLOAT && hasIntInput)

                    val tensor = if (isVoiceEmbeddingInput) {
                        val embDim = resolveEmbeddingDim(shape, voiceEmbedding?.size ?: 256)
                        val embVec = adaptVoiceEmbedding(voiceEmbedding, embDim)
                        val dims = when (shape?.size) {
                            3 -> longArrayOf(1L, 1L, embDim.toLong())
                            2 -> longArrayOf(1L, embDim.toLong())
                            1 -> longArrayOf(embDim.toLong())
                            else -> longArrayOf(1L, embDim.toLong())
                        }
                        feedTrace.add("$name:FLOAT${dims.contentToString()}")
                        OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(embVec), dims)
                    } else {
                        val dims = when (shape?.size) {
                            3 -> longArrayOf(1L, 1L, codes.size.toLong())
                            2 -> longArrayOf(1L, codes.size.toLong())
                            1 -> longArrayOf(codes.size.toLong())
                            else -> longArrayOf(1L, 1L, codes.size.toLong())
                        }
                        feedTrace.add("$name:$type${dims.contentToString()}")
                        when (type) {
                            OnnxJavaType.INT64 -> {
                                val longs = LongArray(codes.size) { codes[it].toLong() }
                                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longs), dims)
                            }
                            OnnxJavaType.FLOAT -> {
                                val floats = FloatArray(codes.size) { codes[it].toFloat() }
                                OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floats), dims)
                            }
                            else -> {
                                OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(codes), dims)
                            }
                        }
                    }
                    inputs[name] = tensor
                }

                CrashReportStore.markNativeOperationStart(
                    context = appContext,
                    operation = "NeuCodec Decoder session.run",
                    detail = "codes=${codes.size}, inputs=${feedTrace.joinToString(", ")}"
                )
                session.run(inputs).use { result ->
                    if (result.size() <= 0) {
                        throw IllegalStateException("neucodec_decoder.onnx không trả về sóng âm đầu ra.")
                    }
                    val raw = flattenAnyToFloatArray(result[0].value)
                    if (raw.isEmpty()) {
                        throw IllegalStateException("Sóng âm giải mã từ neucodec_decoder.onnx bị rỗng.")
                    }
                    cleanAndNormalizeWaveform(raw)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "decodeSpeechCodes failed (feeds=${feedTrace.joinToString()}): ${e.message}", e)
                throw IllegalStateException(
                    "Giải mã ONNX thất bại [inputs=${feedTrace.joinToString(", ")}]: ${e.message}",
                    e
                )
            } finally {
                CrashReportStore.clearNativeOperation(appContext)
                inputs.values.forEach { runCatching { it.close() } }
            }
        }
    }

    private fun resolveEmbeddingDim(shape: LongArray?, fallbackDim: Int): Int {
        if (shape != null) {
            for (i in shape.lastIndex downTo 0) {
                val d = shape[i].toInt()
                if (d > 1) return d
            }
        }
        return fallbackDim.coerceAtLeast(1)
    }

    private fun adaptVoiceEmbedding(source: FloatArray?, targetDim: Int): FloatArray {
        val out = FloatArray(targetDim)
        if (source != null && source.isNotEmpty()) {
            if (source.size == targetDim) {
                System.arraycopy(source, 0, out, 0, targetDim)
            } else {
                val maxIdx = (source.size - 1).coerceAtLeast(1)
                val outMax = (targetDim - 1).coerceAtLeast(1)
                for (i in 0 until targetDim) {
                    val pos = i.toFloat() * maxIdx / outMax
                    val lo = pos.toInt().coerceIn(0, source.lastIndex)
                    val hi = (lo + 1).coerceIn(0, source.lastIndex)
                    val frac = pos - lo
                    out[i] = source[lo] * (1f - frac) + source[hi] * frac
                }
            }
        } else {
            val v = (1.0 / kotlin.math.sqrt(targetDim.toDouble())).toFloat()
            out.fill(v)
        }
        var normSq = 0.0
        for (v in out) normSq += v.toDouble() * v.toDouble()
        if (normSq > 1e-12) {
            val inv = (1.0 / kotlin.math.sqrt(normSq)).toFloat()
            for (i in out.indices) out[i] *= inv
        }
        return out
    }

    private fun cleanAndNormalizeWaveform(raw: FloatArray): FloatArray {
        val out = FloatArray(raw.size)
        var sum = 0.0
        var finiteCount = 0
        for (i in raw.indices) {
            val v = raw[i]
            if (v.isFinite()) {
                out[i] = v
                sum += v
                finiteCount++
            }
        }
        if (finiteCount == 0) return FloatArray(0)
        val dc = (sum / finiteCount).toFloat()
        var peak = 0f
        for (i in out.indices) {
            val v = out[i] - dc
            out[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val gain = when {
            peak > 0.96f -> 0.94f / peak
            peak in 1e-4f..0.20f -> (0.65f / peak).coerceAtMost(4.0f)
            else -> 1.0f
        }
        if (gain != 1.0f) {
            for (i in out.indices) {
                out[i] = (out[i] * gain).coerceIn(-0.98f, 0.98f)
            }
        }
        // Apply 8ms edge fade-in/fade-out to avoid audible clicks between sentences
        val fade = (DECODER_SAMPLE_RATE * 8 / 1000).coerceAtMost(out.size / 4)
        for (i in 0 until fade) {
            val w = i.toFloat() / fade.coerceAtLeast(1).toFloat()
            out[i] *= w
            out[out.lastIndex - i] *= w
        }
        return out
    }

    private fun flattenAnyToIntArray(value: Any?): IntArray {
        val acc = ArrayList<Int>(512)
        fun walk(obj: Any?) {
            when (obj) {
                null -> Unit
                is IntArray -> for (v in obj) acc.add(v)
                is LongArray -> for (v in obj) acc.add(v.toInt())
                is ShortArray -> for (v in obj) acc.add(v.toInt())
                is FloatArray -> for (v in obj) acc.add(v.toInt())
                is DoubleArray -> for (v in obj) acc.add(v.toInt())
                is Array<*> -> for (elem in obj) walk(elem)
                is Number -> acc.add(obj.toInt())
            }
        }
        walk(value)
        return acc.toIntArray()
    }

    private fun flattenAnyToFloatArray(value: Any?): FloatArray {
        val acc = ArrayList<Float>(16384)
        fun walk(obj: Any?) {
            when (obj) {
                null -> Unit
                is FloatArray -> for (v in obj) acc.add(v)
                is DoubleArray -> for (v in obj) acc.add(v.toFloat())
                is ShortArray -> for (v in obj) acc.add(v / 32768f)
                is Array<*> -> for (elem in obj) walk(elem)
                is Number -> acc.add(obj.toFloat())
            }
        }
        walk(value)
        return acc.toFloatArray()
    }

    private fun formatInputs(session: OrtSession): String =
        session.inputInfo.entries.joinToString(", ") { (k, v) ->
            val ti = v.info as? TensorInfo
            "$k:${ti?.type ?: "?"}${ti?.shape?.contentToString() ?: "[]"}"
        }

    private fun formatOutputs(session: OrtSession): String =
        session.outputInfo.entries.joinToString(", ") { (k, v) ->
            val ti = v.info as? TensorInfo
            "$k:${ti?.type ?: "?"}${ti?.shape?.contentToString() ?: "[]"}"
        }

    @Synchronized
    fun closeSessions() {
        runCatching { encoderSession?.close() }
        runCatching { speakerEncoderSession?.close() }
        runCatching { decoderSession?.close() }
        encoderSession = null
        speakerEncoderSession = null
        decoderSession = null
    }

    companion object {
        private const val TAG = "NeuCodecOnnx"
        const val ENCODER_SAMPLE_RATE = 16_000
        const val DECODER_SAMPLE_RATE = 24_000
        private const val HOP_SAMPLES_16K = 320
        private const val MAX_REF_AUDIO_SECONDS = 8
    }
}
