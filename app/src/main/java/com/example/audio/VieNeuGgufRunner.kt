package com.example.audio

import android.os.ParcelFileDescriptor
import android.util.Log
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
 * The GGUF backbone autoregressively predicts `<|speech_N|>` tokens until `<|SPEECH_GENERATION_END|>`.
 */
class VieNeuGgufRunner {

    private var llamaContext: LlamaContext? = null
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

        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = ParcelFileDescriptor.open(ggufFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val params = mapOf<String, Any>(
                "model" to ggufFile.absolutePath,
                "model_fd" to pfd.fd,
                "embedding" to false,
                "n_ctx" to DEFAULT_CONTEXT_SIZE,
                "n_batch" to 512,
                "n_threads" to threads,
                "n_gpu_layers" to 0,
                "use_mlock" to false,
                "use_mmap" to true,
                "vocab_only" to false
            )
            val ctx = LlamaContext(1, params)
            llamaContext = ctx
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
            val fullErr = "LlamaContext(${ggufFile.name}) thất bại:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
            lastLoadError = fullErr
            statusSummary = "LỖI NẠP GGUF"
            Log.e(TAG, fullErr, e)
            false
        } finally {
            runCatching { pfd?.close() }
        }
    }

    @Synchronized
    fun generateSpeechCodes(
        refPhones: String,
        targetPhones: String,
        refCodes: IntArray,
        temperature: Double = 0.8,
        topK: Int = 50,
        maxNewTokens: Int = 1024
    ): GgufGenerationResult {
        val ctx = llamaContext ?: return GgufGenerationResult(
            errorMessage = lastLoadError ?: "Chưa nạp được model VieNeu-TTS GGUF."
        )
        if (targetPhones.isBlank()) {
            return GgufGenerationResult(errorMessage = "Chuỗi âm vị mục tiêu trống.")
        }

        // Cap reference codes to leave sufficient room inside n_ctx=2048
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

        val safeMaxTokens = maxNewTokens.coerceIn(96, 1280)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val startMs = System.currentTimeMillis()
        val streamedText = StringBuilder(4096)

        return try {
            ctx.setTokenCallback { piece ->
                streamedText.append(piece)
                if (streamedText.contains(SPEECH_END_TOKEN)) {
                    runCatching { ctx.stopCompletion() }
                }
            }

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
                "emit_partial_completion" to true,
                "stop" to listOf(
                    SPEECH_END_TOKEN,
                    "<|im_end|>",
                    "<|endoftext|>",
                    "</s>"
                )
            )

            val resultMap = ctx.completion(params)
            val elapsed = System.currentTimeMillis() - startMs

            val resultText = (resultMap["text"] as? String)
                ?: (resultMap["content"] as? String)
                ?: ""
            val combinedRaw = if (resultText.length >= streamedText.length) {
                resultText
            } else {
                streamedText.toString()
            }

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
            runCatching { ctx.setTokenCallback {} }
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
        loadedModelPath = null
        statusSummary = "Đã giải phóng GGUF"
    }

    companion object {
        private const val TAG = "VieNeuGgufRunner"
        private const val DEFAULT_CONTEXT_SIZE = 2048
        private const val MAX_REF_CODES = 550
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
