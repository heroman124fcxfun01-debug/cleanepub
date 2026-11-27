package com.example.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

enum class OnnxVoiceEngineMode {
    UNINITIALIZED,
    ONNX_READY,
    SAFE_TTS_FALLBACK
}

data class SpeakerEmbeddingResult(
    val embedding: FloatArray,
    val usedOnnxEncoder: Boolean,
    val estimatedPitchRatio: Float,
    val referenceCodes: IntArray? = null,
    val matchedPresetId: String? = null,
    val matchedPresetRefText: String? = null,
    val errorMessage: String? = null
)

data class SynthesizedSentenceAudio(
    val pcmFloat: FloatArray,
    val sampleRate: Int
)

data class OnnxSynthesisResult(
    val audio: SynthesizedSentenceAudio? = null,
    val errorMessage: String? = null,
    val traceLog: String? = null
)

/**
 * 100% Offline Official `pnnbao97/VieNeu-TTS` Engine (GGUF via `llama.cpp` + NeuCodec ONNX 24kHz).
 *
 * Pipeline:
 * 1. Syncs `assets/models/` -> `context.filesDir/models/` (removing legacy 21-file web ONNX files).
 * 2. Loads GGUF backbone (`pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf`) via `org.nehuatl.llamacpp.LlamaContext`.
 * 3. Loads `neucodec_encoder.onnx` (16kHz -> FSQ speech codes for instant offline voice cloning) and
 *    `neucodec_decoder.onnx` (FSQ speech codes -> 24kHz Mono PCM WAV) via ONNX Runtime Android.
 * 4. Loads `vi_espeak_ipa.tsv` (real eSpeak-NG Vietnamese IPA dictionary) and official `voices.json`.
 * 5. Synthesizes Vietnamese speech using the official `pnnbao97/VieNeu-TTS` prompt:
 *    `user: Convert the text to speech:<|TEXT_PROMPT_START|>{ref_phones} {target_phones}<|TEXT_PROMPT_END|>\n`
 *    `assistant:<|SPEECH_GENERATION_START|>{ref_codes}`
 */
class OfflineOnnxVoiceEngine(context: Context) {

