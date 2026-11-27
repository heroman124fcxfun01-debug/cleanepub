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
 * Native crash hardening for Samsung SM-S928U1 (Android 17, arm64-v8a):
 * - Loads strictly via physical file path (`LlamaModel.load(ggufFile.absolutePath, config)`),
 *   NEVER via `initContextWithFd` / `ParcelFileDescriptor`.
 * - Disables `mmap` and `mlock` (`useMmap = false`, `useMlock = false`) to avoid SIGSEGV/SIGBUS
 *   page faults on Android 16/17 16KB-page kernels.
 * - Uses baseline `-march=armv8-a+fp+simd` (`GGML_NATIVE=OFF`) with lightweight `n_batch = 128`
 *   and `n_ctx = 1024` (automatic fallback to `512` if RAM is tight).
 * - Uses streaming generation (`LlamaModel.generateStream`) with `stopSequences` + immediate
 *   `cancelGeneration()` the instant `<|SPEECH_GENERATION_END|>` (or EOG marker) is emitted.
 */
class VieNeuGgufRunner(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext
    @Volatile
    private var loadedModel: LlamaModel? = null
    private var loadedModelPath: String? = null
    private var activeContextSize: Int = PREFERRED_CONTEXT_SIZE
    private var activeBatchSize: Int = DEFAULT_BATCH_SIZE
    private var activeThreads: Int = 4

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

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val candidateContexts = intArrayOf(PREFERRED_CONTEXT_SIZE, FALLBACK_CONTEXT_SIZE)
        val loadFailures = mutableListOf<String>()

        for (ctxSize in candidateContexts) {
            CrashReportStore.markNativeOperationStart(
                context = appContext,
                operation = "LlamaModel.load (${ggufFile.name})",
                detail = "path=${ggufFile.absolutePath}, size=${AiSystemDiagnosticReport.formatMb(ggufFile.length())}, " +
                    "n_ctx=$ctxSize, n_batch=$DEFAULT_BATCH_SIZE, threads=$threads, use_mmap=false, use_mlock=false"
            )
            try {
                val cfg = LlamaConfig(
                    contextSize = ctxSize,
                    batchSize = DEFAULT_BATCH_SIZE,
                    threads = threads,
                    threadsBatch = threads,
                    temperature = 0.8f,
                    topP = 0.95f,
                    topK = 50,
                    repeatPenalty = 1.1f,
                    frequencyPenalty = 0.0f,
                    presencePenalty = 0.0f,
                    maxTokens = 384,
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
                    activeBatchSize = DEFAULT_BATCH_SIZE
                    activeThreads = threads
                    statusSummary = "OK (${ggufFile.name} • ${AiSystemDiagnosticReport.formatMb(ggufFile.length())} • " +
                        "n_ctx=$ctxSize • n_batch=$DEFAULT_BATCH_SIZE • threads=$threads • mmap=off)"
                    Log.i(TAG, "Loaded GGUF backbone via file path: $statusSummary")
                    return true
                } else {
                    loadFailures.add("n_ctx=$ctxSize: LlamaModel.load hoàn tất nhưng isLoaded() == false")
                    runCatching { model.close() }
                }
            } catch (e: Throwable) {
                loadFailures.add("n_ctx=$ctxSize: ${OnnxModelAssetHelper.formatFullThrowable(e)}")
                Log.e(TAG, "LlamaModel.load failed at n_ctx=$ctxSize", e)
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

    @Synchronized
    fun generateSpeechCodes(
        refPhones: String,
        targetPhones: String,
        refCodes: IntArray,
        temperature: Double = 0.8,
        topK: Int = 50,
        maxNewTokens: Int = 600
    ): GgufGenerationResult {
        val model = loadedModel
        if (model == null || !isLoaded) {
            return GgufGenerationResult(
                errorMessage = lastLoadError ?: "Chưa nạp được model VieNeu-TTS GGUF."
            )
        }
        if (targetPhones.isBlank()) {
            return GgufGenerationResult(errorMessage = "Chuỗi âm vị mục tiêu trống.")
        }

        val combinedPhones = if (refPhones.isNotBlank()) {
            "$refPhones $targetPhones".trim()
        } else {
            targetPhones.trim()
        }

        // Guard against native wrapper's `safePromptLimit = min(n_ctx - maxTokens - 16, batchSize * 3)`:
        // Keep prompt well inside `batchSize * 3` (384 tokens) AND leave generous room for speech generation.
        val estTextTokens = (combinedPhones.length + 34).coerceAtLeast(48)
        val maxPromptBudget = (activeBatchSize * 3 - 14).coerceAtMost(activeContextSize - 160)
        val maxAllowedRefCodes = (maxPromptBudget - estTextTokens)
            .coerceIn(48, if (activeContextSize <= 512) 180 else MAX_REF_CODES)

        val cappedRefCodes = if (refCodes.size > maxAllowedRefCodes) {
            refCodes.copyOfRange(0, maxAllowedRefCodes)
        } else {
            refCodes
        }

        val refCodesStr = buildString(cappedRefCodes.size * 14) {
            for (code in cappedRefCodes) {
                append("<|speech_")
                append(code)
                append("|>")
            }
        }

        // Exact official pnnbao97/VieNeu-TTS & scripts/test_tts_gguf.py prompt format
        val prompt = "user: Convert the text to speech:<|TEXT_PROMPT_START|>$combinedPhones<|TEXT_PROMPT_END|>\n" +
            "assistant:<|SPEECH_GENERATION_START|>$refCodesStr"

        val estPromptTokens = estTextTokens + cappedRefCodes.size + 8
        val availableGenRoom = (activeContextSize - estPromptTokens - 24).coerceIn(96, 680)
        val safeMaxTokens = maxNewTokens.coerceIn(96, availableGenRoom)
        val startMs = System.currentTimeMillis()

        val genCfg = LlamaConfig(
            contextSize = activeContextSize,
            batchSize = activeBatchSize,
            threads = activeThreads,
            threadsBatch = activeThreads,
            temperature = temperature.toFloat().coerceIn(0.1f, 1.3f),
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
            detail = "refCodes=${cappedRefCodes.size}, targetIpaLen=${targetPhones.length}, " +
                "n_ctx=$activeContextSize, maxTokens=$safeMaxTokens, threads=$activeThreads"
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
                            // Check recent tail for <|SPEECH_GENERATION_END|> or EOG markers
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

            // Cut cleanly at the earliest stop marker if present
            var cutIdx = combinedRaw.length
            for (marker in STOP_MARKERS) {
                val idx = combinedRaw.indexOf(marker)
                if (idx in 0 until cutIdx) {
                    cutIdx = idx
                }
            }
            val cleanSegment = combinedRaw.substring(0, cutIdx)
            val codes = extractSpeechIds(cleanSegment)
            val promptPreview = "refPhones='${refPhones.take(80)}' | targetPhones='${targetPhones.take(100)}' | refCodes=${cappedRefCodes.size}"

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
        if (model != null) {
            runCatching { model.cancelGeneration() }
            runCatching { model.close() }
        }
        loadedModelPath = null
        statusSummary = "Đã giải phóng GGUF"
    }

    companion object {
        private const val TAG = "VieNeuGgufRunner"
        private const val PREFERRED_CONTEXT_SIZE = 1024
        private const val FALLBACK_CONTEXT_SIZE = 512
        private const val DEFAULT_BATCH_SIZE = 128
        private const val MAX_REF_CODES = 260
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
