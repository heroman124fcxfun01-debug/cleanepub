package com.example.audio

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.abs

/**
 * Official NeuCodec ONNX Runtime Engine (Encoder + Decoder) for `pnnbao97/VieNeu-TTS` GGUF:
 *
 * 1. **Encoder (`neucodec_encoder.onnx` / `distill_neucodec_encoder.onnx`)**:
 *    - Consumes 16,000 Hz Mono Float32 audio padded to a multiple of 320 samples (`[1, 1, T]` or `[1, T]`).
 *    - Produces discrete 50 Hz FSQ speech token IDs (`IntArray`, values in `0..65535`).
 *    - Used when the user records or imports a voice sample to clone their voice 100% offline.
 *
 * 2. **Decoder (`neucodec_decoder.onnx` / `pnnbao-ump/neucodec-onnx-decoder-int8`)**:
 *    - Consumes discrete FSQ speech token IDs (`[1, 1, F]` or `[1, F]`, `INT32` or `INT64`).
 *    - Reconstructs crystal-clear 24,000 Hz Mono Float32 PCM speech waveforms (`[1, 1, T_24k]`).
 */
class NeuCodecOnnxEngine {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    @Volatile
    var ortEnvironmentStatus: String = "Chưa khởi tạo"
        private set

    val loadedSignatures = LinkedHashMap<String, String>()
    val loadErrors = LinkedHashMap<String, String>()

    val hasEncoder: Boolean
        get() = encoderSession != null

    val hasDecoder: Boolean
        get() = decoderSession != null

    @Synchronized
    fun initialize(encoderFile: File?, decoderFile: File?) {
        closeSessions()
        loadedSignatures.clear()
        loadErrors.clear()

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

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        encoderSession = loadSession(ortEnv, opts, "neucodec_encoder", encoderFile)
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
        }
    }

    /**
     * Encodes 16 kHz Mono Float32 reference voice audio into discrete 50 Hz NeuCodec FSQ speech token IDs.
     * Pads length to a multiple of 320 samples and caps to [MAX_REF_AUDIO_SECONDS] seconds so the LLM
     * context window (`n_ctx = 2048`) has ample room for target generation.
     */
    @Synchronized
    fun encodeReferenceAudio(samples: FloatArray, sampleRate: Int): Result<IntArray> {
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

            // Cap to max 10 seconds (~500 speech tokens) and pad to multiple of 320 samples
            val maxSamples = ENCODER_SAMPLE_RATE * MAX_REF_AUDIO_SECONDS
            val trimmedLen = mono16k.size.coerceAtMost(maxSamples)
            val paddedLen = ((trimmedLen + HOP_SAMPLES_16K - 1) / HOP_SAMPLES_16K) * HOP_SAMPLES_16K
            val padded = FloatArray(paddedLen)
            System.arraycopy(mono16k, 0, padded, 0, trimmedLen)

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
            try {
                for ((name, nodeInfo) in session.inputInfo) {
                    val tInfo = nodeInfo.info as? TensorInfo
                    val shape = tInfo?.shape
                    val type = tInfo?.type ?: OnnxJavaType.FLOAT
                    val lower = name.lowercase(Locale.ROOT)

                    val tensor = when {
                        type == OnnxJavaType.INT64 || type == OnnxJavaType.INT32 || lower.contains("len") -> {
                            if (type == OnnxJavaType.INT32) {
                                OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(intArrayOf(paddedLen)), longArrayOf(1L))
                            } else {
                                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(paddedLen.toLong())), longArrayOf(1L))
                            }
                        }
                        shape != null && shape.size == 2 -> {
                            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded), longArrayOf(1L, paddedLen.toLong()))
                        }
                        shape != null && shape.size == 1 -> {
                            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded), longArrayOf(paddedLen.toLong()))
                        }
                        else -> {
                            // Standard Distill-NeuCodec ONNX input: [1, 1, T]
                            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(padded), longArrayOf(1L, 1L, paddedLen.toLong()))
                        }
                    }
                    inputs[name] = tensor
                }

                session.run(inputs).use { result ->
                    if (result.size() <= 0) {
                        throw IllegalStateException("neucodec_encoder.onnx không trả về tensor codes.")
                    }
                    val flatCodes = flattenAnyToIntArray(result[0].value)
                        .filter { it in 0..65535 }
                        .toIntArray()
                    if (flatCodes.size < 8) {
                        throw IllegalStateException(
                            "neucodec_encoder.onnx chỉ trích xuất được ${flatCodes.size} token âm thanh (quá ngắn)."
                        )
                    }
                    flatCodes
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        }
    }

    /**
     * Decodes a 1D sequence of discrete NeuCodec FSQ speech token IDs (`0..65535`) into a
     * Mono 24,000 Hz Float32 PCM waveform (`[-1.0f, 1.0f]`).
     */
    @Synchronized
    fun decodeSpeechCodes(codes: IntArray): Result<FloatArray> {
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
            try {
                for ((name, nodeInfo) in session.inputInfo) {
                    val tInfo = nodeInfo.info as? TensorInfo
                    val shape = tInfo?.shape
                    val type = tInfo?.type ?: OnnxJavaType.INT32

                    val dims = when (shape?.size) {
                        3 -> longArrayOf(1L, 1L, codes.size.toLong())
                        2 -> longArrayOf(1L, codes.size.toLong())
                        1 -> longArrayOf(codes.size.toLong())
                        else -> longArrayOf(1L, 1L, codes.size.toLong())
                    }

                    val tensor = when (type) {
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
                    inputs[name] = tensor
                }

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
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        }
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
        runCatching { decoderSession?.close() }
        encoderSession = null
        decoderSession = null
    }

    companion object {
        private const val TAG = "NeuCodecOnnx"
        const val ENCODER_SAMPLE_RATE = 16_000
        const val DECODER_SAMPLE_RATE = 24_000
        private const val HOP_SAMPLES_16K = 320
        private const val MAX_REF_AUDIO_SECONDS = 10
    }
}
