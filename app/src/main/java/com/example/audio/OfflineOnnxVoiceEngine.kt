package com.example.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    val timbreSettings: VoiceTimbreSettings = VoiceTimbreSettings(pitchRatio = estimatedPitchRatio),
    val acousticSummary: String = "",
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
 * Strictly LAZY-LOADED on [Dispatchers.IO]:
 * - Constructing this class performs ZERO file I/O, ZERO `assets/models/` copying, and ZERO native
 *   library loading (`llama.cpp` / `onnxruntime`).
 * - Enforces exact 1-to-1 alignment between `ref_text` (`ref_phones`) and `ref_codes` so VieNeu-TTS
 *   NEVER speaks leftover sample/reference text between book sentences.
 * - Applies real 24kHz PCM DSP timbre shaping (WSOLA Pitch Shift + 3-band Biquad Formant/EQ) via
 *   [VoiceTimbreProcessor] so distinct cloned voices sound unmistakably different.
 */
class OfflineOnnxVoiceEngine(context: Context) {

    private val appContext = context.applicationContext

    private val ioMutex = Mutex()
    private val ggufRunnerLazy = lazy { VieNeuGgufRunner(appContext) }
    private val neuCodecLazy = lazy { NeuCodecOnnxEngine(appContext) }
    private val ggufRunner: VieNeuGgufRunner get() = ggufRunnerLazy.value
    private val neuCodec: NeuCodecOnnxEngine get() = neuCodecLazy.value

    // Cached ref_text -> eSpeak IPA string so reference text is phonemized once per voice
    private val refPhonesCache = ConcurrentHashMap<String, String>()

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
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (!forceResync && engineMode != OnnxVoiceEngineMode.UNINITIALIZED) {
                    return@withLock engineMode
                }

