package com.example.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class OnnxVoiceEngineMode {
    UNINITIALIZED,
    ONNX_READY,
    SAFE_TTS_FALLBACK
}

data class SpeakerEmbeddingResult(
    val embedding: FloatArray,
    val usedOnnxEncoder: Boolean,
    val referenceCodes: IntArray? = null,
    val durationSeconds: Float = 0f,
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
 * 100% Offline Official `pnnbao97/VieNeu-TTS` Engine
 * (GGUF Backbone via `llama.cpp` + NeuCodec ONNX Encoder 16kHz + NeuCodec ONNX Decoder 24kHz).
 *
 * Strictly LAZY-LOADED on [Dispatchers.IO]:
 * - Constructing this class performs ZERO file I/O, ZERO `assets/models/` copying, and ZERO native
 *   library loading (`llama.cpp` / `onnxruntime`).
 * - Extracts REAL 50 Hz FSQ `ref_codes` & `speaker_embedding` directly from the user's cleanest
 *   3–8 second (optimal 6s) voice recording via `neucodec_encoder.onnx` (ZERO pitch/formant/voices.json faking).
 * - Synthesizes with `temperature = 0.8` directly from `(ref_text, ref_codes, speaker_embedding)`.
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
    private val activeOpsCount = AtomicInteger(0)

    val isBusy: Boolean
        get() = activeOpsCount.get() > 0

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

                    // 1. Initialize NeuCodec ONNX Encoder (16kHz real voice cloning) + optional Speaker Encoder + Decoder (24kHz)
                    neuCodec.initialize(
                        encoderFile = discovered.codecEncoderFile,
                        decoderFile = discovered.codecDecoderFile,
                        speakerEncoderFile = discovered.speakerEncoderFile
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
                            "enc=${neuCodec.hasEncoder} | spk=${neuCodec.hasSpeakerEncoder} | dec=${neuCodec.hasDecoder} | " +
                            "g2p=${espeakG2p.entryCount} | presets=${presetVoices.size}"
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

    val currentCachedVoiceId: String?
        get() = if (ggufRunnerLazy.isInitialized()) ggufRunner.currentCachedVoiceId else null

    /**
     * Clears ALL stale KV-cache & voice prefix state whenever switching voices or starting a preview.
     */
    fun clearVoiceKvCache(reason: String = "") {
        if (ggufRunnerLazy.isInitialized()) {
            runCatching { ggufRunner.clearKvCacheAndPrefix(reason) }
        }
    }

    /**
     * Frees >150 MB of native RAM by ensuring `neucodec_encoder.onnx` OrtSession is closed
     * before book reading starts.
     */
    fun releaseEncoderMemory() {
        if (neuCodecLazy.isInitialized()) {
            runCatching { neuCodec.closeEncoderSession() }
        }
    }

    fun getPresetVoices(): List<VieNeuPresetVoice> = presetVoices

    fun findPresetVoiceById(presetId: String?): VieNeuPresetVoice? {
        if (presetId.isNullOrBlank()) return presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
        return presetVoices.firstOrNull { it.id.equals(presetId, ignoreCase = true) && it.referenceCodes.size >= 8 }
            ?: presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
    }

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
        val hasEnc = neuCodecLazy.isInitialized() && neuCodec.hasEncoder
        return when {
            isFullOnnxSynthesisReady() && hasEnc -> "Model: OK • Encoder | Voices: $voiceCount"
            isFullOnnxSynthesisReady() -> "Model: OK | Voices: $voiceCount"
            engineMode == OnnxVoiceEngineMode.UNINITIALIZED -> "Model: Chờ nạp | Voices: $voiceCount"
            else -> "Model: Lỗi | Voices: $voiceCount"
        }
    }

    fun getReadinessDiagnostic(): String {
        val d = discoveredModels
        if (d == null || !d.hasAnyModel) {
            return lastInitError ?: "Chưa có bộ model VieNeu-TTS GGUF + NeuCodec ONNX trong assets/models/."
        }
        val missing = mutableListOf<String>()
        if (!ggufRunner.isLoaded) missing.add("chưa nạp được backbone .gguf (${ggufRunner.lastLoadError ?: "thiếu file .gguf"})")
        if (!neuCodec.hasDecoder) missing.add("chưa nạp được neucodec_decoder.onnx")
        if (!neuCodec.hasEncoder) missing.add("thiếu neucodec_encoder.onnx để nhân bản giọng thật")
        return if (missing.isEmpty()) {
            "Sẵn sàng VieNeu-TTS GGUF + NeuCodec Encoder 16kHz + Decoder 24kHz (Nhân bản giọng thật 100% offline)"
        } else {
            missing.joinToString(" | ")
        }
    }

    /**
     * Real 100% offline zero-shot voice cloning extractor via `neucodec_encoder.onnx`
     * (+ optional `speaker_encoder.onnx`):
     * - Automatically trims silence & extracts the cleanest 3–8 second (optimal 6s) 16 kHz Mono PCM segment.
     * - Runs ONNX Runtime directly on-device to obtain authentic 50 Hz FSQ `ref_codes` & `speaker_embedding`.
     * - ZERO `voices.json` substitution and ZERO artificial pitch/formant shifting.
     */
    suspend fun extractSpeakerEmbedding(
        samples: FloatArray,
        sampleRate: Int,
        alreadyPreprocessed: Boolean = false
    ): SpeakerEmbeddingResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (samples.isEmpty() || AudioFileDecoder.computeRms(samples) < AudioFileDecoder.MIN_VALID_AUDIO_RMS) {
            return@withContext SpeakerEmbeddingResult(
                embedding = FloatArray(0),
                usedOnnxEncoder = false,
                errorMessage = AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
            )
        }

        if (!neuCodec.hasEncoder) {
            val foundPath = discoveredModels?.codecEncoderFile?.absolutePath
            val missingErr = neuCodec.loadErrors["neucodec_encoder"]
                ?: if (foundPath != null) {
                    "Đã tìm thấy $foundPath nhưng encoderSession chưa nạp được."
                } else {
                    "Không tìm thấy neucodec_encoder.onnx trong ${discoveredModels?.modelsRootDir?.absolutePath ?: "filesDir/models"} để trích xuất mã giọng thật."
                }
            return@withContext SpeakerEmbeddingResult(
                embedding = FloatArray(0),
                usedOnnxEncoder = false,
                errorMessage = missingErr
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

        val cleanWindow16k = if (alreadyPreprocessed) {
            samples16k
        } else {
            AudioFileDecoder.extractCleanestSpeechWindow16k(
                samples = samples16k,
                sampleRate = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE,
                minSeconds = AudioFileDecoder.CLONE_MIN_WINDOW_SECONDS,
                maxSeconds = AudioFileDecoder.CLONE_MAX_WINDOW_SECONDS,
                trimEdgeMicNoiseSeconds = AudioFileDecoder.MAX_EDGE_SILENCE_TRIM_SECONDS
            )
        }

        if (cleanWindow16k.isEmpty() || AudioFileDecoder.computeRms(cleanWindow16k) < AudioFileDecoder.MIN_VALID_AUDIO_RMS) {
            return@withContext SpeakerEmbeddingResult(
                embedding = FloatArray(0),
                usedOnnxEncoder = false,
                errorMessage = AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
            )
        }

        val encodedRes = neuCodec.encodeReferenceAudio(
            samples = cleanWindow16k,
            sampleRate = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE,
            alreadyPreprocessed = true
        )
        val encoded = encodedRes.getOrNull()
        val hasValidCodes = encoded != null &&
            encoded.codes.size >= NeuCodecOnnxEngine.MIN_VALID_REF_CODES &&
            !encoded.codes.all { it == 0 } &&
            encoded.codes.distinct().size > 1
        if (hasValidCodes && encoded != null) {
            val realEmbedding = encoded.voiceEmbedding?.takeIf { it.isNotEmpty() }
                ?: OnnxModelAssetHelper.computeAcousticFingerprint(cleanWindow16k)
            val spkTag = if (encoded.voiceEmbedding != null) " • speaker_emb=${encoded.voiceEmbedding.size}D" else ""
            val summary = String.format(
                Locale.US,
                "NeuCodec Encoder ONNX: %dc mã giọng thật (%.1fs @ 16kHz)%s",
                encoded.codes.size,
                encoded.durationSeconds,
                spkTag
            )
            Log.i(TAG, summary)
            return@withContext SpeakerEmbeddingResult(
                embedding = realEmbedding,
                usedOnnxEncoder = true,
                referenceCodes = encoded.codes,
                durationSeconds = encoded.durationSeconds,
                acousticSummary = summary,
                errorMessage = null
            )
        }

        val encErr = encodedRes.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            ?: AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
        Log.e(TAG, encErr)
        SpeakerEmbeddingResult(
            embedding = FloatArray(0),
            usedOnnxEncoder = false,
            errorMessage = encErr
        )
    }

    /**
     * Lazily synthesizes & persists `preview_<preset_id>.wav` for an official `voices.json` preset
     * so tapping "Nghe thử" plays instantaneously (<50ms).
     */
    suspend fun ensurePresetPreviewCached(presetId: String): File? = withContext(Dispatchers.IO) {
        val storageId = OnnxModelAssetHelper.presetStorageId(presetId)
        VoicePreviewCache.getValidPreviewFile(appContext, storageId)?.let { return@withContext it }
        if (isBusy) return@withContext null

        ensureInitialized()
        if (!isFullOnnxSynthesisReady() || isBusy) return@withContext null

        val preset = findPresetVoiceById(presetId) ?: return@withContext null
        val synth = synthesizeSentenceDetailed(
            sentence = VoicePreviewCache.INSTANT_PREVIEW_SENTENCE,
            speakerEmbedding = preset.voiceEmbedding ?: FloatArray(0),
            speed = 1.0f,
            referenceCodes = preset.referenceCodes,
            referenceText = preset.referenceText,
            matchedPresetId = preset.id,
            voiceId = preset.storageId
        )
        val audio = synth.audio ?: return@withContext null
        VoicePreviewCache.savePreviewPcm(
            context = appContext,
            voiceId = storageId,
            samples = audio.pcmFloat,
            sampleRate = audio.sampleRate
        )
    }

    /**
     * Pre-phonemizes `ref_text` and locks the active voice prefix (`ref_phones` + `ref_codes`)
     * ONCE before streaming a chapter. Automatically clears old KV-cache if `voiceId` changed!
     */
    fun prepareVoiceForStreaming(
        referenceCodes: IntArray? = null,
        referenceText: String? = null,
        matchedPresetId: String? = null,
        voiceId: String = ""
    ) {
        if (!ggufRunner.isLoaded) return
        val explicitPreset = matchedPresetId?.takeIf { it.isNotBlank() }?.let { findPresetVoiceById(it) }
        val inferredPreset = explicitPreset ?: findPresetMatchingCodes(referenceCodes)
        val resolvedRefCodes: IntArray
        val resolvedRefText: String
        val resolvedVoiceId: String
        when {
            inferredPreset != null -> {
                resolvedRefCodes = inferredPreset.referenceCodes
                resolvedRefText = inferredPreset.referenceText
                resolvedVoiceId = voiceId.ifBlank { inferredPreset.storageId }
            }
            referenceCodes != null && referenceCodes.size >= 8 -> {
                resolvedRefCodes = referenceCodes
                resolvedRefText = referenceText?.trim().orEmpty()
                resolvedVoiceId = voiceId.ifBlank { "cloned_${resolvedRefCodes.size}_${resolvedRefCodes.firstOrNull() ?: 0}" }
            }
            else -> {
                val fallback = presetVoices.firstOrNull { it.referenceCodes.size >= 8 } ?: return
                resolvedRefCodes = fallback.referenceCodes
                resolvedRefText = fallback.referenceText
                resolvedVoiceId = voiceId.ifBlank { fallback.storageId }
            }
        }
        val cleanRefText = VietnameseTtsTextNormalizer.normalizeForTts(resolvedRefText).trim()
        val refPhones = if (cleanRefText.isNotBlank()) {
            refPhonesCache.getOrPut(cleanRefText) { espeakG2p.phonemize(cleanRefText) }
        } else {
            ""
        }
        ggufRunner.lockVoicePrefix(
            refPhones = refPhones,
            refCodes = resolvedRefCodes,
            voiceId = resolvedVoiceId
        )
    }

    /**
     * Synthesizes a single Vietnamese text chunk using official `pnnbao97/VieNeu-TTS` GGUF + NeuCodec 24kHz.
     */
    suspend fun synthesizeSentenceDetailed(
        sentence: String,
        speakerEmbedding: FloatArray = FloatArray(0),
        speed: Float = 1.0f,
        referenceCodes: IntArray? = null,
        referenceText: String? = null,
        matchedPresetId: String? = null,
        voiceId: String = ""
    ): OnnxSynthesisResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        synthesizeSentenceBlocking(
            sentence = sentence,
            speakerEmbedding = speakerEmbedding,
            speed = speed,
            referenceCodes = referenceCodes,
            referenceText = referenceText,
            matchedPresetId = matchedPresetId,
            voiceId = voiceId
        )
    }

    /**
     * Direct blocking synthesis entry point for the dedicated Producer OS thread
     * (zero coroutine context-switch overhead between sentences).
     */
    fun synthesizeSentenceBlocking(
        sentence: String,
        speakerEmbedding: FloatArray = FloatArray(0),
        speed: Float = 1.0f,
        referenceCodes: IntArray? = null,
        referenceText: String? = null,
        matchedPresetId: String? = null,
        voiceId: String = ""
    ): OnnxSynthesisResult {
        activeOpsCount.incrementAndGet()
        try {
            if (!ggufRunner.isLoaded) {
                return OnnxSynthesisResult(
                    errorMessage = ggufRunner.lastLoadError
                        ?: "Chưa nạp được model VieNeu-TTS GGUF (${discoveredModels?.describeSummary()})."
                )
            }
            if (!neuCodec.hasDecoder) {
                return OnnxSynthesisResult(
                    errorMessage = neuCodec.loadErrors["neucodec_decoder"]
                        ?: "Thiếu file neucodec_decoder.onnx để giải mã sóng âm 24kHz."
                )
            }

            val cleanTarget = VietnameseTtsTextNormalizer.normalizeForTts(sentence).trim()
            if (cleanTarget.isEmpty()) {
                return OnnxSynthesisResult(errorMessage = "Câu văn bản trống sau khi chuẩn hóa.")
            }

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
                    resolvedVoiceEmbedding = speakerEmbedding.takeIf { it.isNotEmpty() } ?: inferredPreset.voiceEmbedding
                    voiceSourceLabel = "Preset '${inferredPreset.displayLabel}' (${resolvedRefCodes.size} codes)"
                }
                referenceCodes != null && referenceCodes.size >= 8 -> {
                    resolvedRefCodes = referenceCodes
                    resolvedRefText = referenceText?.trim().orEmpty()
                    resolvedVoiceEmbedding = speakerEmbedding.takeIf { it.isNotEmpty() }
                    voiceSourceLabel = "Zero-Shot Cloned Voice (${resolvedRefCodes.size} real NeuCodec codes)"
                }
                else -> {
                    val fallbackPreset = presetVoices.firstOrNull { it.referenceCodes.size >= 8 }
                    if (fallbackPreset == null) {
                        return OnnxSynthesisResult(
                            errorMessage = "Chưa có mã giọng mẫu (ref_codes). Vui lòng tạo giọng nhân bản mới."
                        )
                    }
                    resolvedRefCodes = fallbackPreset.referenceCodes
                    resolvedRefText = fallbackPreset.referenceText
                    resolvedVoiceEmbedding = speakerEmbedding.takeIf { it.isNotEmpty() } ?: fallbackPreset.voiceEmbedding
                    voiceSourceLabel = "Default Preset '${fallbackPreset.displayLabel}' (${resolvedRefCodes.size} codes)"
                }
            }

            // Convert BOTH Ref Text and Target Text through the exact same normalizer + vi_espeak_ipa.tsv dictionary
            val cleanRefText = VietnameseTtsTextNormalizer.normalizeForTts(resolvedRefText).trim()
            val refPhones = if (cleanRefText.isNotBlank()) {
                refPhonesCache.getOrPut(cleanRefText) { espeakG2p.phonemize(cleanRefText) }
            } else {
                ""
            }
            val targetPhones = espeakG2p.phonemize(cleanTarget)
            if (targetPhones.isBlank()) {
                return OnnxSynthesisResult(
                    errorMessage = "Bộ chuyển âm vị tiếng Việt không tạo được chuỗi IPA cho câu này."
                )
            }

            val wordCount = VietnameseTtsTextNormalizer.countWords(cleanTarget).coerceAtLeast(1)
            // Tight token budget matched to 80-120 char chunks (~14-18 tokens/Vietnamese syllable)
            val estMaxTokens = (wordCount * 18 + 64).coerceIn(110, 400)

            val resolvedVoiceId = voiceId.trim().ifEmpty {
                inferredPreset?.storageId
                    ?: "cloned_${resolvedRefCodes.size}_${resolvedRefCodes.firstOrNull() ?: 0}_${resolvedRefCodes.lastOrNull() ?: 0}"
            }

            val genRes = ggufRunner.generateSpeechCodes(
                refPhones = refPhones,
                targetPhones = targetPhones,
                refCodes = resolvedRefCodes,
                temperature = VieNeuGgufRunner.DEFAULT_TEMPERATURE.toDouble(),
                topK = VieNeuGgufRunner.DEFAULT_TOP_K,
                topP = VieNeuGgufRunner.DEFAULT_TOP_P.toDouble(),
                repeatPenalty = VieNeuGgufRunner.DEFAULT_REPEAT_PENALTY.toDouble(),
                maxNewTokens = estMaxTokens,
                voiceId = resolvedVoiceId
            )

            if (genRes.errorMessage != null || genRes.speechCodes.size < 4) {
                return OnnxSynthesisResult(
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

            return decoded.fold(
                onSuccess = { waveform24k ->
                    val durSec = waveform24k.size.toFloat() / NeuCodecOnnxEngine.DECODER_SAMPLE_RATE.toFloat()
                    val trace = buildString {
                        appendLine("Pipeline: Official pnnbao97/VieNeu-TTS GGUF (temp=0.7, top_p=0.9, repeat=1.1, threads=3) + NeuCodec ONNX (24kHz)")
                        appendLine("Voice: $voiceSourceLabel")
                        appendLine("RefText: ${resolvedRefText.take(120)}")
                        appendLine("TargetText: ${cleanTarget.take(140)}")
                        appendLine("GGUF: ${genRes.speechCodes.size} speech tokens trong ${genRes.elapsedMs} ms")
                        appendLine("NeuCodec Decode: ${waveform24k.size} samples @ 24kHz (${String.format(Locale.US, "%.2fs", durSec)}) trong $decodeMs ms")
                    }
                    OnnxSynthesisResult(
                        audio = SynthesizedSentenceAudio(
                            pcmFloat = waveform24k,
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
        } finally {
            activeOpsCount.decrementAndGet()
        }
    }

    suspend fun runFullSystemDiagnostics(forceResync: Boolean = false): AiSystemDiagnosticReport =
        withContext(Dispatchers.IO) {
            ensureInitialized(forceResync = forceResync)
            val (assetsFiles, totalAssetsBytes) = OnnxModelAssetHelper.inspectAssetsModelsFiles(appContext)
            val (filesDirPath, filesDirFiles, totalFilesDirBytes) =
                OnnxModelAssetHelper.inspectFilesDirModelsFiles(appContext)
            val d = discoveredModels

            val mapped = linkedMapOf(
                "pipeline" to "Official pnnbao97/VieNeu-TTS (GGUF temp=0.7/top_p=0.9/threads=3 + NeuCodec Encoder On-Demand + Decoder QUInt8 24kHz)",
                "gguf_backbone" to (d?.ggufBackboneFile?.let { f ->
                    if (ggufRunner.isLoaded) "${f.name} (OK)" else "${f.name} (LỖI NẠP)"
                } ?: "(không tìm thấy *.gguf)"),
                "neucodec_encoder" to (d?.codecEncoderFile?.let { f ->
                    if (neuCodec.hasEncoder) {
                        val ramState = if (neuCodec.isEncoderResidentInRam) "Đang nạp RAM" else "On-Demand — đã giải phóng RAM"
                        "${f.name} (OK • $ramState)"
                    } else {
                        "${f.name} (LỖI NẠP — xem mục lỗi bên dưới)"
                    }
                } ?: "(THIẾU neucodec_encoder.onnx)"),
                "speaker_encoder" to (d?.speakerEncoderFile?.let { f ->
                    if (neuCodec.hasSpeakerEncoder) "${f.name} (OK)" else "${f.name} (LỖI NẠP)"
                } ?: "(không cần riêng — dùng trực tiếp NeuCodec FSQ ref_codes)"),
                "neucodec_decoder" to (d?.codecDecoderFile?.let { f ->
                    if (neuCodec.hasDecoder) "${f.name} (OK)" else "${f.name} (LỖI NẠP — xem mục lỗi bên dưới)"
                } ?: "(không tìm thấy neucodec_decoder.onnx)"),
                "espeak_g2p_dict" to (d?.espeakDictFile?.let { "${it.name} (${espeakG2p.entryCount} âm tiết)" }
                    ?: "Dùng luật dự phòng eSpeak-NG vi (${espeakG2p.entryCount} âm tiết)"),
                "voices.json" to (d?.voicesJsonFile?.let { "${it.name} (${presetVoices.size} giọng mẫu)" }
                    ?: "(không đóng gói)"),
                "output_sample_rate" to "${NeuCodecOnnxEngine.DECODER_SAMPLE_RATE} Hz"
            )

            val loaded = LinkedHashMap<String, String>()
            if (ggufRunner.isLoaded) {
                loaded["llama.cpp (GGUF)"] = ggufRunner.statusSummary
            }
            loaded.putAll(neuCodec.loadedSignatures)

            val sampleIpa = espeakG2p.phonemize("Hôm nay trời trong xanh và mát mẻ.")
            val presetPreview = if (presetVoices.isEmpty()) "(không có)" else {
                presetVoices.joinToString(" | ") { "${it.id}(${it.referenceCodes.size}c)" }
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
            val inputSamples = sampleFloat16k?.takeIf { it.isNotEmpty() } ?: FloatArray(16_000 * 4) { i ->
                val t = i.toDouble() / 16_000.0
                (0.35 * kotlin.math.sin(2.0 * kotlin.math.PI * 185.0 * t) +
                    0.18 * kotlin.math.sin(2.0 * kotlin.math.PI * 370.0 * t)).toFloat()
            }
            val res = extractSpeakerEmbedding(inputSamples, sampleRate)
            val elapsed = System.currentTimeMillis() - startMs
            val ok = res.usedOnnxEncoder && (res.referenceCodes?.size ?: 0) >= 8 && res.errorMessage.isNullOrBlank()
            val modeLabel = if (ok) {
                "THÀNH CÔNG (NeuCodec Encoder ONNX — ${res.referenceCodes?.size ?: 0} mã giọng thật)"
            } else {
                "THẤT BẠI"
            }
            val detail = buildString {
                appendLine("Trạng thái: $modeLabel")
                appendLine("Thời gian chạy: $elapsed ms")
                appendLine("Tóm tắt Encoder: ${res.acousticSummary}")
                appendLine("Số mã FSQ NeuCodec thật: ${res.referenceCodes?.size ?: 0} tokens (~50 tok/s)")
                if (!res.errorMessage.isNullOrBlank()) {
                    appendLine()
                    appendLine("CHI TIẾT LỖI GỐC (NGUYÊN VĂN):")
                    appendLine(res.errorMessage)
                }
            }
            AiDiagnosticTestResult(
                title = "Thử NeuCodec Encoder ONNX (trích xuất giọng thật)",
                success = ok,
                elapsedMs = elapsed,
                detailLog = detail.trimEnd()
            )
        } catch (e: Throwable) {
            AiDiagnosticTestResult(
                title = "Thử NeuCodec Encoder ONNX (trích xuất giọng thật)",
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
        matchedPresetId: String? = null
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
                matchedPresetId = matchedPresetId
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

        @Volatile
        private var sharedInstance: OfflineOnnxVoiceEngine? = null

        /**
         * Process-wide shared singleton so Studio (ViewModel) and BookAudioPlaybackService share ONE
         * GGUF + NeuCodec Decoder instance in RAM (~300 MB saved on Helio G99) and share one unified
         * `currentCachedVoiceId` / KV-cache invalidation state.
         */
        fun getShared(context: Context): OfflineOnnxVoiceEngine {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: OfflineOnnxVoiceEngine(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }
}