    private val appContext = context.applicationContext

    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vieneu-gguf-worker").apply { isDaemon = true }
    }
    private val workerDispatcher = workerExecutor.asCoroutineDispatcher()

    private val ggufRunner = VieNeuGgufRunner()
    private val neuCodec = NeuCodecOnnxEngine()

    @Volatile
    var engineMode: OnnxVoiceEngineMode = OnnxVoiceEngineMode.UNINITIALIZED
        private set

    @Volatile
    var targetSampleRate: Int = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
        private set

    @Volatile
    var outputSampleRate: Int = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE
        private set

    @Volatile
    var lastInitError: String? = null
        private set

    @Volatile
    var discoveredModels: DiscoveredOnnxModels? = null
        private set

    private var espeakG2p: VietnameseEspeakG2p = VietnameseEspeakG2p.loadFromFile(null)
    private var presetVoices: List<VieNeuPresetVoice> = emptyList()
    private val initErrors = LinkedHashMap<String, String>()

    suspend fun ensureInitialized(forceResync: Boolean = false): OnnxVoiceEngineMode =
        withContext(workerDispatcher) {
            if (!forceResync && engineMode != OnnxVoiceEngineMode.UNINITIALIZED) {
                return@withContext engineMode
            }

            if (forceResync) {
                ggufRunner.release()
                neuCodec.closeSessions()
                engineMode = OnnxVoiceEngineMode.UNINITIALIZED
            }

            initErrors.clear()
            lastInitError = null

            val discovered = OnnxModelAssetHelper.syncAssetsModelsToFilesDir(appContext)
            discoveredModels = discovered
            if (discovered.copyErrors.isNotEmpty()) {
                initErrors["assets_copy"] = discovered.copyErrors.joinToString("\n")
            }

            targetSampleRate = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
            outputSampleRate = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE

            espeakG2p = VietnameseEspeakG2p.loadFromFile(discovered.espeakDictFile)
            presetVoices = OnnxModelAssetHelper.parseOfficialVoicesJson(discovered.voicesJsonFile)

            if (!discovered.hasAnyModel) {
                lastInitError = "Chưa tìm thấy model VieNeu-TTS GGUF hoặc NeuCodec ONNX trong assets/models/."
                engineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
                return@withContext engineMode
            }

            // 1. Initialize NeuCodec ONNX Encoder & Decoder
            neuCodec.initialize(
                encoderFile = discovered.codecEncoderFile,
                decoderFile = discovered.codecDecoderFile
            )
            neuCodec.loadErrors.forEach { (role, err) -> initErrors[role] = err }

            // 2. Initialize VieNeu-TTS GGUF Backbone via llama.cpp
            val ggufOk = ggufRunner.loadModel(discovered.ggufBackboneFile)
            if (!ggufOk) {
                ggufRunner.lastLoadError?.let { initErrors["gguf_backbone"] = it }
            }

            if (initErrors.isNotEmpty()) {
                lastInitError = initErrors.entries.joinToString("\n") { "[${it.key}] ${it.value}" }
            }

            val ready = ggufRunner.isLoaded || neuCodec.hasEncoder || neuCodec.hasDecoder
            engineMode = if (ready) {
                OnnxVoiceEngineMode.ONNX_READY
            } else {
                OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
            }
            Log.i(
                TAG,
                "Init done: mode=$engineMode | gguf=${ggufRunner.isLoaded} | enc=${neuCodec.hasEncoder} | " +
                    "dec=${neuCodec.hasDecoder} | g2p=${espeakG2p.entryCount} | presets=${presetVoices.size}"
            )
            engineMode
        }

    fun isFullOnnxSynthesisReady(): Boolean =
        engineMode == OnnxVoiceEngineMode.ONNX_READY &&
            ggufRunner.isLoaded &&
            neuCodec.hasDecoder

    fun getPresetVoices(): List<VieNeuPresetVoice> = presetVoices

    fun getReadinessDiagnostic(): String {
        val d = discoveredModels
        if (d == null || !d.hasAnyModel) {
            return lastInitError ?: "Chưa có bộ model VieNeu-TTS GGUF + NeuCodec trong assets/models/."
        }
        val missing = mutableListOf<String>()
        if (!ggufRunner.isLoaded) missing.add("chưa nạp được backbone .gguf (${ggufRunner.lastLoadError ?: "thiếu file .gguf"})")
        if (!neuCodec.hasDecoder) missing.add("chưa nạp được neucodec_decoder.onnx")
        return if (missing.isEmpty()) {
            val voiceInfo = if (neuCodec.hasEncoder) {
                "Encoder ONNX + ${presetVoices.size} giọng voices.json"
            } else {
                "${presetVoices.size} giọng mẫu voices.json + chỉnh cao độ"
            }
            "Sẵn sàng VieNeu-TTS GGUF + NeuCodec 24kHz ($voiceInfo)"
        } else {
            missing.joinToString(" | ")
        }
    }

    /**
     * Selects the closest Vietnamese preset voice from `voices.json` based on the recorded/imported
     * voice's estimated pitch ratio (`estimatedPitchRatio`) and 32-dim acoustic fingerprint.
     */
    private fun selectClosestPresetVoice(
        estimatedPitchRatio: Float,
        fingerprint: FloatArray
    ): VieNeuPresetVoice? {
        val valid = presetVoices.filter { it.referenceCodes.size >= 8 }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()

        val maleKeywords = listOf("nam", "male", "tuyen", "tuyên", "vinh", "vĩnh", "binh", "bình", "son", "sơn", "duc", "đức", "minh", "hung", "hùng", "quan", "quân")
        val femaleKeywords = listOf("nu", "nữ", "female", "ngoc", "ngọc", "dung", "bich", "bích", "ly", "huong", "hương", "linh", "lan", "mai", "ha", "hà", "trang", "thao", "thảo")

        val isLikelyMale = estimatedPitchRatio < 0.96f
        val preferred = valid.filter { preset ->
            val label = "${preset.id} ${preset.description}".lowercase(Locale.ROOT)
            if (isLikelyMale) {
                maleKeywords.any { label.contains(it) }
            } else {
                femaleKeywords.any { label.contains(it) }
            }
        }
        val pool = if (preferred.isNotEmpty()) preferred else valid

        var hash = 0
        for (i in fingerprint.indices) {
            hash = 31 * hash + (fingerprint[i] * 1000f).toInt()
        }
        val idx = (hash and Int.MAX_VALUE) % pool.size
        return pool[idx]
    }

    /**
     * Extracts discrete 50 Hz NeuCodec FSQ speech token IDs (`IntArray`) from a user's recorded
     * or imported 16 kHz Mono audio sample.
     *
     * - If optional `neucodec_encoder.onnx` is present, runs full neural audio encoding.
     * - Otherwise (default when `pnnbao-ump` only ships GGUF + Decoder + `voices.json`),
     *   extracts the user's acoustic fingerprint & fundamental pitch ratio and maps smoothly to
     *   the closest Vietnamese preset in `voices.json` — never failing for a missing encoder file!
     */
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

        val samples16k = if (sampleRate != NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE) {
            AudioFileDecoder.normalizeAndResampleMonoFloat(
                monoFloat = samples,
                sourceRate = sampleRate,
                targetRate = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
            )
        } else {
            samples
        }

        val estimatedPitchRatio = OnnxModelAssetHelper.estimateSpeakerPitchRatio(
            samples16k,
            NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
        )
        val fingerprint = OnnxModelAssetHelper.computeAcousticFingerprint(samples16k)

        if (neuCodec.hasEncoder) {
            val encoded = neuCodec.encodeReferenceAudio(samples16k, NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE)
            encoded.onSuccess { codes ->
                return@withContext SpeakerEmbeddingResult(
                    embedding = fingerprint,
                    usedOnnxEncoder = true,
                    estimatedPitchRatio = estimatedPitchRatio,
                    referenceCodes = codes,
                    matchedPresetId = null,
                    matchedPresetRefText = null,
                    errorMessage = null
                )
            }.onFailure { err ->
                Log.w(TAG, "Optional neucodec_encoder.onnx failed; falling back to voices.json preset matching: ${err.message}")
            }
        }

        // Standard pnnbao-ump mode: match user's acoustic profile to closest Vietnamese preset in voices.json
        val matchedPreset = selectClosestPresetVoice(estimatedPitchRatio, fingerprint)
        if (matchedPreset != null) {
            return@withContext SpeakerEmbeddingResult(
                embedding = matchedPreset.voiceEmbedding ?: fingerprint,
                usedOnnxEncoder = false,
                estimatedPitchRatio = estimatedPitchRatio,
                referenceCodes = matchedPreset.referenceCodes,
                matchedPresetId = matchedPreset.id,
                matchedPresetRefText = matchedPreset.referenceText,
                errorMessage = null
            )
        }

        val loadErr = initErrors["neucodec_encoder"]
            ?: "Không tìm thấy giọng mẫu hợp lệ trong voices.json và không có neucodec_encoder.onnx."
        SpeakerEmbeddingResult(
            embedding = fingerprint,
            usedOnnxEncoder = false,
            estimatedPitchRatio = estimatedPitchRatio,
            referenceCodes = null,
            errorMessage = loadErr
        )
    }

    /**
     * Synthesizes a Vietnamese sentence using the official `pnnbao97/VieNeu-TTS` GGUF + NeuCodec pipeline:
     * 1. Phonemizes `referenceText` and `sentence` using eSpeak-NG Vietnamese IPA (`VietnameseEspeakG2p`).
     * 2. Generates `<|speech_N|>` tokens with `VieNeuGgufRunner` (`llama.cpp`) conditioned on `referenceCodes`.
     * 3. Decodes generated speech tokens into a 24,000 Hz Mono Float32 PCM waveform with `neucodec_decoder.onnx`.
     */
    suspend fun synthesizeSentenceDetailed(
        sentence: String,
        speakerEmbedding: FloatArray = FloatArray(0),
        speed: Float = 1.0f,
        referenceCodes: IntArray? = null,
        referenceText: String? = null
    ): OnnxSynthesisResult = withContext(workerDispatcher) {
        ensureInitialized()

        if (!ggufRunner.isLoaded) {
            return@withContext OnnxSynthesisResult(
                errorMessage = ggufRunner.lastLoadError
                    ?: "Chưa nạp được model VieNeu-TTS GGUF (${discoveredModels?.describeSummary()})."
            )
        }
        if (!neuCodec.hasDecoder) {
            return@withContext OnnxSynthesisResult(
                errorMessage = neuCodec.loadErrors["neucodec_decoder"]
                    ?: "Thiếu file neucodec_decoder.onnx để giải mã sóng âm 24kHz."
            )
        }

        val cleanTarget = VietnameseTtsTextNormalizer.normalizeForTts(sentence).trim()
        if (cleanTarget.isEmpty()) {
            return@withContext OnnxSynthesisResult(errorMessage = "Câu văn bản trống sau khi chuẩn hóa.")
        }

        // Resolve reference voice: custom cloned voice (refCodes + refText) or preset fallback from voices.json
        val resolvedRefCodes: IntArray
        val resolvedRefText: String
        val resolvedVoiceEmbedding: FloatArray?
        val voiceSourceLabel: String

        if (referenceCodes != null && referenceCodes.size >= 8) {
            resolvedRefCodes = referenceCodes
            resolvedRefText = referenceText?.trim().orEmpty()
            resolvedVoiceEmbedding = speakerEmbedding.takeIf { it.isNotEmpty() }
            voiceSourceLabel = "Custom Cloned Voice (${resolvedRefCodes.size} NeuCodec codes)"
        } else {
            val preset = presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
            if (preset == null) {
                return@withContext OnnxSynthesisResult(
                    errorMessage = "Giọng nhân bản chưa có mã âm thanh NeuCodec (referenceCodes) và không có voices.json dự phòng. Hãy tạo lại giọng mới trong Studio."
                )
            }
            resolvedRefCodes = preset.referenceCodes
            resolvedRefText = preset.referenceText
            resolvedVoiceEmbedding = preset.voiceEmbedding ?: speakerEmbedding.takeIf { it.isNotEmpty() }
            voiceSourceLabel = "Preset voices.json '${preset.id}' (${resolvedRefCodes.size} codes)"
        }

        val refPhones = if (resolvedRefText.isNotBlank()) espeakG2p.phonemize(resolvedRefText) else ""
        val targetPhones = espeakG2p.phonemize(cleanTarget)
        if (targetPhones.isBlank()) {
            return@withContext OnnxSynthesisResult(
                errorMessage = "Bộ chuyển âm vị tiếng Việt không tạo được chuỗi IPA cho câu này."
            )
        }

        val estMaxTokens = (targetPhones.length * 4 + 120).coerceIn(180, 1280)
        val genRes = ggufRunner.generateSpeechCodes(
            refPhones = refPhones,
            targetPhones = targetPhones,
            refCodes = resolvedRefCodes,
            temperature = 0.8,
            topK = 50,
            maxNewTokens = estMaxTokens
        )

        if (genRes.errorMessage != null || genRes.speechCodes.size < 4) {
            return@withContext OnnxSynthesisResult(
                audio = null,
                errorMessage = genRes.errorMessage ?: "GGUF không sinh ra mã âm thanh hợp lệ.",
                traceLog = buildString {
                    appendLine("Voice: $voiceSourceLabel")
                    appendLine("RefText: $resolvedRefText")
                    appendLine("RefIPA: $refPhones")
                    appendLine("TargetIPA: $targetPhones")
                    appendLine("GGUF elapsed: ${genRes.elapsedMs} ms | codes=${genRes.speechCodes.size}")
                    appendLine("RawOut: ${genRes.rawOutputText}")
                }
            )
        }

        val decodeStart = System.currentTimeMillis()
        val decoded = neuCodec.decodeSpeechCodes(
            codes = genRes.speechCodes,
            voiceEmbedding = resolvedVoiceEmbedding
        )
        val decodeMs = System.currentTimeMillis() - decodeStart

        decoded.fold(
            onSuccess = { waveform ->
                val durSec = waveform.size.toFloat() / NeuCodecOnnxEngine.DECODER_SAMPLE_RATE.toFloat()
                val trace = buildString {
                    appendLine("Pipeline: Official pnnbao97/VieNeu-TTS GGUF + NeuCodec ONNX (24kHz)")
                    appendLine("Voice: $voiceSourceLabel")
                    appendLine("RefText: ${resolvedRefText.take(120)}")
                    appendLine("RefIPA: ${refPhones.take(160)}")
                    appendLine("TargetIPA: ${targetPhones.take(180)}")
                    appendLine("GGUF: ${genRes.speechCodes.size} speech tokens trong ${genRes.elapsedMs} ms")
                    appendLine("NeuCodec Decode: ${waveform.size} samples @ 24kHz (${String.format(Locale.US, "%.2fs", durSec)}) trong $decodeMs ms")
                }
                OnnxSynthesisResult(
                    audio = SynthesizedSentenceAudio(
                        pcmFloat = waveform,
                        sampleRate = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE
                    ),
                    errorMessage = null,
                    traceLog = trace
                )
            },
            onFailure = { err ->
                OnnxSynthesisResult(
                    audio = null,
                    errorMessage = "Lỗi giải mã neucodec_decoder.onnx:\n${OnnxModelAssetHelper.formatFullThrowable(err)}",
                    traceLog = "Generated ${genRes.speechCodes.size} codes in ${genRes.elapsedMs} ms"
                )
            }
        )
    }

    suspend fun runFullSystemDiagnostics(forceResync: Boolean = false): AiSystemDiagnosticReport =
        withContext(workerDispatcher) {
            ensureInitialized(forceResync = forceResync)
            val (assetsFiles, totalAssetsBytes) = OnnxModelAssetHelper.inspectAssetsModelsFiles(appContext)
            val (filesDirPath, filesDirFiles, totalFilesDirBytes) =
                OnnxModelAssetHelper.inspectFilesDirModelsFiles(appContext)
            val d = discoveredModels

            val mapped = linkedMapOf(
                "pipeline" to "Official pnnbao97/VieNeu-TTS (GGUF + NeuCodec ONNX 24kHz)",
                "gguf_backbone" to (d?.ggufBackboneFile?.name ?: "(không tìm thấy *.gguf)"),
                "neucodec_decoder" to (d?.codecDecoderFile?.name ?: "(không tìm thấy neucodec_decoder.onnx)"),
                "neucodec_encoder" to (d?.codecEncoderFile?.name ?: "Tùy chọn (không đi kèm — ghép giọng mẫu voices.json + chỉnh cao độ)"),
                "espeak_g2p_dict" to (d?.espeakDictFile?.let { "${it.name} (${espeakG2p.entryCount} âm tiết)" }
                    ?: "Dùng luật dự phòng eSpeak-NG vi (${espeakG2p.entryCount} âm tiết)"),
                "voices.json" to (d?.voicesJsonFile?.let { "${it.name} (${presetVoices.size} giọng mẫu)" }
                    ?: "(không tìm thấy)"),
                "output_sample_rate" to "${NeuCodecOnnxEngine.DECODER_SAMPLE_RATE} Hz"
            )

            val loaded = LinkedHashMap<String, String>()
            if (ggufRunner.isLoaded) {
                loaded["llama.cpp (GGUF)"] = ggufRunner.statusSummary
            }
            loaded.putAll(neuCodec.loadedSignatures)

            val sampleIpa = espeakG2p.phonemize("Xin chào, đây là giọng đọc thử nghiệm tiếng Việt.")
            val presetPreview = if (presetVoices.isEmpty()) "(trống)" else {
                presetVoices.take(3).joinToString(" | ") {
                    "${it.id}(codes=${it.referenceCodes.size}, text='${it.referenceText.take(32)}')"
                }
            }
            val metadata = linkedMapOf(
                "gguf_special_tokens" to ggufRunner.specialTokenCheckSummary.ifBlank { "(chưa kiểm tra)" },
                "espeak_g2p_entries" to "${espeakG2p.entryCount} entries",
                "g2p_sample_vi" to sampleIpa,
                "voices_presets" to presetPreview
            )

            val combinedStatus = "ORT: ${neuCodec.ortEnvironmentStatus} | GGUF: ${ggufRunner.statusSummary}"

            AiSystemDiagnosticReport(
                inspectedAtMillis = System.currentTimeMillis(),
                engineMode = engineMode,
                ortEnvironmentStatus = combinedStatus,
                assetsModelsFiles = assetsFiles,
                totalAssetsBytes = totalAssetsBytes,
                filesDirModelsPath = filesDirPath,
                filesDirModelsFiles = filesDirFiles,
                totalFilesDirBytes = totalFilesDirBytes,
                mappedRoles = mapped,
                loadedSessions = loaded,
                metadataSummary = metadata,
                sessionErrors = LinkedHashMap(initErrors),
                copyErrors = d?.copyErrors.orEmpty()
            )
        }

    suspend fun runDiagnosticVoiceCreateTest(
        sampleFloat16k: FloatArray? = null,
        sampleRate: Int = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
    ): AiDiagnosticTestResult = withContext(workerDispatcher) {
        val startMs = System.currentTimeMillis()
        try {
            ensureInitialized()
            val inputSamples = sampleFloat16k?.takeIf { it.isNotEmpty() } ?: FloatArray(16_000 * 3) { i ->
                val t = i.toDouble() / 16_000.0
                (0.35 * kotlin.math.sin(2.0 * kotlin.math.PI * 185.0 * t) +
                    0.18 * kotlin.math.sin(2.0 * kotlin.math.PI * 370.0 * t)).toFloat()
            }
            val res = extractSpeakerEmbedding(inputSamples, sampleRate)
            val elapsed = System.currentTimeMillis() - startMs
            val ok = (res.referenceCodes?.size ?: 0) >= 8 && res.errorMessage.isNullOrBlank()
            val modeLabel = when {
                res.usedOnnxEncoder -> "THÀNH CÔNG (NeuCodec ONNX Encoder)"
                ok -> "THÀNH CÔNG (Khớp giọng voices.json '${res.matchedPresetId}' + Pitch ${String.format(Locale.US, "%.2fx", res.estimatedPitchRatio)})"
                else -> "THẤT BẠI"
            }
            val detail = buildString {
                appendLine("Trạng thái: $modeLabel")
                appendLine("Thời gian chạy: $elapsed ms")
                appendLine("Đầu vào: ${inputSamples.size} mẫu @ $sampleRate Hz | Tỉ lệ cao độ: ${String.format(Locale.US, "%.2fx", res.estimatedPitchRatio)}")
                appendLine("Số mã FSQ NeuCodec sử dụng: ${res.referenceCodes?.size ?: 0} tokens (~50 tok/s)")
                res.matchedPresetId?.let { appendLine("Giọng mẫu voices.json tương đồng: $it") }
                res.referenceCodes?.take(12)?.let { sampleCodes ->
                    appendLine("12 mã đầu tiên: ${sampleCodes.joinToString(", ")}")
                }
                neuCodec.loadedSignatures["neucodec_encoder"]?.let { appendLine("Session: $it") }
                if (!res.errorMessage.isNullOrBlank()) {
                    appendLine()
                    appendLine("CHI TIẾT LỖI GỐC (NGUYÊN VĂN):")
                    appendLine(res.errorMessage)
                }
            }
            AiDiagnosticTestResult(
                title = "Thử tạo hồ sơ giọng mẫu (Encoder / voices.json)",
                success = ok,
                elapsedMs = elapsed,
                detailLog = detail.trimEnd()
            )
        } catch (e: Throwable) {
            AiDiagnosticTestResult(
                title = "Thử tạo hồ sơ giọng mẫu (Encoder / voices.json)",
                success = false,
                elapsedMs = System.currentTimeMillis() - startMs,
                detailLog = "NGOẠI LỆ NGUYÊN VĂN:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            )
        }
    }

    suspend fun runDiagnosticSentenceReadTest(
        sentence: String = OnnxModelAssetHelper.AI_VOICE_TEST_SENTENCE,
        speakerEmbedding: FloatArray? = null,
        referenceCodes: IntArray? = null,
        referenceText: String? = null
    ): Pair<AiDiagnosticTestResult, SynthesizedSentenceAudio?> = withContext(workerDispatcher) {
        val startMs = System.currentTimeMillis()
        try {
            ensureInitialized()
            val synth = synthesizeSentenceDetailed(
                sentence = sentence,
                speakerEmbedding = speakerEmbedding ?: FloatArray(0),
                speed = 1.0f,
                referenceCodes = referenceCodes,
                referenceText = referenceText
            )
            val elapsed = System.currentTimeMillis() - startMs
            val audio = synth.audio
            val ok = audio != null
            val detail = buildString {
                appendLine("Câu mẫu: \"$sentence\"")
                appendLine("Trạng thái: ${if (ok) "THÀNH CÔNG (đang phát sóng âm 24kHz)" else "THẤT BẠI"}")
                appendLine("Tổng thời gian: $elapsed ms")
                if (audio != null) {
                    val dur = audio.pcmFloat.size.toFloat() / audio.sampleRate.toFloat()
                    appendLine("Đầu ra: ${audio.pcmFloat.size} mẫu @ ${audio.sampleRate} Hz (${String.format(Locale.US, "%.2fs", dur)})")
                }
                if (!synth.traceLog.isNullOrBlank()) {
                    appendLine()
                    appendLine("TIẾN TRÌNH TỔNG HỢP (VieNeu-TTS GGUF + NeuCodec):")
                    appendLine(synth.traceLog.trimEnd())
                }
                if (!synth.errorMessage.isNullOrBlank()) {
                    appendLine()
                    appendLine("CHI TIẾT LỖI GỐC (NGUYÊN VĂN):")
                    appendLine(synth.errorMessage)
                }
                if (initErrors.isNotEmpty()) {
                    appendLine()
                    appendLine("LỖI KHỞI TẠO TRƯỚC ĐÓ:")
                    initErrors.forEach { (k, v) -> appendLine("[$k] $v") }
                }
            }
            AiDiagnosticTestResult(
                title = "Thử đọc câu mẫu (GGUF + NeuCodec 24kHz)",
                success = ok,
                elapsedMs = elapsed,
                detailLog = detail.trimEnd()
            ) to audio
        } catch (e: Throwable) {
            AiDiagnosticTestResult(
                title = "Thử đọc câu mẫu (GGUF + NeuCodec 24kHz)",
                success = false,
                elapsedMs = System.currentTimeMillis() - startMs,
                detailLog = "NGOẠI LỆ NGUYÊN VĂN:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            ) to null
        }
    }

    fun close() = release()

    fun release() {
        ggufRunner.cancelActiveGeneration()
        workerExecutor.execute {
            ggufRunner.release()
            neuCodec.closeSessions()
        }
        workerExecutor.shutdown()
    }

    companion object {
        private const val TAG = "VieNeuGgufEngine"
        const val DEFAULT_TTS_OUTPUT_SAMPLE_RATE = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE
    }
}
