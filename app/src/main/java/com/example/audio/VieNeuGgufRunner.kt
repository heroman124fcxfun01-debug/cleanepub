package com.example.audio

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.CrashReportStore
import org.nehuatl.llamacpp.LlamaContext
import java.io.File
import java.util.Locale

data class GgufGenerationResult(
    val speechCodes: IntArray = IntArray(0),
    val rawOutputText: String = "",
    val promptPreview: String = "",
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Official `pnnbao97/VieNeu-TTS` GGUF Backbone Inference Engine powered by `llama.cpp` (`LlamaContext`).
 *
 * Implements the exact NeuTTS / VieNeu-TTS prompt structure:
 * ```
 * user: Convert the text to speech:<|TEXT_PROMPT_START|>{ref_phones} {target_phones}<|TEXT_PROMPT_END|>
 * assistant:<|SPEECH_GENERATION_START|>{ref_codes_str}
 * ```
 * where `{ref_codes_str}` is the concatenation of `<|speech_N|>` tokens extracted from the reference
 * voice sample by NeuCodec (or loaded from `voices.json`).
 *
 * Native safety guarantees:
 * - NEVER pre-loads an arbitrary `librnllama_v8_2_dotprod_i8mm.so` blindly (which causes fatal `SIGILL`
 *   on ARM64 CPUs without `i8mm` or double-JNI library conflicts with `LlamaContext.<clinit>`).
 * - Matches `LlamaContext.<clinit>`'s exact primary ABI + `/proc/cpuinfo` library selection, verifies
 *   the `.so` exists, and triggers `Class.forName("org.nehuatl.llamacpp.LlamaContext")` inside
 *   `try-catch (Throwable)` with native crash breadcrumbs.
 * - Disables re-entrant JNI token callbacks (`emit_partial_completion = false`) and lets native `stop`
 *   tokens terminate generation cleanly in C++.
 */
class VieNeuGgufRunner(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext
    private var llamaContext: LlamaContext? = null
    private var activeModelPfd: ParcelFileDescriptor? = null
    private var loadedModelPath: String? = null

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
        get() = llamaContext != null

    /**
     * Resolves the EXACT single `librnllama*.so` name that `LlamaContext.<clinit>` will load based
     * on `Build.SUPPORTED_ABIS[0]` and `/proc/cpuinfo` `Features`.
     */
    private fun resolveExpectedNativeLibName(): Pair<String?, String> {
        val primaryAbi = runCatching { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }.getOrDefault("")
        if (primaryAbi == "x86_64") {
            return "rnllama_x86_64" to "ABI=x86_64"
        }
        if (primaryAbi != "arm64-v8a") {
            return null to "ABI chính '$primaryAbi' (SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.joinToString()}) không phải 64-bit arm64-v8a/x86_64"
        }
        val features = CrashReportStore.readCpuFeaturesLine()
        val hasDotProd = features.contains("dotprod") || features.contains("asimddp")
        val hasV82 = features.contains("asimd") && features.contains("crc32") && features.contains("aes")
        val hasI8mm = features.contains("i8mm")
        val libName = when {
            hasV82 && hasDotProd && hasI8mm -> "rnllama_v8_2_dotprod_i8mm"
            hasV82 && hasDotProd -> "rnllama_v8_2_dotprod"
            hasV82 && hasI8mm -> "rnllama_v8_2_i8mm"
            hasV82 -> "rnllama_v8_2"
            else -> "rnllama_v8"
        }
        return libName to "ABI=arm64-v8a | lib=lib$libName.so | v82=$hasV82 dotprod=$hasDotProd i8mm=$hasI8mm"
    }

    /**
     * Safely initializes `org.nehuatl.llamacpp.LlamaContext` (which loads the single matching
     * `librnllama*.so` for this CPU) inside `try-catch (Throwable)`.
     *
     * CRITICAL: Do NOT pre-call `System.loadLibrary("rnllama_v8_2_dotprod_i8mm")` before
     * `Class.forName` — loading a mismatched ISA variant causes immediate native `SIGILL` on
     * ARM64 devices without `i8mm`!
     */
    private fun ensureLlamaNativeLibraryReady(): Boolean {
        val (expectedLib, cpuDesc) = resolveExpectedNativeLibName()
        if (expectedLib == null) {
            val msg = "Thiết bị không hỗ trợ thư viện native llama.cpp 64-bit ($cpuDesc)."
            lastLoadError = msg
            statusSummary = "KHÔNG HỖ TRỢ CPU"
            Log.w(TAG, msg)
            return false
        }

        // Check whether the native library directory contains the expected .so (when extracted)
        val nativeDir = runCatching { appContext?.applicationInfo?.nativeLibraryDir }.getOrNull()
        if (!nativeDir.isNullOrBlank()) {
            val expectedSo = File(nativeDir, "lib$expectedLib.so")
            val dirEntries = runCatching { File(nativeDir).list()?.toList().orEmpty() }.getOrDefault(emptyList())
            if (dirEntries.isNotEmpty() && !expectedSo.exists()) {
                val msg = "Thiếu thư viện native lib$expectedLib.so trong $nativeDir ($cpuDesc).\n" +
                    "Các file .so hiện có: ${dirEntries.joinToString(", ")}"
                lastLoadError = msg
                statusSummary = "THIẾU THƯ VIỆN NATIVE GGUF"
                Log.e(TAG, msg)
                return false
            }
        }

        // Trigger LlamaContext.<clinit> once inside try-catch(Throwable) so ONLY the exact CPU-matched
        // library is loaded into the process.
        CrashReportStore.markNativeOperationStart(
            context = appContext,
            operation = "LlamaContext.<clinit> (nạp lib$expectedLib.so)",
            detail = cpuDesc
        )
        return try {
            Class.forName("org.nehuatl.llamacpp.LlamaContext", true, javaClass.classLoader)
            Log.i(TAG, "LlamaContext native library initialized cleanly ($cpuDesc)")
            true
        } catch (t: Throwable) {
            val msg = "Khởi tạo thư viện native llama.cpp (lib$expectedLib.so) thất bại ($cpuDesc):\n" +
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
                "File GGUF không hợp lệ hoặc quá nhỏ: ${ggufFile.name} (${ggufFile.length()} bytes)"
            }
            lastLoadError = msg
            statusSummary = "LỖI: $msg"
            return false
        }

        if (!ensureLlamaNativeLibraryReady()) {
            return false
        }

        var pfd: ParcelFileDescriptor? = null
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        CrashReportStore.markNativeOperationStart(
            context = appContext,
            operation = "LlamaContext.initContextWithFd (${ggufFile.name})",
            detail = "size=${AiSystemDiagnosticReport.formatMb(ggufFile.length())}, n_ctx=$DEFAULT_CONTEXT_SIZE, n_batch=$DEFAULT_BATCH_SIZE, threads=$threads"
        )
        return try {
            pfd = ParcelFileDescriptor.open(ggufFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val params = mapOf<String, Any>(
                "model" to ggufFile.absolutePath,
                "model_fd" to pfd.fd,
                "embedding" to false,
                "n_ctx" to DEFAULT_CONTEXT_SIZE,
                "n_batch" to DEFAULT_BATCH_SIZE,
                "n_threads" to threads,
                "n_gpu_layers" to 0,
                "use_mlock" to false,
                "use_mmap" to true,
                "vocab_only" to false
            )
            val ctx = LlamaContext(1, params)
            llamaContext = ctx
            // Keep ParcelFileDescriptor open for the lifetime of LlamaContext so mmap/fd never invalidates!
            activeModelPfd = pfd
            loadedModelPath = ggufFile.absolutePath

            // Verify special tokens are recognized by the GGUF tokenizer
            val probeTokens = runCatching {
                ctx.tokenize("<|TEXT_PROMPT_START|><|SPEECH_GENERATION_START|><|speech_0|><|SPEECH_GENERATION_END|>")
            }.getOrNull()
            specialTokenCheckSummary = if (probeTokens != null) {
                "probe4Specials -> ${probeTokens.size} tokens: ${probeTokens.take(8)}"
            } else {
                "probe failed"
            }

            val details = runCatching { ctx.modelDetails }.getOrNull()
            val desc = details?.get("desc")?.toString()?.takeIf { it.isNotBlank() } ?: ggufFile.name
            statusSummary = "OK ($desc • ${AiSystemDiagnosticReport.formatMb(ggufFile.length())} • n_ctx=$DEFAULT_CONTEXT_SIZE • threads=$threads)"
            Log.i(TAG, "Loaded GGUF backbone: $statusSummary | $specialTokenCheckSummary")
            true
        } catch (e: Throwable) {
            runCatching { pfd?.close() }
            val fullErr = "LlamaContext(${ggufFile.name}) thất bại:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            lastLoadError = fullErr
            statusSummary = "LỖI NẠP GGUF"
            Log.e(TAG, fullErr, e)
            false
        } finally {
            CrashReportStore.clearNativeOperation(appContext)
        }
    }

    @Synchronized
    fun generateSpeechCodes(
        refPhones: String,
        targetPhones: String,
        refCodes: IntArray,
        temperature: Double = 0.8,
        topK: Int = 50,
        maxNewTokens: Int = 900
    ): GgufGenerationResult {
        val ctx = llamaContext ?: return GgufGenerationResult(
            errorMessage = lastLoadError ?: "Chưa nạp được model VieNeu-TTS GGUF."
        )
        if (targetPhones.isBlank()) {
            return GgufGenerationResult(errorMessage = "Chuỗi âm vị mục tiêu trống.")
        }

        // Cap reference codes to leave ample room inside n_ctx=1536
        val cappedRefCodes = if (refCodes.size > MAX_REF_CODES) {
            refCodes.copyOfRange(0, MAX_REF_CODES)
        } else {
            refCodes
        }

        val combinedPhones = if (refPhones.isNotBlank()) {
            "$refPhones $targetPhones".trim()
        } else {
            targetPhones.trim()
        }

        val refCodesStr = buildString(cappedRefCodes.size * 14) {
            for (code in cappedRefCodes) {
                append("<|speech_")
                append(code)
                append("|>")
            }
        }

        // Exact official pnnbao97/VieNeu-TTS & neuphonic/neutts-air prompt format
        val prompt = "user: Convert the text to speech:<|TEXT_PROMPT_START|>$combinedPhones<|TEXT_PROMPT_END|>\n" +
            "assistant:<|SPEECH_GENERATION_START|>$refCodesStr"

        val safeMaxTokens = maxNewTokens.coerceIn(96, 960)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val startMs = System.currentTimeMillis()

        CrashReportStore.markNativeOperationStart(
            context = appContext,
            operation = "LlamaContext.completion (sinh speech tokens)",
            detail = "refCodes=${cappedRefCodes.size}, targetIpaLen=${targetPhones.length}, n_predict=$safeMaxTokens, threads=$threads"
        )
        return try {
            // Keep emit_partial_completion=false to avoid per-token JNI callback overhead and
            // unsafe re-entrant stopCompletion() calls from inside native C++ callbacks.
            val params = mapOf<String, Any>(
                "prompt" to prompt,
                "temperature" to temperature.coerceIn(0.1, 1.3),
                "top_k" to topK.coerceIn(1, 200),
                "top_p" to 0.95,
                "min_p" to 0.05,
                "penalty_repeat" to 1.05,
                "penalty_last_n" to 64,
                "n_threads" to threads,
                "n_predict" to safeMaxTokens,
                "ignore_eos" to false,
                "emit_partial_completion" to false,
                "stop" to listOf(
                    SPEECH_END_TOKEN,
                    "<|im_end|>",
                    "<|endoftext|>",
                    "</s>"
                )
            )

            val resultMap = ctx.completion(params)
            val elapsed = System.currentTimeMillis() - startMs

            val combinedRaw = (resultMap["text"] as? String)
                ?: (resultMap["content"] as? String)
                ?: ""

            // Cut cleanly at <|SPEECH_GENERATION_END|> if present
            val endIdx = combinedRaw.indexOf(SPEECH_END_TOKEN)
            val cleanSegment = if (endIdx >= 0) combinedRaw.substring(0, endIdx) else combinedRaw

            val codes = extractSpeechIds(cleanSegment)
            val promptPreview = "refPhones='${refPhones.take(80)}' | targetPhones='${targetPhones.take(100)}' | refCodes=${cappedRefCodes.size}"

            if (codes.size < 4) {
                val err = "GGUF sinh ra quá ít mã âm thanh (${codes.size} tokens trong ${elapsed}ms).\n" +
                    "Prompt: $promptPreview\n" +
                    "Raw output (${combinedRaw.length} chars): ${combinedRaw.take(280)}"
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
            val err = "Lỗi suy luận LlamaContext.completion():\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
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
        runCatching { llamaContext?.stopCompletion() }
    }

    @Synchronized
    fun release() {
        runCatching { llamaContext?.stopCompletion() }
        runCatching { llamaContext?.release() }
        llamaContext = null
        runCatching { activeModelPfd?.close() }
        activeModelPfd = null
        loadedModelPath = null
        statusSummary = "Đã giải phóng GGUF"
    }

    companion object {
        private const val TAG = "VieNeuGgufRunner"
        private const val DEFAULT_CONTEXT_SIZE = 1536
        private const val DEFAULT_BATCH_SIZE = 256
        private const val MAX_REF_CODES = 450
        const val SPEECH_END_TOKEN = "<|SPEECH_GENERATION_END|>"
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
