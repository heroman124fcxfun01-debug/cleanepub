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
    val durationSeconds: Float = 0f,
    val refSpeechTokens: String = "",
    val first10Tokens: IntArray = IntArray(0),
    val minToken: Int = 0,
    val maxToken: Int = 0,
    val uniqueTokenCount: Int = 0,
    val encoderIoSummary: String = ""
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
    private var cachedSessionOpts: OrtSession.SessionOptions? = null
    private var encoderModelFile: File? = null
    private var speakerEncoderModelFile: File? = null
    private var encoderSession: OrtSession? = null
    private var speakerEncoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    @Volatile
    var ortEnvironmentStatus: String = "Chưa khởi tạo"
        private set

    val loadedSignatures = LinkedHashMap<String, String>()
    val loadErrors = LinkedHashMap<String, String>()

    /** True when `neucodec_encoder.onnx` exists on disk (or is currently loaded) for on-demand voice cloning. */
    val hasEncoder: Boolean
        get() = encoderSession != null || (encoderModelFile?.let { it.exists() && it.length() > 64L } == true)

    val isEncoderResidentInRam: Boolean
        get() = encoderSession != null

    val hasSpeakerEncoder: Boolean
        get() = speakerEncoderSession != null || (speakerEncoderModelFile?.let { it.exists() && it.length() > 64L } == true)

    val hasDecoder: Boolean
        get() = decoderSession != null

    /**
     * Initializes ONNX Runtime and loads ONLY `neucodec_decoder.onnx` for low-RAM book reading.
     * `neucodec_encoder.onnx` (>150 MB RAM) is kept on disk and loaded strictly on-demand inside
     * [encodeReferenceAudio], then closed immediately via [closeEncoderSession] once `ref_codes` are extracted!
     */
    @Synchronized
    fun initialize(
        encoderFile: File?,
        decoderFile: File?,
        speakerEncoderFile: File? = null,
        preloadEncoder: Boolean = false
    ) {
        closeSessions()
        loadedSignatures.clear()
        loadErrors.clear()
        encoderModelFile = encoderFile
        speakerEncoderModelFile = speakerEncoderFile

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
                // Fixed 3 threads for Helio G99 thermal stability (prevents thermal throttling during long books)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 3))
                setInterOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Prevent idle ORT worker threads from busy-spinning while llama.cpp runs GGUF decode
                runCatching { addConfigEntry("session.intra_op.allow_spinning", "0") }
            }
        } catch (e: Throwable) {
            val err = "OrtSession.SessionOptions thất bại:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            loadErrors["OrtSessionOptions"] = err
            Log.e(TAG, err, e)
            return
        }
        cachedSessionOpts = opts

        // Keep ONLY neucodec_decoder.onnx resident in RAM during reading!
        decoderSession = loadSession(ortEnv, opts, "neucodec_decoder", decoderFile)

        if (encoderFile != null && encoderFile.exists() && encoderFile.length() > 64L) {
            if (preloadEncoder) {
                encoderSession = loadSession(ortEnv, opts, "neucodec_encoder", encoderFile)
                speakerEncoderSession = loadSession(ortEnv, opts, "speaker_encoder", speakerEncoderFile)
            } else {
                loadedSignatures["neucodec_encoder"] =
                    "${encoderFile.name} (${AiSystemDiagnosticReport.formatMb(encoderFile.length())}) | ON-DEMAND (chỉ nạp khi tạo giọng, giải phóng >150MB RAM khi đọc sách)"
            }
        }
    }

    /**
     * Immediately closes `neucodec_encoder.onnx` & optional `speaker_encoder.onnx` OrtSessions
     * to reclaim >150 MB of native RAM right after extracting `ref_codes`.
     */
    @Synchronized
    fun closeEncoderSession() {
        val hadEncoder = encoderSession != null || speakerEncoderSession != null
        runCatching { encoderSession?.close() }
        runCatching { speakerEncoderSession?.close() }
        encoderSession = null
        speakerEncoderSession = null
        if (hadEncoder) {
            encoderModelFile?.takeIf { it.exists() && it.length() > 64L }?.let { f ->
                loadedSignatures["neucodec_encoder"] =
                    "${f.name} (${AiSystemDiagnosticReport.formatMb(f.length())}) | ON-DEMAND (đã đóng OrtSession, giải phóng >150MB RAM)"
            }
            Log.i(TAG, "Closed neucodec_encoder OrtSession — reclaimed >150 MB native RAM.")
        }
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
     * On-demand zero-shot voice cloning encoder (`neucodec_encoder.onnx`):
     * - Opens `encoderSession` lazily ONLY when encoding a newly recorded/imported voice.
     * - Normalizes to exact 16,000 Hz Mono Float32 (`[-1.0f, 1.0f]`), trims only absolute silence
     *   (`< 0.005f`) up to max 0.15s, caps strictly at 7.0s (`112,000` samples), pads to multiple of 320.
     * - Extracts exact `INT32[1, 1, N]` chronological order (`(output[0].value as Array<Array<IntArray>>)[0][0]`).
     * - **Always closes `encoderSession` in `finally`** to free >150 MB native RAM immediately!
     */
    @Synchronized
    fun encodeReferenceAudio(
        samples: FloatArray,
        sampleRate: Int,
        alreadyPreprocessed: Boolean = false
    ): Result<EncodedReferenceVoice> {
        val ortEnv = env ?: return Result.failure(IllegalStateException("OrtEnvironment chưa được khởi tạo."))
        val opts = cachedSessionOpts ?: return Result.failure(IllegalStateException("OrtSession.SessionOptions chưa sẵn sàng."))

        if (samples.isEmpty()) {
            return Result.failure(IllegalArgumentException("Âm thanh mẫu bị rỗng."))
        }

        val encFile = encoderModelFile
        if (encoderSession == null) {
            if (encFile == null || !encFile.exists() || encFile.length() <= 64L) {
                return Result.failure(
                    IllegalStateException(
                        loadErrors["neucodec_encoder"]
                            ?: "Thiếu file neucodec_encoder.onnx trong thư mục models để trích xuất mã giọng mẫu."
                    )
                )
            }
            encoderSession = loadSession(ortEnv, opts, "neucodec_encoder", encFile)
            if (speakerEncoderSession == null && speakerEncoderModelFile != null) {
                speakerEncoderSession = loadSession(ortEnv, opts, "speaker_encoder", speakerEncoderModelFile)
            }
        }

        val session = encoderSession
            ?: return Result.failure(
                IllegalStateException(
                    loadErrors["neucodec_encoder"]
                        ?: "Không thể khởi tạo OrtSession cho neucodec_encoder.onnx."
                )
            )

        return try {
            runCatching {
            val mono16k = if (sampleRate != ENCODER_SAMPLE_RATE) {
                AudioFileDecoder.normalizeAndResampleMonoFloat(
                    monoFloat = samples,
                    sourceRate = sampleRate,
                    targetRate = ENCODER_SAMPLE_RATE
                )
            } else {
                samples
            }

            // Trim ONLY absolute silence (< 0.005f, max 0.15s) & cap strictly to 7.0s (112,000 samples @ 16kHz)
            val cleanest16k = if (alreadyPreprocessed) {
                mono16k
            } else {
                AudioFileDecoder.extractCleanestSpeechWindow16k(
                    samples = mono16k,
                    sampleRate = ENCODER_SAMPLE_RATE,
                    minSeconds = AudioFileDecoder.CLONE_MIN_WINDOW_SECONDS,
                    maxSeconds = MAX_REF_AUDIO_SECONDS.toFloat(),
                    trimEdgeMicNoiseSeconds = AudioFileDecoder.MAX_EDGE_SILENCE_TRIM_SECONDS
                )
            }
            val trimmedLen = cleanest16k.size.coerceAtMost(MAX_REF_AUDIO_SAMPLES_16K)
            if (trimmedLen < MIN_REF_AUDIO_SAMPLES_16K) {
                val sec = trimmedLen.toFloat() / ENCODER_SAMPLE_RATE.toFloat()
                throw IllegalArgumentException(
                    "Âm thanh mẫu quá ngắn (${String.format(Locale.US, "%.1f", sec)}s). Vui lòng dùng đoạn ghi âm rõ tiếng dài từ 4 đến 7 giây."
                )
            }
            // Block silent/corrupt reference audio (RMS < 0.001f) BEFORE peak normalization
            val inputRms = AudioFileDecoder.computeRms(
                if (trimmedLen == cleanest16k.size) cleanest16k else cleanest16k.copyOf(trimmedLen)
            )
            if (inputRms < AudioFileDecoder.MIN_VALID_AUDIO_RMS) {
                throw IllegalArgumentException(AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE)
            }

            val paddedLen = ((trimmedLen + HOP_SAMPLES_16K - 1) / HOP_SAMPLES_16K) * HOP_SAMPLES_16K
            val padded = FloatArray(paddedLen)
            System.arraycopy(cleanest16k, 0, padded, 0, trimmedLen)

            // Peak-normalize 16kHz Mono Float32 input evenly to 0.9f before neucodec_encoder.onnx
            var peak = 0f
            for (v in padded) {
                val a = abs(v)
                if (a > peak) peak = a
            }
            if (peak > 1e-4f) {
                val gain = (AudioFileDecoder.ENCODER_PEAK_NORMALIZE_TARGET / peak)
                    .coerceAtMost(AudioFileDecoder.MAX_PEAK_NORMALIZE_GAIN)
                for (i in padded.indices) {
                    padded[i] = (padded[i] * gain).coerceIn(-1f, 1f)
                }
            }

            val inputs = LinkedHashMap<String, OnnxTensor>()
            val fedShapeDesc = ArrayList<String>()
            val ioContractSummary = "in=[${formatInputs(session)}] | out=[${formatOutputs(session)}]"
            Log.i(TAG, "neucodec_encoder.onnx metadata: $ioContractSummary")
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
                            // Standard NeuCodec / Distill-NeuCodec ONNX input: FLOAT[1, 1, numSamples] @ 16000 Hz Mono
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

                    val codesOutNode = result[codesIndex]
                    val codesOutTensorInfo = codesOutNode.info as? TensorInfo
                    val codesOutDims = codesOutTensorInfo?.shape
                    val codesOutShape = codesOutDims?.contentToString() ?: "?"
                    val rawCodesValue = codesOutNode.value
                    val extractedCodes: IntArray = extractChronologicalCodes(rawCodesValue, codesOutDims)
                    val outOfRangeCount = extractedCodes.count { it !in 0..65535 }
                    val flatCodes = extractedCodes
                        .filter { it in 0..65535 }
                        .toIntArray()
                    val first10 = flatCodes.take(10).toIntArray()
                    val uniqueCount = flatCodes.distinct().size
                    val minTok = flatCodes.minOrNull() ?: 0
                    val maxTok = flatCodes.maxOrNull() ?: 0
                    val countIn1024 = flatCodes.count { it in 0..1023 }

                    Log.i(
                        TAG,
                        "Encoder output: shape=$codesOutShape | rawLen=${extractedCodes.size} | validLen=${flatCodes.size} | " +
                            "outOfRange=$outOfRangeCount | unique=$uniqueCount | " +
                            "range=$minTok..$maxTok (in0..1023=$countIn1024, ggufVocab=0..65535) | " +
                            "first10=${first10.contentToString()} | " +
                            "first5Tags=${VieNeuGgufRunner.formatRefSpeechTokens(flatCodes, 5)}"
                    )

                    val invalidCodes = flatCodes.size < MIN_VALID_REF_CODES ||
                        outOfRangeCount > 0 ||
                        flatCodes.all { it == 0 } ||
                        uniqueCount < MIN_UNIQUE_REF_CODES
                    if (invalidCodes) {
                        throw IllegalArgumentException(
                            "${AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE} " +
                                "(shape=$codesOutShape, len=${flatCodes.size}c, outOfRange=$outOfRangeCount, " +
                                "unique=$uniqueCount, first10=${first10.contentToString()})."
                        )
                    }
                    val optionalVoiceEmb = if (embIndex in 0 until result.size()) {
                        flattenAnyToFloatArray(result[embIndex].value).takeIf { it.isNotEmpty() }
                    } else {
                        runSpeakerEncoderIfLoaded(ortEnv, padded)
                    }
                    val canonicalTokensStr = VieNeuGgufRunner.formatRefSpeechTokens(flatCodes)
                    Log.i(
                        TAG,
                        "Encoded reference voice (${String.format(Locale.US, "%.2fs", durSec)} @ 16kHz) -> " +
                            "${flatCodes.size} FSQ codes (unique=$uniqueCount, range=$minTok..$maxTok, embDim=${optionalVoiceEmb?.size ?: 0})"
                    )
                    EncodedReferenceVoice(
                        codes = flatCodes,
                        voiceEmbedding = optionalVoiceEmb,
                        durationSeconds = durSec,
                        refSpeechTokens = canonicalTokensStr,
                        first10Tokens = first10,
                        minToken = minTok,
                        maxToken = maxTok,
                        uniqueTokenCount = uniqueCount,
                        encoderIoSummary = "fed=[${fedShapeDesc.joinToString()}] -> out=$codesOutShape"
                    )
                }
            } catch (t: Throwable) {
                if (t is IllegalArgumentException && t.message?.startsWith(AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE) == true) {
                    throw t
                }
                val schema = "Encoder schema: $ioContractSummary | fed=[${fedShapeDesc.joinToString()}]"
                Log.e(TAG, "encodeReferenceAudio failed | $schema", t)
                throw IllegalStateException("$schema\n${t.message ?: t.javaClass.simpleName}", t)
            } finally {
                CrashReportStore.clearNativeOperation(appContext)
                inputs.values.forEach { runCatching { it.close() } }
            }
            }
        } finally {
            // Immediately release neucodec_encoder.onnx OrtSession to reclaim >150 MB RAM before TTS/reading!
            closeEncoderSession()
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
                    val outVal = result[0].value
                    val direct3d = ((outVal as? Array<*>)?.firstOrNull() as? Array<*>)?.firstOrNull() as? FloatArray
                    val raw = direct3d ?: flattenAnyToFloatArray(outVal)
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
        if (raw.isEmpty()) return FloatArray(0)

        // 1. Strip initial 20ms (480 samples @ 24kHz) DC / ISTFT startup spike from neucodec_decoder.onnx
        val startupTrimSamples = (DECODER_SAMPLE_RATE * 20) / 1000 // 480 samples @ 24000 Hz
        val trimmedRaw = if (raw.size > startupTrimSamples * 4) {
            raw.copyOfRange(startupTrimSamples, raw.size)
        } else {
            raw
        }

        val out = FloatArray(trimmedRaw.size)
        var sum = 0.0
        var finiteCount = 0
        for (i in trimmedRaw.indices) {
            val v = trimmedRaw[i]
            if (v.isFinite()) {
                out[i] = v
                sum += v
                finiteCount++
            }
        }
        if (finiteCount == 0) return FloatArray(0)

        // 2. Remove DC offset and compute peak amplitude
        val dc = (sum / finiteCount).toFloat()
        var peak = 0f
        for (i in out.indices) {
            val v = out[i] - dc
            out[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }

        // 3. Normalize gently and clamp strictly within [-1.0f, 1.0f]
        val gain = when {
            peak > 0.96f -> 0.94f / peak
            peak in 1e-4f..0.20f -> (0.65f / peak).coerceAtMost(4.0f)
            else -> 1.0f
        }
        for (i in out.indices) {
            out[i] = (out[i] * gain).coerceIn(-1.0f, 1.0f)
        }

        // 4. Apply 20ms linear fade-in (0.0 -> 1.0 over first 480 samples @ 24000 Hz)
        //    so the speaker diaphragm ramps smoothly with ZERO opening "ụp" pop, plus 15ms fade-out.
        val fadeInSamples = ((DECODER_SAMPLE_RATE * 20) / 1000).coerceAtMost(out.size / 3)
        for (i in 0 until fadeInSamples) {
            val w = i.toFloat() / fadeInSamples.coerceAtLeast(1).toFloat()
            out[i] = (out[i] * w).coerceIn(-1.0f, 1.0f)
        }
        val fadeOutSamples = ((DECODER_SAMPLE_RATE * 15) / 1000).coerceAtMost(out.size / 4)
        for (i in 0 until fadeOutSamples) {
            val w = i.toFloat() / fadeOutSamples.coerceAtLeast(1).toFloat()
            val idx = out.lastIndex - i
            out[idx] = (out[idx] * w).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    /**
     * Extracts 1D speech token IDs in strict chronological frame order from any NeuCodec ONNX
     * encoder output layout (`[1, 1, N]`, `[1, N, 1]`, `[N, 1, 1]`, `[1, N]`, `[N, 1]`, or `[N]`),
     * preventing accidental axis transpositions or scrambling between batch/channel/time axes.
     */
    private fun extractChronologicalCodes(rawValue: Any?, dims: LongArray?): IntArray {
        if (rawValue == null) return IntArray(0)
        when (rawValue) {
            is IntArray -> return rawValue.copyOf()
            is LongArray -> return IntArray(rawValue.size) { rawValue[it].toInt() }
        }

        val outer = rawValue as? Array<*> ?: return flattenAnyToIntArray(rawValue)
        if (outer.isEmpty()) return IntArray(0)
        val first0 = outer[0]

        // 3D tensors: [1, 1, N], [1, N, 1], or [N, 1, 1]
        if (first0 is Array<*>) {
            val d0 = outer.size
            val d1 = first0.size
            val firstInner = first0.firstOrNull()
            val d2 = when (firstInner) {
                is IntArray -> firstInner.size
                is LongArray -> firstInner.size
                else -> 0
            }
            if (d1 > 0 && d2 > 0) {
                if (dims != null && dims.size == 3) {
                    Log.d(TAG, "extractChronologicalCodes 3D: dims=${dims.contentToString()} runtime=[$d0, $d1, $d2]")
                }
                // Standard NeuCodec [1, 1, N]
                if (d0 == 1 && d1 == 1) {
                    return when (firstInner) {
                        is IntArray -> firstInner.copyOf()
                        is LongArray -> IntArray(firstInner.size) { firstInner[it].toInt() }
                        else -> flattenAnyToIntArray(rawValue)
                    }
                }
                // Transposed [1, N, 1]
                if (d0 == 1 && d2 == 1 && d1 > 1) {
                    return IntArray(d1) { t ->
                        when (val inner = first0[t]) {
                            is IntArray -> inner.firstOrNull() ?: 0
                            is LongArray -> inner.firstOrNull()?.toInt() ?: 0
                            else -> 0
                        }
                    }
                }
                // Transposed [N, 1, 1]
                if (d1 == 1 && d2 == 1 && d0 > 1) {
                    return IntArray(d0) { t ->
                        val mid = outer[t] as? Array<*>
                        when (val inner = mid?.firstOrNull()) {
                            is IntArray -> inner.firstOrNull() ?: 0
                            is LongArray -> inner.firstOrNull()?.toInt() ?: 0
                            else -> 0
                        }
                    }
                }
            }
            return flattenAnyToIntArray(rawValue)
        }

        // 2D tensors: [1, N] or [N, 1]
        if (first0 is IntArray || first0 is LongArray) {
            val d0 = outer.size
            val d1 = when (first0) {
                is IntArray -> first0.size
                is LongArray -> first0.size
                else -> 0
            }
            if (d0 == 1) {
                return when (first0) {
                    is IntArray -> first0.copyOf()
                    is LongArray -> IntArray(first0.size) { first0[it].toInt() }
                    else -> flattenAnyToIntArray(rawValue)
                }
            }
            if (d1 == 1 && d0 > 1) {
                return IntArray(d0) { t ->
                    when (val row = outer[t]) {
                        is IntArray -> row.firstOrNull() ?: 0
                        is LongArray -> row.firstOrNull()?.toInt() ?: 0
                        else -> 0
                    }
                }
            }
        }

        return flattenAnyToIntArray(rawValue)
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
        closeEncoderSession()
        runCatching { decoderSession?.close() }
        runCatching { cachedSessionOpts?.close() }
        decoderSession = null
        cachedSessionOpts = null
    }

    companion object {
        private const val TAG = "NeuCodecOnnx"
        const val ENCODER_SAMPLE_RATE = 16_000
        const val DECODER_SAMPLE_RATE = 24_000
        const val MIN_VALID_REF_CODES = 30
        const val MIN_UNIQUE_REF_CODES = 8
        private const val HOP_SAMPLES_16K = 320
        private const val MIN_REF_AUDIO_SECONDS = 4
        const val MIN_REF_AUDIO_SAMPLES_16K = 64_000
        private const val MAX_REF_AUDIO_SECONDS = 7
        const val MAX_REF_AUDIO_SAMPLES_16K = 112_000
    }
}
