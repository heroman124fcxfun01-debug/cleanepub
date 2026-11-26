package com.example.audio

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

enum class OnnxVoiceEngineMode {
    UNINITIALIZED,
    ONNX_READY,
    SAFE_TTS_FALLBACK
}

data class SpeakerEmbeddingResult(
    val embedding: FloatArray,
    val usedOnnxEncoder: Boolean,
    val estimatedPitchRatio: Float,
    val errorMessage: String? = null
)

data class SynthesizedSentenceAudio(
    val pcmFloat: FloatArray,
    val sampleRate: Int
)

data class OnnxSynthesisResult(
    val audio: SynthesizedSentenceAudio? = null,
    val errorMessage: String? = null
)

/**
 * 100% Offline ONNX Runtime VieNeu-TTS Voice Cloning & Speech Synthesis Engine.
 *
 * Key architecture guarantees:
 * 1. Recursively copies ALL files & subdirectories from `assets/models/` (including `.onnx`,
 *    `.onnx_data`, `.data`, `.bin`, `.json`) into `context.filesDir/models/` preserving structure.
 * 2. Initializes every [OrtSession] exclusively via absolute file path on `filesDir/models/...`
 *    (NEVER `InputStream.readBytes()`) so ONNX Runtime automatically resolves external `.onnx_data` weights.
 * 3. Runs `speaker_encoder.onnx` on normalized 16kHz Mono Float32 audio (supporting raw waveform or
 *    80-bin FFT log-mel filterbank inputs by inspecting `session.inputInfo`).
 * 4. Runs Vietnamese G2P (`sea_g2p.bin`) + `tokenizer.json` -> autoregressive backbone (`prefill` +
 *    `decode_step` or unified KV-cache / acoustic model) -> MANDATORY neural audio codec / vocoder
 *    decoder ONNX to reconstruct Mono 24,000 Hz (or codec native rate) `FloatArray` speech waveforms.
 * 5. Never swallows ONNX exceptions silently: preserves exact `e.message`, missing filenames, and
 *    tensor signatures for clear diagnostics in UI and logs.
 */
class OfflineOnnxVoiceEngine(context: Context) {