                try {
                    if (forceResync) {
                        if (ggufRunnerLazy.isInitialized()) ggufRunner.release()
                        if (neuCodecLazy.isInitialized()) neuCodec.closeSessions()
                        refPhonesCache.clear()
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
                    refPhonesCache.clear()

                    if (!discovered.hasAnyModel) {
                        lastInitError = "Chưa tìm thấy model VieNeu-TTS GGUF hoặc NeuCodec ONNX trong assets/models/."
                        engineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
                        return@withLock engineMode
                    }

                    // 1. Initialize NeuCodec ONNX Decoder (24kHz) ONLY — never load non-existent neucodec_encoder.onnx
                    neuCodec.initialize(
                        encoderFile = null,
                        decoderFile = discovered.codecDecoderFile
                    )
                    neuCodec.loadErrors.forEach { (role, err) -> initErrors[role] = err }

                    // 2. Initialize VieNeu-TTS GGUF Backbone via llama.cpp (safely wrapped in try-catch Throwable)
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
                        "Lazy init done on IO: mode=$engineMode | gguf=${ggufRunner.isLoaded} | " +
                            "dec=${neuCodec.hasDecoder} | g2p=${espeakG2p.entryCount} | presets=${presetVoices.size}"
                    )
                    engineMode
                } catch (t: Throwable) {
                    val fullErr = "Lỗi khởi tạo hệ thống AI Offline:\n${OnnxModelAssetHelper.formatFullThrowable(t)}"
                    initErrors["init_fatal"] = fullErr
                    lastInitError = fullErr
                    engineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
                    Log.e(TAG, fullErr, t)
                    engineMode
                }
            }
        }

    fun isFullOnnxSynthesisReady(): Boolean =
        engineMode == OnnxVoiceEngineMode.ONNX_READY &&
            ggufRunnerLazy.isInitialized() &&
            neuCodecLazy.isInitialized() &&
            ggufRunner.isLoaded &&
            neuCodec.hasDecoder

    fun cancelActiveGeneration() {
        if (ggufRunnerLazy.isInitialized()) {
            runCatching { ggufRunner.cancelActiveGeneration() }
        }
    }

    fun getPresetVoices(): List<VieNeuPresetVoice> = presetVoices

    fun findPresetVoiceById(presetId: String?): VieNeuPresetVoice? {
        if (presetId.isNullOrBlank()) return presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
        return presetVoices.firstOrNull { it.id.equals(presetId, ignoreCase = true) && it.referenceCodes.size >= 8 }
            ?: presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
    }

    /**
     * Checks whether a given `referenceCodes` array came from one of the official `voices.json` presets.
     * Used to guarantee 1-to-1 `ref_text` <-> `ref_codes` alignment even for previously saved cloned profiles
     * whose stored `referenceText` was a mismatched recording script.
     */
    private fun findPresetMatchingCodes(codes: IntArray?): VieNeuPresetVoice? {
        if (codes == null || codes.size < 8 || presetVoices.isEmpty()) return null
        val checkLen = minOf(codes.size, 12)
        for (preset in presetVoices) {
            val pCodes = preset.referenceCodes
            if (pCodes.size != codes.size) continue
            var same = true
            for (i in 0 until checkLen) {
                if (pCodes[i] != codes[i]) {
                    same = false
                    break
                }
            }
            if (same && pCodes.last() == codes.last()) {
                return preset
            }
        }
        return null
    }

    fun getStatusBadgeSummary(): String {
        val voiceCount = presetVoices.size
        return when {
            isFullOnnxSynthesisReady() -> "Model: OK | Voices: $voiceCount"
            engineMode == OnnxVoiceEngineMode.UNINITIALIZED -> "Model: Chờ nạp | Voices: $voiceCount"
            else -> "Model: Lỗi | Voices: $voiceCount"
        }
    }

    fun getReadinessDiagnostic(): String {
        val d = discoveredModels
        if (d == null || !d.hasAnyModel) {
            return lastInitError ?: "Chưa có bộ model VieNeu-TTS GGUF + NeuCodec trong assets/models/."
        }
        val missing = mutableListOf<String>()
        if (!ggufRunner.isLoaded) missing.add("chưa nạp được backbone .gguf (${ggufRunner.lastLoadError ?: "thiếu file .gguf"})")
        if (!neuCodec.hasDecoder) missing.add("chưa nạp được neucodec_decoder.onnx")
        return if (missing.isEmpty()) {
            "Sẵn sàng VieNeu-TTS GGUF + NeuCodec 24kHz (${presetVoices.size} giọng mẫu voices.json + DSP biến đổi âm sắc)"
        } else {
            missing.joinToString(" | ")
        }
    }

    /**
     * Analyzes the user's 16 kHz Mono audio sample across multiple acoustic dimensions:
     * - Median voiced F0 pitch (Hz)
     * - 3-band spectral energy ratios (Low/Mid/High), Spectral Centroid & Formant tilt
     * - Speaking rate (syllables/second)
     *
     * Maps accurately across ALL Vietnamese presets in `voices.json` (`Vinh`, `Binh`, `Tuyen`,
     * `Doan`, `Ly`, `Ngoc`, etc.) and computes real relative WSOLA Pitch + 3-band Formant/EQ parameters.
     */
    suspend fun extractSpeakerEmbedding(
        samples: FloatArray,
        sampleRate: Int
    ): SpeakerEmbeddingResult = withContext(Dispatchers.IO) {
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

        val acousticProfile = VoiceTimbreProcessor.analyzeVoice16k(
            samples = samples16k,
            sampleRate = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
        )
        val (matchedPreset, timbreSettings) = VoiceTimbreProcessor.matchBestPresetAndComputeTimbre(
            profile = acousticProfile,
            presets = presetVoices
        )
        val summary = String.format(
            Locale.US,
            "F0=%.0fHz | Centroid=%.0fHz | Tilt=%+.2f | Rate=%.1f syl/s -> %s (%s)",
            acousticProfile.f0Hz,
            acousticProfile.spectralCentroidHz,
            acousticProfile.spectralTilt,
            acousticProfile.speakingRateSylPerSec,
            matchedPreset?.displayLabel ?: "none",
            timbreSettings.describeShort()
        )
        Log.i(TAG, "Acoustic voice profiling: $summary")

        if (matchedPreset != null) {
            return@withContext SpeakerEmbeddingResult(
                embedding = matchedPreset.voiceEmbedding ?: acousticProfile.fingerprint32,
                usedOnnxEncoder = false,
                estimatedPitchRatio = timbreSettings.pitchRatio,
                referenceCodes = matchedPreset.referenceCodes,
                matchedPresetId = matchedPreset.id,
                matchedPresetRefText = matchedPreset.referenceText,
                timbreSettings = timbreSettings,
                acousticSummary = summary,
                errorMessage = null
            )
        }

        val loadErr = "Không tìm thấy giọng mẫu hợp lệ trong voices.json (filesDir/models/voices.json)."
        SpeakerEmbeddingResult(
            embedding = acousticProfile.fingerprint32,
            usedOnnxEncoder = false,
            estimatedPitchRatio = timbreSettings.pitchRatio,
            referenceCodes = null,
            timbreSettings = timbreSettings,
            acousticSummary = summary,
            errorMessage = loadErr
        )
    }

    /**
     * Synthesizes a single Vietnamese text chunk using official `pnnbao97/VieNeu-TTS` GGUF + NeuCodec 24kHz:
     * 1. Resolves strictly aligned `(ref_text, ref_codes)` from `voices.json` (never letting mismatched
     *    recording scripts leak into `ref_text` and cause the model to speak extra sample sentences!).
     * 2. Phonemizes `ref_text` (cached per voice) and `target_text` ONLY via eSpeak-NG Vietnamese IPA.
     * 3. Generates `<|speech_N|>` tokens with `VieNeuGgufRunner`, stopping immediately on `<|SPEECH_GENERATION_END|>`.
     * 4. Decodes to 24,000 Hz Mono Float32 PCM via `neucodec_decoder.onnx`.
     * 5. Applies real 24kHz PCM DSP Pitch Shifting + 3-band Formant/EQ shaping via [VoiceTimbreProcessor].
     */
    suspend fun synthesizeSentenceDetailed(
        sentence: String,
        speakerEmbedding: FloatArray = FloatArray(0),
        speed: Float = 1.0f,
        referenceCodes: IntArray? = null,
        referenceText: String? = null,
        matchedPresetId: String? = null,
        timbreSettings: VoiceTimbreSettings? = null
    ): OnnxSynthesisResult = withContext(Dispatchers.IO) {
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

        // STRICT 1-to-1 ALIGNMENT between ref_codes and ref_text:
        // If this voice is backed by a preset in voices.json (by matchedPresetId OR by exact referenceCodes match),
        // ALWAYS pair it with THAT preset's exact original referenceText so GGUF NEVER speaks leftover/mismatched text!
        val explicitPreset = matchedPresetId?.takeIf { it.isNotBlank() }?.let { findPresetVoiceById(it) }
        val inferredPreset = explicitPreset ?: findPresetMatchingCodes(referenceCodes)

        val resolvedRefCodes: IntArray
        val resolvedRefText: String
        val resolvedVoiceEmbedding: FloatArray?
        val voiceSourceLabel: String

        when {
            inferredPreset != null -> {
                resolvedRefCodes = inferredPreset.referenceCodes
                resolvedRefText = inferredPreset.referenceText
                resolvedVoiceEmbedding = inferredPreset.voiceEmbedding ?: speakerEmbedding.takeIf { it.isNotEmpty() }
                voiceSourceLabel = "Preset '${inferredPreset.displayLabel}' (${resolvedRefCodes.size} codes)"
            }
            referenceCodes != null && referenceCodes.size >= 8 -> {
                resolvedRefCodes = referenceCodes
                resolvedRefText = referenceText?.trim().orEmpty()
                resolvedVoiceEmbedding = speakerEmbedding.takeIf { it.isNotEmpty() }
                voiceSourceLabel = "Custom Encoded Voice (${resolvedRefCodes.size} codes)"
            }
            else -> {
                val fallbackPreset = presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
                if (fallbackPreset == null) {
                    return@withContext OnnxSynthesisResult(
                        errorMessage = "Không tìm thấy giọng mẫu hợp lệ trong voices.json."
                    )
                }
                resolvedRefCodes = fallbackPreset.referenceCodes
                resolvedRefText = fallbackPreset.referenceText
                resolvedVoiceEmbedding = fallbackPreset.voiceEmbedding ?: speakerEmbedding.takeIf { it.isNotEmpty() }
                voiceSourceLabel = "Default Preset '${fallbackPreset.displayLabel}' (${resolvedRefCodes.size} codes)"
            }
        }

        val refPhones = if (resolvedRefText.isNotBlank()) {
            refPhonesCache.getOrPut(resolvedRefText) { espeakG2p.phonemize(resolvedRefText) }
        } else {
            ""
        }
        val targetPhones = espeakG2p.phonemize(cleanTarget)
        if (targetPhones.isBlank()) {
            return@withContext OnnxSynthesisResult(
                errorMessage = "Bộ chuyển âm vị tiếng Việt không tạo được chuỗi IPA cho câu này."
            )
        }

        // Tight token budget proportional to target Vietnamese word count (~11-15 speech tokens/word @ 50Hz)
        // prevents runaway hallucination while leaving comfortable headroom for expressive pauses.
        val wordCount = VietnameseTtsTextNormalizer.countWords(cleanTarget).coerceAtLeast(1)
        val estMaxTokens = (wordCount * 20 + 90).coerceIn(150, 680)

        val genRes = ggufRunner.generateSpeechCodes(
            refPhones = refPhones,
            targetPhones = targetPhones,
            refCodes = resolvedRefCodes,
            temperature = 0.75,
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
            onSuccess = { rawWaveform ->
                val dspStart = System.currentTimeMillis()
                val shapedWaveform = VoiceTimbreProcessor.applyTimbreAndPitch(
                    samples = rawWaveform,
                    sampleRate = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE,
                    timbre = timbreSettings
                )
                val dspMs = System.currentTimeMillis() - dspStart
                val durSec = shapedWaveform.size.toFloat() / NeuCodecOnnxEngine.DECODER_SAMPLE_RATE.toFloat()
                val trace = buildString {
                    appendLine("Pipeline: Official pnnbao97/VieNeu-TTS GGUF + NeuCodec ONNX (24kHz)")
                    appendLine("Voice: $voiceSourceLabel")
                    if (timbreSettings != null && !timbreSettings.isIdentity) {
                        appendLine("DSP Timbre: ${timbreSettings.describeShort()} (${dspMs}ms)")
                    }
                    appendLine("RefText: ${resolvedRefText.take(120)}")
                    appendLine("TargetText: ${cleanTarget.take(140)}")
                    appendLine("GGUF: ${genRes.speechCodes.size} speech tokens trong ${genRes.elapsedMs} ms")
                    appendLine("NeuCodec Decode: ${shapedWaveform.size} samples @ 24kHz (${String.format(Locale.US, "%.2fs", durSec)}) trong $decodeMs ms")
                }
                OnnxSynthesisResult(
                    audio = SynthesizedSentenceAudio(
                        pcmFloat = shapedWaveform,
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
        withContext(Dispatchers.IO) {
            ensureInitialized(forceResync = forceResync)
            val (assetsFiles, totalAssetsBytes) = OnnxModelAssetHelper.inspectAssetsModelsFiles(appContext)
            val (filesDirPath, filesDirFiles, totalFilesDirBytes) =
                OnnxModelAssetHelper.inspectFilesDirModelsFiles(appContext)
            val d = discoveredModels

            val mapped = linkedMapOf(
                "pipeline" to "Official pnnbao97/VieNeu-TTS (GGUF + NeuCodec ONNX 24kHz + DSP WSOLA/EQ)",
                "gguf_backbone" to (d?.ggufBackboneFile?.name ?: "(không tìm thấy *.gguf)"),
                "neucodec_decoder" to (d?.codecDecoderFile?.name ?: "(không tìm thấy neucodec_decoder.onnx)"),
                "neucodec_encoder" to "Không sử dụng (phân tích đa chiều F0/Centroid/Rate -> khớp voices.json + DSP 24kHz)",
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

            val sampleIpa = espeakG2p.phonemize("Hôm nay trời trong xanh và mát mẻ.")
            val presetPreview = if (presetVoices.isEmpty()) "(trống)" else {
                presetVoices.joinToString(" | ") {
                    "${it.id}(${it.referenceCodes.size}c)"
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
    ): AiDiagnosticTestResult = withContext(Dispatchers.IO) {
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
            val modeLabel = if (ok) {
                "THÀNH CÔNG (Khớp '${res.matchedPresetId}' + ${res.timbreSettings.describeShort()})"
            } else {
                "THẤT BẠI"
            }
            val detail = buildString {
                appendLine("Trạng thái: $modeLabel")
                appendLine("Thời gian chạy: $elapsed ms")
                appendLine("Phân tích âm học: ${res.acousticSummary}")
                appendLine("Số mã FSQ NeuCodec sử dụng: ${res.referenceCodes?.size ?: 0} tokens (~50 tok/s)")
                res.matchedPresetId?.let { appendLine("Giọng mẫu voices.json tương đồng: $it") }
                if (!res.errorMessage.isNullOrBlank()) {
                    appendLine()
                    appendLine("CHI TIẾT LỖI GỐC (NGUYÊN VĂN):")
                    appendLine(res.errorMessage)
                }
            }
            AiDiagnosticTestResult(
                title = "Thử phân tích & khớp giọng mẫu (voices.json + DSP)",
                success = ok,
                elapsedMs = elapsed,
                detailLog = detail.trimEnd()
            )
        } catch (e: Throwable) {
            AiDiagnosticTestResult(
                title = "Thử phân tích & khớp giọng mẫu (voices.json + DSP)",
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
        referenceText: String? = null,
        matchedPresetId: String? = null,
        timbreSettings: VoiceTimbreSettings? = null
    ): Pair<AiDiagnosticTestResult, SynthesizedSentenceAudio?> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            ensureInitialized()
            val synth = synthesizeSentenceDetailed(
                sentence = sentence,
                speakerEmbedding = speakerEmbedding ?: FloatArray(0),
                speed = 1.0f,
                referenceCodes = referenceCodes,
                referenceText = referenceText,
                matchedPresetId = matchedPresetId,
                timbreSettings = timbreSettings
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
        refPhonesCache.clear()
        if (ggufRunnerLazy.isInitialized()) {
            runCatching { ggufRunner.cancelActiveGeneration() }
            runCatching { ggufRunner.release() }
        }
        if (neuCodecLazy.isInitialized()) {
            runCatching { neuCodec.closeSessions() }
        }
    }

    companion object {
        private const val TAG = "VieNeuGgufEngine"
        const val DEFAULT_TTS_OUTPUT_SAMPLE_RATE = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE
    }
}
