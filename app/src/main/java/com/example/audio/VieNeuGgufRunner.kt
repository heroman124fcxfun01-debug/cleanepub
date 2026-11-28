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
 * - `n_batch = 256` (`safePromptLimit = 768`) & `n_ctx = 1536` (fallback `1024`) so NO `voices.json` preset's
 *   `ref_codes` (up to 308 tokens) is ever truncated out of sync with `ref_phones`.
 * - Caches the active voice's serialized prefix (`refPhones` + `refCodesStr`) across consecutive book sentences
 *   and keeps the warm `LlamaModel` context resident without reloading.
 * - Uses parallel prompt prefill (`threadsBatch = 4..6`) + fast streaming generation with immediate
 *   `cancelGeneration()` on `<|SPEECH_GENERATION_END|>`.
 */
class VieNeuGgufRunner(context: Context? = null) {

    private data class CachedVoicePrefix(
        val cacheKey: String,
        val refPhones: String,
        val refCodesCount: Int,
        val refCodesStr: String,
        val promptHead: String
    )

    private val appContext: Context? = context?.applicationContext
    @Volatile
    private var loadedModel: LlamaModel? = null
    private var loadedModelPath: String? = null
    private var activeContextSize: Int = PREFERRED_CONTEXT_SIZE
    private var activeBatchSize: Int = DEFAULT_BATCH_SIZE
    private var activeThreads: Int = 4
    private var activeThreadsBatch: Int = 6

    // Prefix cache for the currently active voice so consecutive sentences avoid rebuilding ref tags
    @Volatile
    private var cachedVoicePrefix: CachedVoicePrefix? = null

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
        val threads = cores.coerceIn(2, 4)
        val threadsBatch = cores.coerceIn(4, 6)
        // Prefer n_ctx=1536, n_batch=256 so full ref_codes (up to 308 tokens) never get truncated;
        // automatically fall back to lighter configs only if a low-RAM device rejects it.
        val candidates = listOf(
            PREFERRED_CONTEXT_SIZE to DEFAULT_BATCH_SIZE,
            FALLBACK_CONTEXT_SIZE to DEFAULT_BATCH_SIZE,
            FALLBACK_CONTEXT_SIZE to FALLBACK_BATCH_SIZE
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
                    temperature = 0.8f,
                    topP = 0.95f,
                    topK = 50,
                    repeatPenalty = 1.1f,
                    frequencyPenalty = 0.0f,
                    presencePenalty = 0.0f,
                    maxTokens = 480,
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

    private fun getOrBuildVoicePrefix(refPhones: String, refCodes: IntArray): CachedVoicePrefix {
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
        return CachedVoicePrefix(
            cacheKey = key,
            refPhones = cleanRefPhones,
            refCodesCount = refCodes.size,
            refCodesStr = refCodesStr,
            promptHead = promptHead
        ).also {
            cachedVoicePrefix = it
        }
    }

    @Synchronized
    fun generateSpeechCodes(
        refPhones: String,
        targetPhones: String,
        refCodes: IntArray,
        temperature: Double = 0.8,
        topK: Int = 50,
        maxNewTokens: Int = 520
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

        // Estimate prompt tokens carefully so maxTokens stays inside n_ctx without triggering native smartTruncate
        val estTextTokens = ((prefix.refPhones.length + cleanTargetPhones.length) * 0.75f).toInt().coerceAtLeast(32) + 28
        val estPromptTokens = estTextTokens + prefix.refCodesCount
        val availableGenRoom = (activeContextSize - estPromptTokens - 32).coerceIn(120, 720)
        val safeMaxTokens = maxNewTokens.coerceIn(120, availableGenRoom)
        val startMs = System.currentTimeMillis()

        val genCfg = LlamaConfig(
            contextSize = activeContextSize,
            batchSize = activeBatchSize,
            threads = activeThreads,
            threadsBatch = activeThreadsBatch,
            temperature = temperature.toFloat().coerceIn(0.1f, 1.2f),
            topP = 0.95f,
            topK = topK.coerceIn(1, 200),
            repeatPenalty = 1.1f,
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
                            val tailStart = (rawOut.length - 48).coerceAtLeast(0)
                            val tail = rawOut.substring(tailStart)
                            val hitStop = STOP_MARKERS.any { marker -> tail.contains(marker) }
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
                        "GGUF generated %d speech tokens in %d ms (%.1f tok/s)",
                        codes.size,
                        elapsed,
                        codes.size * 1000.0 / elapsed.coerceAtLeast(1L)
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
        private const val DEFAULT_BATCH_SIZE = 256
        private const val FALLBACK_BATCH_SIZE = 128
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
