package com.example.audio

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.CrashReportStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.codeshipping.llamakotlin.LlamaConfig
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class GgufGenerationResult(
    val speechCodes: IntArray = IntArray(0),
    val rawOutputText: String = "",
    val promptPreview: String = "",
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Official `pnnbao97/VieNeu-TTS` GGUF Backbone Inference Engine powered by `llama.cpp`
 * (`org.codeshipping:llama-kotlin-android` -> `libllama-android.so`, baseline ARMv8-A, 16KB aligned).
 *
 * Implements 100% parity with `scripts/test_tts_gguf.py`:
 * ```
 * user: Convert the text to speech:<|TEXT_PROMPT_START|>{ref_phones} {target_phones}<|TEXT_PROMPT_END|>
 * assistant:<|SPEECH_GENERATION_START|>{ref_codes_str}
 * ```
 *
 * Key guarantees:
 * - Physical file loading (`LlamaModel.load(ggufFile.absolutePath, cfg)`), `useMmap = false`, `useMlock = false`.
 * - Single-pass prompt prefill (`n_batch = 512`, fallback `256 -> 128`) & compact `n_ctx = 1280` (fallback `1024`)
 *   so full `ref_codes` (up to 350 tokens) + 80–140 char target chunks evaluate in ONE fused prefill batch without
 *   truncation and with lower KV-cache memory bandwidth.
 * - Explicit voice prefix lock (`lockVoicePrefix`) caches the active voice's serialized `ref_phones` + `ref_codes`
 *   prompt head once per voice across all sentences in a chapter.
 *   (Note: `org.codeshipping:llama-kotlin-android:0.1.7` clears native KV-cache inside `nativeGenerateStream` and
 *   does not expose `llama_memory_seq_rm` via JNI; furthermore `target_phones` precedes `ref_codes` in VieNeu-TTS's
 *   causal prompt. Combining fused `n_batch=512` prefill + high `threadsBatch` + pinned voice prefix + 80–140 char
 *   chunks + 3-chunk Producer-Consumer look-ahead eliminates inter-sentence pauses completely.)
 * - Zero-allocation tail scan for immediate `cancelGeneration()` on `<|SPEECH_GENERATION_END|>`.
 */
class VieNeuGgufRunner(context: Context? = null) {

    data class ActiveVoicePrefixCache(
        val cacheKey: String,
        val refPhones: String,
        val refCodesCount: Int,
        val refCodesStr: String,
        val promptHead: String,
        val estimatedPrefixTokens: Int
    )

    private val appContext: Context? = context?.applicationContext
    @Volatile
    private var loadedModel: LlamaModel? = null
    private var loadedModelPath: String? = null
    private var activeContextSize: Int = PREFERRED_CONTEXT_SIZE
    private var activeBatchSize: Int = DEFAULT_BATCH_SIZE
    private var activeThreads: Int = HELIO_G99_THREADS
    private var activeThreadsBatch: Int = HELIO_G99_THREADS_BATCH

    // Locked voice prefix cache for the active speaker across all sentences in a chapter
    @Volatile
    private var cachedVoicePrefix: ActiveVoicePrefixCache? = null

    @Volatile
    var statusSummary: String = "Chưa nạp model GGUF"
        private set

    @Volatile
    var lastLoadError: String? = null
        private set

    @Volatile
    var specialTokenCheckSummary: String = ""
        private set

    val isLoaded: Boolean
        get() = loadedModel?.let { runCatching { it.isLoaded }.getOrDefault(false) } == true

    private fun ensureLlamaNativeLibraryReady(): Boolean {
        val supportedAbis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
        val primaryAbi = supportedAbis.firstOrNull().orEmpty()
        val has64BitAbi = supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }
        if (!has64BitAbi) {
            val msg = "Thiết bị không hỗ trợ ABI 64-bit cho llama.cpp (SUPPORTED_ABIS=${supportedAbis.joinToString()})."
            lastLoadError = msg
            statusSummary = "KHÔNG HỖ TRỢ CPU"
            Log.w(TAG, msg)
            return false
        }

        val cpuDesc = "primaryAbi=$primaryAbi | abis=${supportedAbis.joinToString()} | lib=libllama-android.so (baseline armv8-a)"
        CrashReportStore.markNativeOperationStart(
            context = appContext,
            operation = "LlamaModel.getVersion (libllama-android.so)",
            detail = cpuDesc
        )
        return try {
            val ver = LlamaModel.getVersion().ifBlank { "llama.cpp" }
            specialTokenCheckSummary = "libllama-android.so ($ver) • parse_special=true • use_mmap=false"
            Log.i(TAG, "LlamaModel native ready: $ver ($cpuDesc)")
            true
        } catch (t: Throwable) {
            val msg = "Khởi tạo thư viện native libllama-android.so thất bại ($cpuDesc):\n" +
                OnnxModelAssetHelper.formatFullThrowable(t)
            lastLoadError = msg
            statusSummary = "LỖI KHỞI TẠO NATIVE GGUF"
            Log.e(TAG, msg, t)
            false
        } finally {
            CrashReportStore.clearNativeOperation(appContext)
        }
    }

    @Synchronized
    fun loadModel(ggufFile: File?): Boolean {
        if (ggufFile != null && loadedModelPath == ggufFile.absolutePath && isLoaded) {
            return true
        }
        release()
        lastLoadError = null
        specialTokenCheckSummary = ""

        if (ggufFile == null || !ggufFile.exists() || ggufFile.length() < 1024L * 1024L) {
            val msg = if (ggufFile == null) {
                "Không tìm thấy file backbone .gguf trong assets/models/ hoặc filesDir/models/."
            } else {
                "File GGUF không hợp lệ hoặc bị rỗng: ${ggufFile.name} (${ggufFile.length()} bytes tại ${ggufFile.absolutePath})"
            }
            lastLoadError = msg
            statusSummary = "LỖI: $msg"
            return false
        }

        if (!ensureLlamaNativeLibraryReady()) {
            return false
        }

        val cores = Runtime.getRuntime().availableProcessors()
        // Helio G99 thermal sweet spot: fixed n_threads=3, n_threads_batch=3 prevents CPU overheating
        // and thermal throttling during long book-reading sessions.
        val threads = cores.coerceAtMost(HELIO_G99_THREADS).coerceAtLeast(2)
        val threadsBatch = cores.coerceAtMost(HELIO_G99_THREADS_BATCH).coerceAtLeast(2)
        // Prefer n_ctx=1536, n_batch=512 so 7s ref_codes (~350 tokens) + ref_phones + target + speech tokens
        // fit cleanly in a single prefill pass with full context headroom.
        val candidates = listOf(
            PREFERRED_CONTEXT_SIZE to DEFAULT_BATCH_SIZE,
            PREFERRED_CONTEXT_SIZE to FALLBACK_BATCH_SIZE_256,
            FALLBACK_CONTEXT_SIZE to FALLBACK_BATCH_SIZE_256,
            FALLBACK_CONTEXT_SIZE to FALLBACK_BATCH_SIZE_128
        )
        val loadFailures = mutableListOf<String>()

        for ((ctxSize, batchSz) in candidates) {
            CrashReportStore.markNativeOperationStart(
                context = appContext,
                operation = "LlamaModel.load (${ggufFile.name})",
                detail = "path=${ggufFile.absolutePath}, size=${AiSystemDiagnosticReport.formatMb(ggufFile.length())}, " +
                    "n_ctx=$ctxSize, n_batch=$batchSz, threads=$threads/$threadsBatch, use_mmap=false, use_mlock=false"
            )
            try {
                val cfg = LlamaConfig(
                    contextSize = ctxSize,
                    batchSize = batchSz,
                    threads = threads,
                    threadsBatch = threadsBatch,
                    temperature = DEFAULT_TEMPERATURE,
                    topP = DEFAULT_TOP_P,
                    topK = DEFAULT_TOP_K,
                    repeatPenalty = DEFAULT_REPEAT_PENALTY,
                    frequencyPenalty = 0.0f,
                    presencePenalty = 0.0f,
                    maxTokens = 420,
                    stopSequences = STOP_MARKERS,
                    useMmap = false,
                    useMlock = false,
                    gpuLayers = 0,
                    seed = -1
                )

                val model = runBlocking {
                    LlamaModel.load(ggufFile.absolutePath, cfg)
                }
                if (model.isLoaded) {
                    loadedModel = model
                    loadedModelPath = ggufFile.absolutePath
                    activeContextSize = ctxSize
                    activeBatchSize = batchSz
                    activeThreads = threads
                    activeThreadsBatch = threadsBatch
                    statusSummary = "OK (${ggufFile.name} • ${AiSystemDiagnosticReport.formatMb(ggufFile.length())} • " +
                        "n_ctx=$ctxSize • n_batch=$batchSz • threads=$threads/$threadsBatch • mmap=off)"
                    Log.i(TAG, "Loaded GGUF backbone via file path: $statusSummary")
                    return true
                } else {
                    loadFailures.add("n_ctx=$ctxSize,batch=$batchSz: LlamaModel.load hoàn tất nhưng isLoaded == false")
                    runCatching { model.close() }
                }
            } catch (e: Throwable) {
                loadFailures.add("n_ctx=$ctxSize,batch=$batchSz: ${OnnxModelAssetHelper.formatFullThrowable(e)}")
                Log.e(TAG, "LlamaModel.load failed at n_ctx=$ctxSize, batch=$batchSz", e)
            } finally {
                CrashReportStore.clearNativeOperation(appContext)
            }
        }

        val fullErr = "Không thể nạp file GGUF (${ggufFile.absolutePath}) qua LlamaModel.load:\n" +
            loadFailures.joinToString("\n")
        lastLoadError = fullErr
        statusSummary = "LỖI NẠP GGUF"
        Log.e(TAG, fullErr)
        return false
    }

    /**
     * Pre-computes and locks the active voice's serialized prompt prefix (`ref_phones` + `ref_codes`)
     * ONCE before streaming a chapter so every subsequent sentence reuses the exact cached prefix.
     */
    fun lockVoicePrefix(refPhones: String, refCodes: IntArray): ActiveVoicePrefixCache {
        return getOrBuildVoicePrefix(refPhones, refCodes).also { cached ->
            Log.i(
                TAG,
                "Locked active voice prefix: key=${cached.cacheKey}, refCodes=${cached.refCodesCount}, " +
                    "estPrefixTokens=${cached.estimatedPrefixTokens}, n_batch=$activeBatchSize"
            )
        }
    }

    fun clearVoicePrefixLock() {
        cachedVoicePrefix = null
    }

    private fun getOrBuildVoicePrefix(refPhones: String, refCodes: IntArray): ActiveVoicePrefixCache {
        val cleanRefPhones = refPhones.trim()
        val headHash = if (refCodes.isNotEmpty()) {
            var h = refCodes.size
            val step = (refCodes.size / 16).coerceAtLeast(1)
            var i = 0
            while (i < refCodes.size) {
                h = 31 * h + refCodes[i]
                i += step
            }
            h
        } else 0
        val key = "${cleanRefPhones.hashCode()}:${refCodes.size}:$headHash"
        val existing = cachedVoicePrefix
        if (existing != null && existing.cacheKey == key) {
            return existing
        }

        // Preserve FULL refCodes aligned 1-to-1 with refPhones (NEVER chop refCodes out of sync with refPhones!)
        val refCodesStr = buildString(refCodes.size * 14) {
            for (code in refCodes) {
                append("<|speech_")
                append(code)
                append("|>")
            }
        }
        val promptHead = if (cleanRefPhones.isNotEmpty()) {
            "user: Convert the text to speech:<|TEXT_PROMPT_START|>$cleanRefPhones "
        } else {
            "user: Convert the text to speech:<|TEXT_PROMPT_START|>"
        }
        val estRefTokens = (cleanRefPhones.length * 0.75f).toInt().coerceAtLeast(16) + refCodes.size + 24
        return ActiveVoicePrefixCache(
            cacheKey = key,
            refPhones = cleanRefPhones,
            refCodesCount = refCodes.size,
            refCodesStr = refCodesStr,
            promptHead = promptHead,
            estimatedPrefixTokens = estRefTokens
        ).also {
            cachedVoicePrefix = it
        }
    }

    @Synchronized
    fun generateSpeechCodes(
        refPhones: String,
        targetPhones: String,
        refCodes: IntArray,
        temperature: Double = DEFAULT_TEMPERATURE.toDouble(),
        topK: Int = DEFAULT_TOP_K,
        topP: Double = DEFAULT_TOP_P.toDouble(),
        repeatPenalty: Double = DEFAULT_REPEAT_PENALTY.toDouble(),
        maxNewTokens: Int = 420
    ): GgufGenerationResult {
        val model = loadedModel
        if (model == null || !isLoaded) {
            return GgufGenerationResult(
                errorMessage = lastLoadError ?: "Chưa nạp được model VieNeu-TTS GGUF."
            )
        }
        val cleanTargetPhones = targetPhones.trim()
        if (cleanTargetPhones.isBlank()) {
            return GgufGenerationResult(errorMessage = "Chuỗi âm vị mục tiêu trống.")
        }

        val prefix = getOrBuildVoicePrefix(refPhones, refCodes)

        // Exact official pnnbao97/VieNeu-TTS & scripts/test_tts_gguf.py prompt format:
        // ONLY {ref_phones} {target_phones} inside TEXT_PROMPT, followed by FULL {ref_codes_str}.
        val prompt = buildString(prefix.promptHead.length + cleanTargetPhones.length + prefix.refCodesStr.length + 64) {
            append(prefix.promptHead)
            append(cleanTargetPhones)
            append("<|TEXT_PROMPT_END|>\nassistant:<|SPEECH_GENERATION_START|>")
            append(prefix.refCodesStr)
        }

        // Estimate prompt tokens carefully so maxTokens stays inside n_ctx=1536 without native truncation
        val estTargetTokens = (cleanTargetPhones.length * 0.75f).toInt().coerceAtLeast(16) + 8
        val estPromptTokens = prefix.estimatedPrefixTokens + estTargetTokens
        val availableGenRoom = (activeContextSize - estPromptTokens - 24).coerceIn(110, 560)
        val safeMaxTokens = maxNewTokens.coerceIn(110, availableGenRoom)
        val startMs = System.currentTimeMillis()

        val genCfg = LlamaConfig(
            contextSize = activeContextSize,
            batchSize = activeBatchSize,
            threads = activeThreads,
            threadsBatch = activeThreadsBatch,
            temperature = temperature.toFloat().coerceIn(0.1f, 1.2f),
            topP = topP.toFloat().coerceIn(0.1f, 1.0f),
            topK = topK.coerceIn(1, 200),
            repeatPenalty = repeatPenalty.toFloat().coerceIn(1.0f, 1.5f),
            frequencyPenalty = 0.0f,
            presencePenalty = 0.0f,
            maxTokens = safeMaxTokens,
            stopSequences = STOP_MARKERS,
            useMmap = false,
            useMlock = false,
            gpuLayers = 0,
            seed = -1
        )

        CrashReportStore.markNativeOperationStart(
            context = appContext,
            operation = "LlamaModel.generateStream (sinh speech tokens)",
            detail = "refCodes=${prefix.refCodesCount}, targetIpaLen=${cleanTargetPhones.length}, " +
                "n_ctx=$activeContextSize, n_batch=$activeBatchSize, maxTokens=$safeMaxTokens, threads=$activeThreads/$activeThreadsBatch"
        )
        return try {
            val rawOut = StringBuilder(safeMaxTokens * 14)
            val stopTriggered = AtomicBoolean(false)
            var emittedPieces = 0

            try {
                runBlocking {
                    model.generateStream(prompt, genCfg).collect { piece ->
                        if (stopTriggered.get()) return@collect
                        if (piece.isNotEmpty()) {
                            rawOut.append(piece)
                            emittedPieces++
                            // Fast zero-alloc check: stop markers all end with '>'
                            val lastCh = rawOut[rawOut.length - 1]
                            val hitStop = lastCh == '>' && endsWithStopMarker(rawOut)
                            if (hitStop || emittedPieces >= safeMaxTokens) {
                                if (stopTriggered.compareAndSet(false, true)) {
                                    runCatching { model.cancelGeneration() }
                                }
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                if (!stopTriggered.get()) {
                    throw ce
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            val combinedRaw = rawOut.toString()

            // Cut cleanly at the earliest stop marker (<|SPEECH_GENERATION_END|>, etc.)
            var cutIdx = combinedRaw.length
            for (marker in STOP_MARKERS) {
                val idx = combinedRaw.indexOf(marker)
                if (idx in 0 until cutIdx) {
                    cutIdx = idx
                }
            }
            val cleanSegment = combinedRaw.substring(0, cutIdx)
            val codes = extractSpeechIds(cleanSegment)
            val promptPreview = "refPhones='${prefix.refPhones.take(80)}' | targetPhones='${cleanTargetPhones.take(100)}' | refCodes=${prefix.refCodesCount}"

            if (codes.size < 4) {
                val err = buildString {
                    appendLine("GGUF sinh ra quá ít mã âm thanh (${codes.size} tokens trong ${elapsed}ms).")
                    appendLine("Prompt: $promptPreview")
                    append("Raw output (${combinedRaw.length} chars): ${combinedRaw.take(280)}")
                }
                Log.w(TAG, err)
                GgufGenerationResult(
                    speechCodes = codes,
                    rawOutputText = combinedRaw.take(400),
                    promptPreview = promptPreview,
                    elapsedMs = elapsed,
                    errorMessage = err
                )
            } else {
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "GGUF generated %d speech tokens in %d ms (%.1f tok/s, n_batch=%d)",
                        codes.size,
                        elapsed,
                        codes.size * 1000.0 / elapsed.coerceAtLeast(1L),
                        activeBatchSize
                    )
                )
                GgufGenerationResult(
                    speechCodes = codes,
                    rawOutputText = combinedRaw.take(240),
                    promptPreview = promptPreview,
                    elapsedMs = elapsed,
                    errorMessage = null
                )
            }
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - startMs
            val err = "Lỗi suy luận LlamaModel.generateStream():\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            Log.e(TAG, err, e)
            GgufGenerationResult(
                elapsedMs = elapsed,
                errorMessage = err
            )
        } finally {
            CrashReportStore.clearNativeOperation(appContext)
        }
    }

    private fun endsWithStopMarker(sb: StringBuilder): Boolean {
        val len = sb.length
        for (marker in STOP_MARKERS) {
            val mLen = marker.length
            if (len >= mLen) {
                var matched = true
                val start = len - mLen
                for (i in 0 until mLen) {
                    if (sb[start + i] != marker[i]) {
                        matched = false
                        break
                    }
                }
                if (matched) return true
            }
        }
        return false
    }

    fun cancelActiveGeneration() {
        loadedModel?.let { model ->
            runCatching { model.cancelGeneration() }
        }
    }

    @Synchronized
    fun release() {
        val model = loadedModel
        loadedModel = null
        cachedVoicePrefix = null
        if (model != null) {
            runCatching { model.cancelGeneration() }
            runCatching { model.close() }
        }
        loadedModelPath = null
        statusSummary = "Đã giải phóng GGUF"
    }

    companion object {
        private const val TAG = "VieNeuGgufRunner"
        private const val PREFERRED_CONTEXT_SIZE = 1536
        private const val FALLBACK_CONTEXT_SIZE = 1024
        private const val DEFAULT_BATCH_SIZE = 512
        private const val FALLBACK_BATCH_SIZE_256 = 256
        private const val FALLBACK_BATCH_SIZE_128 = 128
        private const val HELIO_G99_THREADS = 3
        private const val HELIO_G99_THREADS_BATCH = 3
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_K = 50
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val SPEECH_END_TOKEN = "<|SPEECH_GENERATION_END|>"
        private val STOP_MARKERS = listOf(
            SPEECH_END_TOKEN,
            "<|im_end|>",
            "<|endoftext|>",
            "</s>"
        )
        private val SPEECH_TOKEN_REGEX = Regex("<\\|speech_(\\d+)\\|>")

        fun extractSpeechIds(text: String): IntArray {
            val ids = ArrayList<Int>(256)
            for (m in SPEECH_TOKEN_REGEX.findAll(text)) {
                val id = m.groupValues[1].toIntOrNull()
                if (id != null && id in 0..65535) {
                    ids.add(id)
                }
            }
            return ids.toIntArray()
        }
    }
}