    private val appContext = context.applicationContext

    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "onnx-voice-worker").apply { isDaemon = true }
    }
    private val workerDispatcher = workerExecutor.asCoroutineDispatcher()

    @Volatile
    var engineMode: OnnxVoiceEngineMode = OnnxVoiceEngineMode.UNINITIALIZED
        private set

    @Volatile
    var targetSampleRate: Int = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
        private set

    @Volatile
    var outputSampleRate: Int = DEFAULT_TTS_OUTPUT_SAMPLE_RATE
        private set

    @Volatile
    var lastInitError: String? = null
        private set

    @Volatile
    var discoveredModels: DiscoveredOnnxModels? = null
        private set

    private var ortEnv: OrtEnvironment? = null
    private var speakerEncoderSession: OrtSession? = null
    private var prefillSession: OrtSession? = null
    private var decodeStepSession: OrtSession? = null
    private var backboneOrAcousticSession: OrtSession? = null
    private var codecDecoderSession: OrtSession? = null

    private var g2pLexicon: Map<String, IntArray> = emptyMap()
    private var tokenizerData: ParsedTokenizerData? = null
    private var presetVoices: Map<String, FloatArray> = emptyMap()
    private val sessionLoadErrors = LinkedHashMap<String, String>()

    /**
     * Copies `assets/models/` -> `context.filesDir/models/` recursively and loads all ONNX sessions
     * from absolute `filesDir` paths on the dedicated worker thread.
     */
    suspend fun ensureInitialized(): OnnxVoiceEngineMode = withContext(workerDispatcher) {
        if (engineMode != OnnxVoiceEngineMode.UNINITIALIZED) {
            return@withContext engineMode
        }

        sessionLoadErrors.clear()
        lastInitError = null

        val discovered = OnnxModelAssetHelper.syncAssetsModelsToFilesDir(appContext)
        discoveredModels = discovered
        if (discovered.copyErrors.isNotEmpty()) {
            sessionLoadErrors["assets_copy"] = discovered.copyErrors.joinToString("; ")
        }

        // Speaker encoder always consumes 16kHz Mono Float32; TTS output is 24kHz (or codec rate)
        targetSampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
        outputSampleRate = discovered.sampleRate

        // Parse supporting dictionaries/configs from filesDir/models/
        g2pLexicon = OnnxModelAssetHelper.parseSeaG2pBinary(discovered.g2pDictionaryFile)
        tokenizerData = OnnxModelAssetHelper.parseTokenizerAndConfig(
            discovered.tokenizerJsonFile,
            discovered.configJsonFile
        )
        tokenizerData?.outputSampleRate?.let { outputSampleRate = it }
        presetVoices = OnnxModelAssetHelper.parseVoicesJson(discovered.voicesJsonFile)

        if (!discovered.hasAnyOnnxModel) {
            lastInitError = "Chưa tìm thấy file .onnx nào trong assets/models/ hoặc ${discovered.modelsRootDir.absolutePath}."
            engineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
            return@withContext engineMode
        }

        val env = try {
            OrtEnvironment.getEnvironment()
        } catch (e: Throwable) {
            lastInitError = "Không khởi tạo được OrtEnvironment: ${e.message}"
            Log.e(TAG, lastInitError, e)
            engineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
            return@withContext engineMode
        }
        ortEnv = env

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        fun loadSessionByPath(role: String, file: File?): OrtSession? {
            if (file == null) return null
            if (!file.exists() || file.length() <= 64L) {
                sessionLoadErrors[role] = "Thiếu hoặc hỏng file ${file.name} (${file.length()} bytes)"
                return null
            }
            return try {
                // CRITICAL: Always pass absolute file path on filesDir so ONNX Runtime finds sibling .onnx_data!
                val session = env.createSession(file.absolutePath, opts)
                Log.i(TAG, "Loaded $role from ${file.absolutePath} | inputs=${formatInputSignatures(session)}")
                session
            } catch (e: Throwable) {
                val msg = "Lỗi nạp ${file.name}: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, msg, e)
                sessionLoadErrors[role] = msg
                null
            }
        }

        speakerEncoderSession = loadSessionByPath("speaker_encoder", discovered.speakerEncoderFile)
        prefillSession = loadSessionByPath("prefill", discovered.prefillModelFile)
        decodeStepSession = loadSessionByPath("decode_step", discovered.decodeStepModelFile)
        backboneOrAcousticSession = loadSessionByPath("backbone", discovered.backboneOrAcousticFile)
        codecDecoderSession = loadSessionByPath("codec_decoder", discovered.codecDecoderFile)

        if (sessionLoadErrors.isNotEmpty()) {
            lastInitError = sessionLoadErrors.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
        }

        val anyLoaded = speakerEncoderSession != null ||
            prefillSession != null ||
            backboneOrAcousticSession != null ||
            codecDecoderSession != null

        engineMode = if (anyLoaded) {
            OnnxVoiceEngineMode.ONNX_READY
        } else {
            OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
        }
        engineMode
    }

    fun isFullOnnxSynthesisReady(): Boolean {
        val hasGenerator = (prefillSession != null && decodeStepSession != null) ||
            backboneOrAcousticSession != null
        return engineMode == OnnxVoiceEngineMode.ONNX_READY && hasGenerator
    }

    fun getReadinessDiagnostic(): String {
        val d = discoveredModels
        if (d == null || !d.hasAnyOnnxModel) {
            return lastInitError ?: "Chưa có bộ model VieNeu-TTS (.onnx / .onnx_data) trong assets/models/."
        }
        if (sessionLoadErrors.isNotEmpty()) {
            return sessionLoadErrors.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
        }
        val missing = mutableListOf<String>()
        if (prefillSession == null && backboneOrAcousticSession == null) {
            missing.add("thiếu model backbone/generator (.onnx)")
        }
        if (prefillSession != null && decodeStepSession == null) {
            missing.add("thiếu model decode_step (.onnx)")
        }
        if ((prefillSession != null || backboneOrAcousticSession != null) && codecDecoderSession == null) {
            missing.add("cảnh báo: chưa có codec/vocoder decoder (.onnx)")
        }
        return if (missing.isEmpty()) {
            "Sẵn sàng (${d.describeSummary()})"
        } else {
            "${missing.joinToString(", ")} [${d.describeSummary()}]"
        }
    }

    // -------------------------------------------------------------------------
    // 1. Speaker Embedding Extraction (`speaker_encoder.onnx` on 16kHz Mono Float32)
    // -------------------------------------------------------------------------

    suspend fun extractSpeakerEmbedding(
        samples: FloatArray,
        sampleRate: Int
    ): SpeakerEmbeddingResult = withContext(workerDispatcher) {
        ensureInitialized()
        if (samples.isEmpty()) {
            return@withContext SpeakerEmbeddingResult(
                embedding = FloatArray(0),
                usedOnnxEncoder = false,
                estimatedPitchRatio = 1.0f,
                errorMessage = "Dữ liệu âm thanh mẫu bị rỗng."
            )
        }

        // Enforce exact 16,000 Hz Mono Float32 [-1.0f, 1.0f] for speaker_encoder.onnx
        val samples16k = if (sampleRate != AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE) {
            AudioFileDecoder.normalizeAndResampleMonoFloat(
                monoFloat = samples,
                sourceRate = sampleRate,
                targetRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
            )
        } else {
            samples
        }

        val estimatedPitchRatio = OnnxModelAssetHelper.estimateSpeakerPitchRatio(
            samples16k,
            AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
        )

        val encoder = speakerEncoderSession
        val env = ortEnv
        val encoderFile = discoveredModels?.speakerEncoderFile

        if (encoder != null && env != null) {
            try {
                val onnxEmbedding = runSpeakerEncoderSession(env, encoder, samples16k)
                if (onnxEmbedding.isEmpty() || onnxEmbedding.all { !it.isFinite() || abs(it) < 1e-9f }) {
                    throw IllegalStateException("speaker_encoder trả về vector rỗng hoặc toàn 0.")
                }
                val sanitized = FloatArray(onnxEmbedding.size) { i ->
                    if (onnxEmbedding[i].isFinite()) onnxEmbedding[i] else 0f
                }
                return@withContext SpeakerEmbeddingResult(
                    embedding = OnnxModelAssetHelper.l2Normalize(sanitized),
                    usedOnnxEncoder = true,
                    estimatedPitchRatio = estimatedPitchRatio,
                    errorMessage = null
                )
            } catch (e: Throwable) {
                val errMsg = "Lỗi chạy ${encoderFile?.name ?: "speaker_encoder.onnx"}: " +
                    "${e.message ?: e.javaClass.simpleName} | Inputs: ${formatInputSignatures(encoder)}"
                Log.e(TAG, errMsg, e)
                return@withContext SpeakerEmbeddingResult(
                    embedding = FloatArray(0),
                    usedOnnxEncoder = false,
                    estimatedPitchRatio = estimatedPitchRatio,
                    errorMessage = errMsg
                )
            }
        }

        // Check if speaker_encoder file existed on disk but failed to load into OrtSession
        val loadErr = sessionLoadErrors["speaker_encoder"]
        if (loadErr != null) {
            return@withContext SpeakerEmbeddingResult(
                embedding = FloatArray(0),
                usedOnnxEncoder = false,
                estimatedPitchRatio = estimatedPitchRatio,
                errorMessage = loadErr
            )
        }

        // No ONNX models bundled in APK (preview mode) -> safe acoustic voiceprint with clear note
        val acousticVector = OnnxModelAssetHelper.computeFallbackVoiceprintEmbedding(
            samples16k,
            AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
        )
        val note = if (discoveredModels?.hasAnyOnnxModel == true) {
            "Không tìm thấy file speaker_encoder.onnx trong ${discoveredModels?.modelsRootDir?.absolutePath} (${discoveredModels?.describeSummary()})"
        } else {
            null
        }
        SpeakerEmbeddingResult(
            embedding = acousticVector,
            usedOnnxEncoder = false,
            estimatedPitchRatio = estimatedPitchRatio,
            errorMessage = note
        )
    }

    private fun runSpeakerEncoderSession(
        env: OrtEnvironment,
        session: OrtSession,
        samples16k: FloatArray
    ): FloatArray {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            var computedFrames = 0
            for ((name, nodeInfo) in session.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val lower = name.lowercase(Locale.ROOT)
                val type = tInfo?.type ?: OnnxJavaType.FLOAT

                val tensor = when {
                    type == OnnxJavaType.INT64 || type == OnnxJavaType.INT32 || lower.contains("len") -> {
                        val lenVal = if (lower.contains("frame") || computedFrames > 0) {
                            computedFrames.coerceAtLeast(1).toLong()
                        } else {
                            samples16k.size.toLong()
                        }
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(lenVal.toInt())), longArrayOf(1L))
                        } else if (type == OnnxJavaType.FLOAT) {
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1.0f)), longArrayOf(1L))
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(lenVal)), longArrayOf(1L))
                        }
                    }

                    shape != null && shape.size == 4 -> {
                        val melBins = shape[3].toInt().takeIf { it in 40..128 }
                            ?: shape[2].toInt().takeIf { it in 40..128 }
                            ?: 80
                        val timeFirst = shape[3].toInt() == melBins || shape[2] <= 0L
                        val (mel, frames) = OnnxModelAssetHelper.computeLogMelSpectrogram(
                            samples16k = samples16k,
                            sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE,
                            melBins = melBins,
                            timeFirst = timeFirst
                        )
                        computedFrames = frames
                        val dims = if (timeFirst) {
                            longArrayOf(1L, 1L, frames.toLong(), melBins.toLong())
                        } else {
                            longArrayOf(1L, 1L, melBins.toLong(), frames.toLong())
                        }
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), dims)
                    }

                    shape != null && shape.size == 3 -> {
                        when {
                            // [1, 1, samples] -> raw 16kHz waveform (NeuCodec / WavLM / audio encoder)
                            shape[1] == 1L && (shape[2] <= 0L || shape[2] > 256L) -> {
                                OnnxTensor.createTensor(
                                    env,
                                    FloatBuffer.wrap(samples16k),
                                    longArrayOf(1L, 1L, samples16k.size.toLong())
                                )
                            }
                            // [1, melBins, frames] -> channels-first log-mel (CAM++ / WeSpeaker)
                            shape[1] in 40L..128L -> {
                                val melBins = shape[1].toInt()
                                val (mel, frames) = OnnxModelAssetHelper.computeLogMelSpectrogram(
                                    samples16k = samples16k,
                                    sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE,
                                    melBins = melBins,
                                    timeFirst = false
                                )
                                computedFrames = frames
                                OnnxTensor.createTensor(
                                    env,
                                    FloatBuffer.wrap(mel),
                                    longArrayOf(1L, melBins.toLong(), frames.toLong())
                                )
                            }
                            // [1, frames, melBins] -> time-first log-mel (ECAPA-TDNN / SpeechBrain / VieNeu)
                            else -> {
                                val melBins = shape[2].toInt().takeIf { it in 40..128 } ?: 80
                                val (mel, frames) = OnnxModelAssetHelper.computeLogMelSpectrogram(
                                    samples16k = samples16k,
                                    sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE,
                                    melBins = melBins,
                                    timeFirst = true
                                )
                                computedFrames = frames
                                OnnxTensor.createTensor(
                                    env,
                                    FloatBuffer.wrap(mel),
                                    longArrayOf(1L, frames.toLong(), melBins.toLong())
                                )
                            }
                        }
                    }

                    shape != null && shape.size == 2 -> {
                        // [1, samples] raw 16kHz waveform
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(samples16k),
                            longArrayOf(1L, samples16k.size.toLong())
                        )
                    }

                    else -> {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(samples16k),
                            longArrayOf(samples16k.size.toLong())
                        )
                    }
                }
                inputs[name] = tensor
            }

            session.run(inputs).use { result ->
                if (result.size() <= 0) {
                    throw IllegalStateException("speaker_encoder không trả về tensor đầu ra.")
                }
                return flattenAnyNumericOutputToFloat(result[0].value)
                    ?: throw IllegalStateException("Không đọc được giá trị số từ đầu ra speaker_encoder.")
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    // -------------------------------------------------------------------------
    // 2. Full TTS Synthesis Pipeline (G2P/Tokenizer -> Generator -> Codec Decoder)
    // -------------------------------------------------------------------------

    suspend fun synthesizeSentenceDetailed(
        sentence: String,
        speakerEmbedding: FloatArray,
        speed: Float
    ): OnnxSynthesisResult = withContext(workerDispatcher) {
        ensureInitialized()
        val env = ortEnv
        if (env == null) {
            return@withContext OnnxSynthesisResult(
                errorMessage = lastInitError ?: "ONNX Runtime chưa sẵn sàng (thiếu file model trong assets/models/)."
            )
        }
        val cleanText = VietnameseTtsTextNormalizer.normalizeForTts(sentence)
        if (cleanText.isBlank()) {
            return@withContext OnnxSynthesisResult(errorMessage = "Câu văn bản trống sau khi chuẩn hóa.")
        }

        val clampedSpeed = speed.coerceIn(0.8f, 1.5f)
        val inputTokenIds = encodeTextToTokenIds(cleanText)
        if (inputTokenIds.isEmpty()) {
            return@withContext OnnxSynthesisResult(errorMessage = "G2P/Tokenizer không tạo được chuỗi token cho câu này.")
        }

        val prefill = prefillSession
        val decodeStep = decodeStepSession
        val unified = backboneOrAcousticSession
        val codecDecoder = codecDecoderSession

        if (prefill == null && unified == null) {
            return@withContext OnnxSynthesisResult(
                errorMessage = "Thiếu model sinh giọng nói ONNX (backbone/prefill). Chi tiết: ${getReadinessDiagnostic()}"
            )
        }

        try {
            // Case A: Split Autoregressive VieNeu-TTS (`prefill` + `decode_step` -> MANDATORY `codecDecoder`)
            if (prefill != null && decodeStep != null) {
                if (codecDecoder == null) {
                    return@withContext OnnxSynthesisResult(
                        errorMessage = "Thiếu file codec/vocoder decoder ONNX để chuyển token âm thanh thành sóng PCM 24kHz. (${discoveredModels?.describeSummary()})"
                    )
                }
                val generatedSpeechCodes = runAutoregressivePrefillAndDecode(
                    env = env,
                    prefillSession = prefill,
                    decodeSession = decodeStep,
                    promptIds = inputTokenIds,
                    speakerEmbedding = speakerEmbedding,
                    maxSteps = (cleanText.length * 7).coerceIn(48, 480)
                )
                if (generatedSpeechCodes.isEmpty()) {
                    return@withContext OnnxSynthesisResult(
                        errorMessage = "Model autoregressive (${discoveredModels?.decodeStepModelFile?.name}) không sinh ra token âm thanh nào."
                    )
                }
                val waveform = runCodecOrVocoderDecoder(
                    env = env,
                    decoderSession = codecDecoder,
                    discreteCodes = generatedSpeechCodes,
                    melFeatures = null
                )
                return@withContext finalizeWaveformResult(waveform, outputSampleRate)
            }

            // Case B: Unified Backbone / Acoustic Generator (`backbone.onnx` / `model.onnx` / `acoustic.onnx`)
            val mainSession = unified ?: prefill!!
            val mainFileName = discoveredModels?.backboneOrAcousticFile?.name
                ?: discoveredModels?.prefillModelFile?.name
                ?: "model.onnx"

            // Detect whether unified model is an autoregressive KV-cache LM or single-pass acoustic/VITS model
            val hasPastKvInputs = mainSession.inputInfo.keys.any { it.lowercase(Locale.ROOT).contains("past") }
            if (hasPastKvInputs) {
                if (codecDecoder == null) {
                    return@withContext OnnxSynthesisResult(
                        errorMessage = "Model $mainFileName sinh token âm thanh nhưng thiếu file codec/vocoder decoder ONNX (neucodec/vocoder). (${discoveredModels?.describeSummary()})"
                    )
                }
                val generatedCodes = runUnifiedAutoregressiveKvModel(
                    env = env,
                    session = mainSession,
                    promptIds = inputTokenIds,
                    speakerEmbedding = speakerEmbedding,
                    maxSteps = (cleanText.length * 7).coerceIn(48, 480)
                )
                if (generatedCodes.isEmpty()) {
                    return@withContext OnnxSynthesisResult(
                        errorMessage = "Model $mainFileName không sinh được chuỗi token âm thanh hợp lệ."
                    )
                }
                val waveform = runCodecOrVocoderDecoder(
                    env = env,
                    decoderSession = codecDecoder,
                    discreteCodes = generatedCodes,
                    melFeatures = null
                )
                return@withContext finalizeWaveformResult(waveform, outputSampleRate)
            }

            // Case C: Single-pass Acoustic/VITS generator (+ mandatory codec/vocoder decoder if present)
            val (floatOut, longOut) = runSinglePassGenerator(
                env = env,
                session = mainSession,
                tokenIds = inputTokenIds,
                speakerEmbedding = speakerEmbedding,
                speed = clampedSpeed
            )

            val waveform = when {
                codecDecoder != null && longOut != null && longOut.isNotEmpty() -> {
                    runCodecOrVocoderDecoder(env, codecDecoder, discreteCodes = longOut, melFeatures = null)
                }
                codecDecoder != null && floatOut != null && floatOut.isNotEmpty() -> {
                    // Check if codec decoder expects INT64 codes (convert argmax/ids) or FLOAT mel spectrogram
                    val decFirstType = (codecDecoder.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.type
                    if (decFirstType == OnnxJavaType.INT64 || decFirstType == OnnxJavaType.INT32) {
                        val codes = LongArray(floatOut.size) { floatOut[it].toLong().coerceAtLeast(0L) }
                        runCodecOrVocoderDecoder(env, codecDecoder, discreteCodes = codes, melFeatures = null)
                    } else {
                        runCodecOrVocoderDecoder(env, codecDecoder, discreteCodes = null, melFeatures = floatOut)
                    }
                }
                floatOut != null && floatOut.size >= 512 -> floatOut
                else -> {
                    return@withContext OnnxSynthesisResult(
                        errorMessage = "Model $mainFileName trả về dữ liệu quá ngắn (${floatOut?.size ?: 0} mẫu float, ${longOut?.size ?: 0} token) và không có codec decoder phù hợp."
                    )
                }
            }

            finalizeWaveformResult(waveform, outputSampleRate)
        } catch (e: Throwable) {
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "không rõ nguyên nhân"}"
            Log.e(TAG, "ONNX synthesis failed: $detail", e)
            OnnxSynthesisResult(errorMessage = detail)
        }
    }

    suspend fun synthesizeSentence(
        sentence: String,
        speakerEmbedding: FloatArray,
        speed: Float
    ): SynthesizedSentenceAudio? {
        return synthesizeSentenceDetailed(sentence, speakerEmbedding, speed).audio
    }

    private fun finalizeWaveformResult(waveform: FloatArray, sampleRate: Int): OnnxSynthesisResult {
        if (waveform.size < 256) {
            return OnnxSynthesisResult(
                errorMessage = "Bộ giải mã âm thanh (codec/vocoder) trả về sóng âm quá ngắn (${waveform.size} mẫu)."
            )
        }
        val normalized = OnnxModelAssetHelper.normalizeOutputWaveform(waveform)
        val hasAudioEnergy = normalized.any { abs(it) > 1e-4f }
        if (!hasAudioEnergy) {
            return OnnxSynthesisResult(
                errorMessage = "Bộ giải mã âm thanh trả về sóng im lặng hoàn toàn (toàn số 0)."
            )
        }
        return OnnxSynthesisResult(
            audio = SynthesizedSentenceAudio(
                pcmFloat = normalized,
                sampleRate = sampleRate.coerceIn(16_000, 48_000)
            ),
            errorMessage = null
        )
    }

    // -------------------------------------------------------------------------
    // Autoregressive Prefill + Decode-Step & Unified KV-Cache Runners
    // -------------------------------------------------------------------------

    private fun runAutoregressivePrefillAndDecode(
        env: OrtEnvironment,
        prefillSession: OrtSession,
        decodeSession: OrtSession,
        promptIds: LongArray,
        speakerEmbedding: FloatArray,
        maxSteps: Int
    ): LongArray {
        val eosId = tokenizerData?.eosTokenId ?: 2L
        val speechEndId = tokenizerData?.speechEndTokenId
        val speechBase = tokenizerData?.speechTokenBaseId ?: 0L

        // 1. Run prefill
        val prefillInputs = buildLmStepInputs(
            env = env,
            session = prefillSession,
            tokenIds = promptIds,
            totalSeqLen = promptIds.size,
            speakerEmbedding = speakerEmbedding,
            pastKv = emptyMap()
        )

        val generatedCodes = ArrayList<Long>(maxSteps)
        var currentKv = LinkedHashMap<String, Any>()
        var nextToken: Long

        try {
            prefillSession.run(prefillInputs).use { prefillRes ->
                val logits = extractLastStepLogits(prefillRes)
                    ?: throw IllegalStateException("Prefill không trả về logits. Outputs: ${prefillSession.outputNames}")
                nextToken = argmaxToken(logits)
                currentKv = extractPresentKvTensorsData(prefillSession, prefillRes)
            }
        } finally {
            prefillInputs.values.forEach { runCatching { it.close() } }
        }

        // 2. Iterative decode steps
        var seqLen = promptIds.size
        var repeatCount = 0
        var lastToken = -1L

        for (step in 0 until maxSteps) {
            if (nextToken == eosId || (speechEndId != null && nextToken == speechEndId)) break
            if (nextToken == lastToken) {
                repeatCount++
                if (repeatCount >= 18) break
            } else {
                repeatCount = 0
                lastToken = nextToken
            }

            val codecToken = if (speechBase > 0L && nextToken >= speechBase) {
                nextToken - speechBase
            } else {
                nextToken.coerceAtLeast(0L)
            }
            generatedCodes.add(codecToken)

            seqLen += 1
            val stepInputs = buildLmStepInputs(
                env = env,
                session = decodeSession,
                tokenIds = longArrayOf(nextToken),
                totalSeqLen = seqLen,
                speakerEmbedding = speakerEmbedding,
                pastKv = currentKv
            )
            try {
                decodeSession.run(stepInputs).use { stepRes ->
                    val logits = extractLastStepLogits(stepRes) ?: break
                    nextToken = argmaxToken(logits)
                    currentKv = extractPresentKvTensorsData(decodeSession, stepRes)
                }
            } finally {
                stepInputs.values.forEach { runCatching { it.close() } }
            }
        }

        return LongArray(generatedCodes.size) { generatedCodes[it] }
    }

    private fun runUnifiedAutoregressiveKvModel(
        env: OrtEnvironment,
        session: OrtSession,
        promptIds: LongArray,
        speakerEmbedding: FloatArray,
        maxSteps: Int
    ): LongArray {
        // Unified merged KV-cache decoder uses the same session for prefill and decode steps
        return runAutoregressivePrefillAndDecode(
            env = env,
            prefillSession = session,
            decodeSession = session,
            promptIds = promptIds,
            speakerEmbedding = speakerEmbedding,
            maxSteps = maxSteps
        )
    }

    private data class CachedTensorPayload(
        val floats: FloatArray?,
        val fp16Shorts: ShortArray?,
        val shape: LongArray,
        val type: OnnxJavaType
    )

    private fun buildLmStepInputs(
        env: OrtEnvironment,
        session: OrtSession,
        tokenIds: LongArray,
        totalSeqLen: Int,
        speakerEmbedding: FloatArray,
        pastKv: Map<String, Any>
    ): LinkedHashMap<String, OnnxTensor> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        val curLen = tokenIds.size
        for ((name, nodeInfo) in session.inputInfo) {
            val lower = name.lowercase(Locale.ROOT)
            val tInfo = nodeInfo.info as? TensorInfo
            val shape = tInfo?.shape
            val type = tInfo?.type ?: OnnxJavaType.FLOAT

            val tensor = when {
                lower.contains("past") || lower.contains("cache") || lower.startsWith("k_") || lower.startsWith("v_") -> {
                    val matched = findMatchingPastKv(name, pastKv)
                    if (matched != null) {
                        createTensorFromPayload(env, matched, type)
                    } else {
                        // Zero-length KV cache for initial prefill step
                        val dims = shape?.mapIndexed { idx, d ->
                            when {
                                d > 0L -> d
                                idx == 0 -> 1L
                                idx == 2 || (shape.size == 4 && idx == 2) -> 0L
                                else -> 1L
                            }
                        }?.toLongArray() ?: longArrayOf(1L, 1L, 0L, 64L)
                        createEmptyTypedTensor(env, dims, type)
                    }
                }

                lower.contains("attention_mask") || lower == "mask" -> {
                    val mask = LongArray(totalSeqLen) { 1L }
                    if (type == OnnxJavaType.INT32) {
                        OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(totalSeqLen) { 1 }), longArrayOf(1L, totalSeqLen.toLong()))
                    } else if (type == OnnxJavaType.BOOL) {
                        OnnxTensor.createTensor(env, arrayOf(BooleanArray(totalSeqLen) { true }))
                    } else {
                        OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1L, totalSeqLen.toLong()))
                    }
                }

                lower.contains("position_ids") || lower == "pos" -> {
                    val startPos = (totalSeqLen - curLen).coerceAtLeast(0)
                    val pos = LongArray(curLen) { (startPos + it).toLong() }
                    OnnxTensor.createTensor(env, LongBuffer.wrap(pos), longArrayOf(1L, curLen.toLong()))
                }

                lower.contains("spk") || lower.contains("speaker") || lower.contains("embed") || lower.contains("voice") -> {
                    val expectedDim = shape?.lastOrNull()?.toInt()?.takeIf { it > 0 } ?: speakerEmbedding.size
                    val fitted = fitFloatVector(speakerEmbedding, expectedDim)
                    val dims = if (shape != null && shape.size == 3) {
                        longArrayOf(1L, 1L, fitted.size.toLong())
                    } else {
                        longArrayOf(1L, fitted.size.toLong())
                    }
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(fitted), dims)
                }

                lower.contains("use_cache") -> {
                    OnnxTensor.createTensor(env, booleanArrayOf(pastKv.isNotEmpty()))
                }

                lower.contains("input_ids") || lower.contains("token") || type == OnnxJavaType.INT64 -> {
                    val dims = if (shape != null && shape.size == 1) {
                        longArrayOf(curLen.toLong())
                    } else {
                        longArrayOf(1L, curLen.toLong())
                    }
                    OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), dims)
                }

                else -> {
                    val dims = shape?.map { if (it > 0L) it else 1L }?.toLongArray() ?: longArrayOf(1L)
                    createEmptyTypedTensor(env, dims, type)
                }
            }
            inputs[name] = tensor
        }
        return inputs
    }

    private fun extractPresentKvTensorsData(
        session: OrtSession,
        result: OrtSession.Result
    ): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        val outputNames = session.outputNames.toList()
        for (i in 0 until result.size()) {
            val outName = outputNames.getOrNull(i) ?: continue
            val lower = outName.lowercase(Locale.ROOT)
            if (lower.contains("present") || lower.contains("cache") || lower.contains("past") ||
                lower.startsWith("k_") || lower.startsWith("v_")
            ) {
                val onnxVal = result[i]
                if (onnxVal is OnnxTensor) {
                    val info = onnxVal.info
                    val shape = info.shape
                    val type = info.type
                    val payload = when (type) {
                        OnnxJavaType.FLOAT16 -> {
                            val sb = onnxVal.shortBuffer
                            val arr = ShortArray(sb.remaining())
                            sb.get(arr)
                            CachedTensorPayload(null, arr, shape, type)
                        }
                        else -> {
                            val fb = onnxVal.floatBuffer
                            val arr = FloatArray(fb.remaining())
                            fb.get(arr)
                            CachedTensorPayload(arr, null, shape, OnnxJavaType.FLOAT)
                        }
                    }
                    out[outName] = payload
                }
            }
        }
        return out
    }

    private fun findMatchingPastKv(inputName: String, pastKv: Map<String, Any>): CachedTensorPayload? {
        if (pastKv.isEmpty()) return null
        (pastKv[inputName] as? CachedTensorPayload)?.let { return it }
        val presentAlias = inputName
            .replace("past_key_values", "present")
            .replace("past_", "present_")
        (pastKv[presentAlias] as? CachedTensorPayload)?.let { return it }
        val digits = inputName.filter { it.isDigit() }
        val isKey = inputName.lowercase(Locale.ROOT).contains("key") || inputName.lowercase(Locale.ROOT).startsWith("k")
        val isVal = inputName.lowercase(Locale.ROOT).contains("val") || inputName.lowercase(Locale.ROOT).startsWith("v")
        for ((k, v) in pastKv) {
            if (v !is CachedTensorPayload) continue
            if (k.filter { it.isDigit() } == digits) {
                val kl = k.lowercase(Locale.ROOT)
                if ((isKey && (kl.contains("key") || kl.startsWith("k"))) ||
                    (isVal && (kl.contains("val") || kl.startsWith("v")))
                ) {
                    return v
                }
            }
        }
        return null
    }

    private fun createTensorFromPayload(
        env: OrtEnvironment,
        payload: CachedTensorPayload,
        expectedType: OnnxJavaType
    ): OnnxTensor {
        return if (expectedType == OnnxJavaType.FLOAT16 && payload.fp16Shorts != null) {
            val bb = ByteBuffer.allocateDirect(payload.fp16Shorts.size * 2).order(ByteOrder.nativeOrder())
            bb.asShortBuffer().put(payload.fp16Shorts)
            bb.rewind()
            OnnxTensor.createTensor(env, bb, payload.shape, OnnxJavaType.FLOAT16)
        } else {
            val floats = payload.floats ?: FloatArray(0)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), payload.shape)
        }
    }

    private fun createEmptyTypedTensor(env: OrtEnvironment, dims: LongArray, type: OnnxJavaType): OnnxTensor {
        val count = dims.fold(1L) { acc, d -> acc * d }.toInt().coerceAtLeast(0)
        return when (type) {
            OnnxJavaType.FLOAT16 -> {
                val bb = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
                OnnxTensor.createTensor(env, bb, dims, OnnxJavaType.FLOAT16)
            }
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(count)), dims)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(count)), dims)
            else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(count)), dims)
        }
    }

    // -------------------------------------------------------------------------
    // Single-Pass Generator & Mandatory Codec / Vocoder Decoder ONNX
    // -------------------------------------------------------------------------

    private fun runSinglePassGenerator(
        env: OrtEnvironment,
        session: OrtSession,
        tokenIds: LongArray,
        speakerEmbedding: FloatArray,
        speed: Float
    ): Pair<FloatArray?, LongArray?> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in session.inputInfo) {
                val lower = name.lowercase(Locale.ROOT)
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT

                val tensor = when {
                    lower.contains("len") -> {
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(tokenIds.size)), longArrayOf(1L))
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(tokenIds.size.toLong())), longArrayOf(1L))
                        }
                    }
                    lower.contains("spk") || lower.contains("speaker") || lower.contains("embed") || lower.contains("style") || lower.contains("voice") -> {
                        val expectedDim = shape?.lastOrNull()?.toInt()?.takeIf { it > 0 } ?: speakerEmbedding.size
                        val fitted = fitFloatVector(speakerEmbedding, expectedDim)
                        val dims = if (shape != null && shape.size == 3) {
                            longArrayOf(1L, 1L, fitted.size.toLong())
                        } else {
                            longArrayOf(1L, fitted.size.toLong())
                        }
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(fitted), dims)
                    }
                    lower.contains("scale") || lower.contains("speed") || lower.contains("alpha") -> {
                        val lengthScale = (1.0f / speed).coerceIn(0.65f, 1.35f)
                        val count = shape?.lastOrNull()?.toInt()?.takeIf { it in 1..4 } ?: 1
                        val vals = FloatArray(count) { idx ->
                            when (idx) {
                                0 -> if (count == 1) lengthScale else 0.667f
                                1 -> lengthScale
                                else -> 0.8f
                            }
                        }
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(vals), longArrayOf(count.toLong()))
                    }
                    type == OnnxJavaType.INT64 || lower.contains("input") || lower.contains("token") || lower.contains("phoneme") || lower.contains("text") -> {
                        val dims = if (shape != null && shape.size == 1) longArrayOf(tokenIds.size.toLong()) else longArrayOf(1L, tokenIds.size.toLong())
                        OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), dims)
                    }
                    type == OnnxJavaType.INT32 -> {
                        val ints = IntArray(tokenIds.size) { tokenIds[it].toInt() }
                        val dims = if (shape != null && shape.size == 1) longArrayOf(ints.size.toLong()) else longArrayOf(1L, ints.size.toLong())
                        OnnxTensor.createTensor(env, IntBuffer.wrap(ints), dims)
                    }
                    else -> {
                        val expectedDim = shape?.lastOrNull()?.toInt()?.takeIf { it > 0 } ?: speakerEmbedding.size
                        val fitted = fitFloatVector(speakerEmbedding, expectedDim)
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(fitted), longArrayOf(1L, fitted.size.toLong()))
                    }
                }
                inputs[name] = tensor
            }

            session.run(inputs).use { result ->
                if (result.size() <= 0) return null to null
                val first = result[0]
                val raw = first.value
                val floats = flattenAnyNumericOutputToFloat(raw)
                val longs = flattenIntOutputToLong(raw)
                return floats to longs
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Mandatory Codec / Vocoder Decoder step (NeuCodec / MOSS-Audio-Tokenizer / HiFi-GAN / Vocos):
     * Converts discrete speech tokens (`INT64` / `INT32`) or acoustic mel frames (`FLOAT`) into
     * a Mono `FloatArray` audio waveform ready for 16-bit PCM `AudioTrack` / WAV playback.
     */
    private fun runCodecOrVocoderDecoder(
        env: OrtEnvironment,
        decoderSession: OrtSession,
        discreteCodes: LongArray?,
        melFeatures: FloatArray?
    ): FloatArray {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in decoderSession.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.INT64
                val lower = name.lowercase(Locale.ROOT)

                val tensor = when {
                    lower.contains("len") && (type == OnnxJavaType.INT64 || type == OnnxJavaType.INT32) -> {
                        val count = discreteCodes?.size ?: ((melFeatures?.size ?: 80) / 80).coerceAtLeast(1)
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(count)), longArrayOf(1L))
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(count.toLong())), longArrayOf(1L))
                        }
                    }

                    type == OnnxJavaType.INT64 || type == OnnxJavaType.INT32 -> {
                        val codes = discreteCodes ?: throw IllegalStateException(
                            "Codec decoder yêu cầu input INT64 ($name) nhưng không có chuỗi token âm thanh."
                        )
                        val dims = when {
                            shape != null && shape.size == 3 -> {
                                val groups = shape[1].toInt().takeIf { it in 1..32 } ?: 1
                                val frames = (codes.size / groups).coerceAtLeast(1)
                                longArrayOf(1L, groups.toLong(), frames.toLong())
                            }
                            shape != null && shape.size == 2 -> longArrayOf(1L, codes.size.toLong())
                            else -> longArrayOf(codes.size.toLong())
                        }
                        val needed = dims.fold(1L) { acc, d -> acc * d }.toInt()
                        val fitted = if (codes.size == needed) codes else LongArray(needed) { codes[it % codes.size] }
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(needed) { fitted[it].toInt() }), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(fitted), dims)
                        }
                    }

                    else -> {
                        // FLOAT mel / latent input (e.g. [1, 80, T] or [1, T, 80])
                        val feats = melFeatures ?: discreteCodes?.let { FloatArray(it.size) { i -> it[i].toFloat() } }
                            ?: throw IllegalStateException("Codec/vocoder decoder thiếu dữ liệu đầu vào float ($name).")
                        val melBins = shape?.getOrNull(1)?.toInt()?.takeIf { it in 16..512 }
                            ?: shape?.getOrNull(2)?.toInt()?.takeIf { it in 16..512 }
                            ?: 80
                        val channelsFirst = shape?.getOrNull(1)?.toInt() == melBins || (shape?.size == 3 && shape[2] <= 0L)
                        val frames = (feats.size / melBins).coerceAtLeast(1)
                        val trimmed = if (frames * melBins == feats.size) feats else feats.copyOf(frames * melBins)
                        val dims = if (channelsFirst) {
                            longArrayOf(1L, melBins.toLong(), frames.toLong())
                        } else {
                            longArrayOf(1L, frames.toLong(), melBins.toLong())
                        }
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(trimmed), dims)
                    }
                }
                inputs[name] = tensor
            }

            decoderSession.run(inputs).use { result ->
                if (result.size() <= 0) {
                    throw IllegalStateException("Codec/vocoder decoder không trả về tensor sóng âm.")
                }
                val outVal = result[0]
                val outInfo = (outVal as? OnnxTensor)?.info
                val rawFloats = flattenAnyNumericOutputToFloat(outVal.value)
                    ?: throw IllegalStateException("Không đọc được sóng âm float từ codec decoder.")

                // If codec outputs stereo [1, 2, N] (e.g. MOSS-Audio-Tokenizer), mix down cleanly to Mono [N]
                val outShape = outInfo?.shape
                if (outShape != null && outShape.size == 3 && outShape[1] == 2L && rawFloats.size >= 2) {
                    val monoLen = rawFloats.size / 2
                    return FloatArray(monoLen) { i ->
                        (rawFloats[i] + rawFloats[monoLen + i]) * 0.5f
                    }
                }
                return rawFloats
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    // -------------------------------------------------------------------------
    // Vietnamese G2P + Tokenizer Encoding & Tensor Utilities
    // -------------------------------------------------------------------------

    private fun encodeTextToTokenIds(cleanText: String): LongArray {
        val tok = tokenizerData
        if (tok != null && tok.tokenToId.isNotEmpty()) {
            val ids = ArrayList<Long>(cleanText.length * 2 + 8)
            tok.bosTokenId?.let { ids.add(it) }
            val words = cleanText.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for ((idx, word) in words.withIndex()) {
                val prefix = if (idx > 0) "Ġ" else ""
                val direct = tok.tokenToId[prefix + word] ?: tok.tokenToId[word]
                if (direct != null) {
                    ids.add(direct)
                } else {
                    // Greedy longest-subword match in tokenizer.json vocab
                    var cursor = 0
                    val target = prefix + word
                    while (cursor < target.length) {
                        var matched: Long? = null
                        var matchLen = 1
                        val maxLook = minOf(target.length, cursor + 16)
                        for (end in maxLook downTo cursor + 1) {
                            val sub = target.substring(cursor, end)
                            val found = tok.tokenToId[sub]
                            if (found != null) {
                                matched = found
                                matchLen = end - cursor
                                break
                            }
                        }
                        if (matched != null) {
                            ids.add(matched)
                            cursor += matchLen
                        } else {
                            val chStr = target[cursor].toString()
                            ids.add(tok.tokenToId[chStr] ?: ((target[cursor].code % 192) + 4).toLong())
                            cursor++
                        }
                    }
                }
            }
            tok.speechStartTokenId?.let { ids.add(it) }
            return LongArray(ids.size) { ids[it] }
        }

        // G2P lexicon (`sea_g2p.bin`) fallback
        val tokens = ArrayList<Long>(cleanText.length * 2 + 4)
        tokens.add(1L) // BOS
        val words = cleanText.lowercase(Locale("vi", "VN")).split(Regex("\\s+"))
        for (rawWord in words) {
            if (rawWord.isEmpty()) continue
            val cleanWord = rawWord.trim { !it.isLetterOrDigit() }
            val dictIds = g2pLexicon[cleanWord]
            if (dictIds != null) {
                for (id in dictIds) tokens.add(id.toLong())
            } else {
                for (ch in rawWord) {
                    tokens.add(((ch.code % 192) + 4).toLong())
                }
            }
            tokens.add(3L)
        }
        tokens.add(2L) // EOS
        return LongArray(tokens.size) { tokens[it] }
    }

    private fun extractLastStepLogits(result: OrtSession.Result): FloatArray? {
        if (result.size() <= 0) return null
        val first = result[0]
        if (first is OnnxTensor) {
            val shape = first.info.shape
            val vocabSize = shape.lastOrNull()?.toInt()?.takeIf { it > 1 } ?: return null
            if (first.info.type == OnnxJavaType.FLOAT) {
                val fb = first.floatBuffer
                val total = fb.remaining()
                if (total < vocabSize) return null
                fb.position(total - vocabSize)
                val last = FloatArray(vocabSize)
                fb.get(last)
                return last
            }
        }
        val flat = flattenAnyNumericOutputToFloat(first.value) ?: return null
        return flat
    }

    private fun argmaxToken(logits: FloatArray): Long {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            val v = logits[i]
            if (v.isFinite() && v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        return bestIdx.toLong()
    }

    private fun fitFloatVector(vec: FloatArray, targetDim: Int): FloatArray {
        if (targetDim <= 0 || vec.size == targetDim) return vec
        return FloatArray(targetDim) { i -> if (i < vec.size) vec[i] else 0f }
    }

    private fun flattenAnyNumericOutputToFloat(raw: Any?): FloatArray? {
        val out = ArrayList<Float>()
        fun collect(obj: Any?) {
            when (obj) {
                is FloatArray -> for (v in obj) out.add(v)
                is DoubleArray -> for (v in obj) out.add(v.toFloat())
                is LongArray -> for (v in obj) out.add(v.toFloat())
                is IntArray -> for (v in obj) out.add(v.toFloat())
                is Array<*> -> for (item in obj) collect(item)
                is Float -> out.add(obj)
                is Double -> out.add(obj.toFloat())
                is Number -> out.add(obj.toFloat())
            }
        }
        collect(raw)
        if (out.isEmpty()) return null
        return FloatArray(out.size) { out[it] }
    }

    private fun flattenIntOutputToLong(raw: Any?): LongArray? {
        val out = ArrayList<Long>()
        fun collect(obj: Any?) {
            when (obj) {
                is LongArray -> for (v in obj) out.add(v)
                is IntArray -> for (v in obj) out.add(v.toLong())
                is Array<*> -> for (item in obj) collect(item)
                is Long -> out.add(obj)
                is Int -> out.add(obj.toLong())
            }
        }
        collect(raw)
        if (out.isEmpty()) return null
        return LongArray(out.size) { out[it] }
    }

    private fun formatInputSignatures(session: OrtSession): String {
        return session.inputInfo.entries.joinToString(", ") { (k, v) ->
            val ti = v.info as? TensorInfo
            val shapeStr = ti?.shape?.joinToString("x") ?: "?"
            "$k:${ti?.type ?: "?"}[$shapeStr]"
        }
    }

    fun close() {
        workerExecutor.execute {
            runCatching { speakerEncoderSession?.close() }
            runCatching { prefillSession?.close() }
            runCatching { decodeStepSession?.close() }
            runCatching { backboneOrAcousticSession?.close() }
            runCatching { codecDecoderSession?.close() }
            speakerEncoderSession = null
            prefillSession = null
            decodeStepSession = null
            backboneOrAcousticSession = null
            codecDecoderSession = null
        }
        workerExecutor.shutdown()
    }

    companion object {
        private const val TAG = "OfflineOnnxVoiceEngine"
        const val DEFAULT_TTS_OUTPUT_SAMPLE_RATE = 24_000
    }
}
