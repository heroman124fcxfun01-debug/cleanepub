package com.example.audio

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

data class VieNeuV3SynthesisOutput(
    val waveform: FloatArray?,
    val sampleRate: Int,
    val traceLog: String,
    val errorMessage: String? = null
)

/**
 * Exact Dual-AR / RQ-Transformer ONNX inference runner for **VieNeu-TTS v3 Turbo (`VieNeuV3TurboForTTS`)**
 * + **MOSS-Audio-Tokenizer-Nano (48 kHz)** matching the exact on-device tensor signatures:
 *
 * - `heads.bin` (5 tensors):
 *     `text_emb[419x768]`, `xvec_w[768x192]`, `xvec_b[768]`, `xvec_ln_w[768]`, `xvec_ln_b[768]`
 * - Special tokens (`tokenizer.json` + `config.json`):
 *     `1=<|bos|>`, `2=<|eos|>`, `3=<|TEXT_PROMPT_START|>`, `4=<|TEXT_PROMPT_END|>`,
 *     `5=<|SPEECH_GENERATION_START|>`, `6=<|SPEECH_GENERATION_END|>`,
 *     `7=<|audio_ref_slot|>`, `16=<|style_0|>` (`default_style_token_id`)
 * - `vieneu_prefill.onnx`:
 *     `in=[inputs_embeds:FLOAT[1x-1x768]]` -> `out=[hidden, present_k_0..11, present_v_0..11]`
 * - `vieneu_decode_step.onnx`:
 *     `in=[inputs_embeds:FLOAT[1x1x768], position_ids:INT64[1x1], past_k_0..11, past_v_0..11]`
 *     -> `out=[hidden:FLOAT[1x1x768], present_k_0..11, present_v_0..11]`
 * - `vieneu_acoustic_cached.onnx`:
 *     `in=[token_emb:FLOAT[1x-1x768], position_ids:INT64[1x-1], past_k_0, past_v_0]`
 *     -> `out=[hidden:FLOAT[1x-1x768], present_k_0, present_v_0]`
 * - `text_head_gather.onnx`:
 *     `in=[vec:FLOAT[1x768], code:INT32[-1]]` -> `out=[logits:FLOAT[1x419], embT:FLOAT[768x-1]]`
 * - `audio_head_0..15.onnx`:
 *     `in=[vec:FLOAT[1x768], code:INT32[-1]]` -> `out=[logits:FLOAT[1x1024], embT:FLOAT[768x-1]]`
 * - `moss_audio_tokenizer_decode_full.onnx`:
 *     `in=[audio_codes:INT32[1, numFrames, 16], audio_code_lengths:INT32[1]]`
 *     -> `out=[audio:FLOAT[1, 1, N], audio_lengths:INT32[1]]` @ 48,000 Hz
 */
class VieNeuV3OnnxRunner(
    private val env: OrtEnvironment,
    private val prefillSession: OrtSession,
    private val decodeStepSession: OrtSession,
    private val acousticSession: OrtSession,
    private val textHeadGatherSession: OrtSession?,
    private val audioHeadSessions: List<OrtSession>,
    private val mossDecodeFullSession: OrtSession,
    private val weightStore: VieNeuV3WeightStore?,
    private val config: VieNeuConfigData,
    private val tokenizer: ParsedTokenizerData?,
    private val presetVoices: List<VieNeuPresetVoice>
) {
    private val hiddenSize: Int = detectHiddenSize()
    private val numCodebooks: Int = audioHeadSessions.size.takeIf { it > 0 } ?: config.numCodebooks
    private val codebookSize: Int = config.codebookSize.coerceAtLeast(256)
    private val zeroHiddenVec = FloatArray(hiddenSize)
    private val dummyCodeZero = intArrayOf(0)

    // Special token IDs from tokenizer.json + config.json
    private val bosTokenId: Long = config.bosTokenId ?: tokenizer?.tokenToId?.get("<|bos|>") ?: 1L
    private val eosTokenId: Long = config.eosTokenId ?: tokenizer?.tokenToId?.get("<|eos|>") ?: 2L
    private val textStartTokenId: Long = tokenizer?.tokenToId?.get("<|TEXT_PROMPT_START|>") ?: 3L
    private val textEndTokenId: Long = tokenizer?.tokenToId?.get("<|TEXT_PROMPT_END|>") ?: 4L
    private val speechStartTokenId: Long = tokenizer?.tokenToId?.get("<|SPEECH_GENERATION_START|>") ?: 5L
    private val speechEndTokenId: Long = tokenizer?.tokenToId?.get("<|SPEECH_GENERATION_END|>") ?: 6L
    private val audioRefSlotTokenId: Long = config.audioRefSlotTokenId
        ?: tokenizer?.tokenToId?.get("<|audio_ref_slot|>") ?: 7L
    private val defaultStyleTokenId: Long = config.defaultStyleTokenId
        ?: tokenizer?.tokenToId?.get("<|style_0|>") ?: 16L

    // Exact `heads.bin` tensor keys
    private val textEmbKey: String? = weightStore?.findKey("text_emb", "text_embed", "embed_tokens")
    private val xvecWKey: String? = weightStore?.findKey("xvec_w", "speaker_proj.weight", "spk_proj.weight")
    private val xvecBKey: String? = weightStore?.findKey("xvec_b", "speaker_proj.bias", "spk_proj.bias")
    private val xvecLnWKey: String? = weightStore?.findKey("xvec_ln_w", "speaker_ln.weight")
    private val xvecLnBKey: String? = weightStore?.findKey("xvec_ln_b", "speaker_ln.bias")

    private fun detectHiddenSize(): Int {
        val prefillIn = prefillSession.inputInfo.values.firstOrNull()?.info as? TensorInfo
        val lastDim = prefillIn?.shape?.lastOrNull()?.toInt()
        if (lastDim != null && lastDim in 128..4096) return lastDim
        return config.hiddenSize
    }

    fun synthesize(
        cleanText: String,
        speakerEmbedding: FloatArray,
        speed: Float
    ): VieNeuV3SynthesisOutput {
        val trace = StringBuilder()
        val startMs = System.currentTimeMillis()
        try {
            // 1. Phonemize & encode Vietnamese text into IPA token IDs (without extra BOS/EOS wrappers)
            val (rawPhonemeIds, phonemeStr) = VietnameseIpaPhonemizer.encodeTextForVieNeuV3(
                cleanText = cleanText,
                tokenizer = tokenizer,
                config = null // We wrap with exact VieNeuV3TurboForTTS special tokens below!
            )
            // Strip any accidental leading BOS or trailing speech-start tokens from generic encoder
            val contentIds = rawPhonemeIds.filter {
                it != bosTokenId && it != eosTokenId && it != speechStartTokenId && it != speechEndTokenId
            }
            trace.appendLine("1. G2P/Tokens: ${contentIds.size} phoneme tok | IPA=\"${phonemeStr.take(85)}\"")
            if (contentIds.isEmpty()) {
                return VieNeuV3SynthesisOutput(null, config.outputSampleRate, trace.toString(), "Chuỗi token văn bản rỗng.")
            }

            // 2. Project 192-dim x-vector -> 768-dim via exact `LayerNorm(xvec_w @ spk + xvec_b, xvec_ln_w, xvec_ln_b)`
            val xvecCond768 = buildSpeakerConditioningEmbedding(speakerEmbedding)

            // 3. Build exact VieNeuV3TurboForTTS prompt embedding sequence:
            //    [<|bos|>, <|style_0|>, <xvecCond @ audio_ref_slot>, <|TEXT_PROMPT_START|>, ...phonemes..., <|TEXT_PROMPT_END|>, <|SPEECH_GENERATION_START|>]
            val promptVectors = ArrayList<FloatArray>(contentIds.size + 6)
            promptVectors.add(lookupSingleTextEmbedding(bosTokenId))
            promptVectors.add(lookupSingleTextEmbedding(defaultStyleTokenId))
            if (xvecCond768 != null) {
                promptVectors.add(xvecCond768)
            } else {
                promptVectors.add(lookupSingleTextEmbedding(audioRefSlotTokenId))
            }
            promptVectors.add(lookupSingleTextEmbedding(textStartTokenId))
            for (tid in contentIds) {
                promptVectors.add(lookupSingleTextEmbedding(tid))
            }
            promptVectors.add(lookupSingleTextEmbedding(textEndTokenId))
            promptVectors.add(lookupSingleTextEmbedding(speechStartTokenId))

            val promptLen = promptVectors.size
            val promptEmbeds = FloatArray(promptLen * hiddenSize)
            for (i in 0 until promptLen) {
                System.arraycopy(promptVectors[i], 0, promptEmbeds, i * hiddenSize, hiddenSize)
            }
            trace.appendLine("2. Prompt: [1, $promptLen, $hiddenSize] (xvecCond=${xvecCond768 != null}, style=$defaultStyleTokenId)")

            // 4. Backbone Prefill (`vieneu_prefill.onnx`)
            var (backboneHidden, backboneKv) = runBackbonePrefill(promptEmbeds, promptLen)
            trace.appendLine("3. Prefill OK: hidden[${backboneHidden.size}], kv=${backboneKv.size} tensors")

            // 5. Autoregressive Frame Generation Loop (Backbone + text_head EOS check + 16-codebook Acoustic RQ-Transformer)
            // MOSS-Audio-Tokenizer-Nano = 12.5 Hz (80ms/frame @ 48kHz).
            val wordCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
            val minFrames = (wordCount * 1.2f).roundToInt().coerceIn(6, 28)
            val maxFrames = ((wordCount * 5.2f) / speed.coerceIn(0.75f, 1.5f))
                .roundToInt()
                .coerceIn(36, 260)

            val generatedFrames = ArrayList<IntArray>(maxFrames)
            val recentC0 = ArrayList<Int>(32)
            val rng = Random(cleanText.hashCode() xor speakerEmbedding.contentHashCode())
            var backbonePos = promptLen
            var stopReason = "max_frames"
            var repeatRun = 0
            var prevC0 = -1

            for (frameIdx in 0 until maxFrames) {
                // Check `<|SPEECH_GENERATION_END|>` (6) or `<|eos|>` (2) via `text_head_gather.onnx`
                if (frameIdx >= minFrames && checkSpeechEndViaTextHead(backboneHidden)) {
                    stopReason = "speech_end@$frameIdx"
                    break
                }

                // Run 1-layer Acoustic Depth Transformer (`vieneu_acoustic_cached.onnx`) + `audio_head_0..15.onnx`
                val (frameCodes, frameEmbSum) = decodeSingleAcousticFrame(
                    backboneHidden = backboneHidden,
                    recentC0 = recentC0,
                    rng = rng
                )
                generatedFrames.add(frameCodes)

                val c0 = frameCodes[0]
                if (c0 == prevC0) {
                    repeatRun++
                    if (repeatRun >= 16 && frameIdx >= minFrames) {
                        stopReason = "repeat_guard@$frameIdx"
                        break
                    }
                } else {
                    repeatRun = 0
                    prevC0 = c0
                }
                recentC0.add(c0)
                if (recentC0.size > 24) recentC0.removeAt(0)

                // Advance 12-layer backbone by 1 frame using summed 16-codebook embedding
                val (nextHidden, nextKv) = runBackboneDecodeStep(
                    stepEmbed = frameEmbSum,
                    position = backbonePos,
                    pastKv = backboneKv
                )
                backboneHidden = nextHidden
                backboneKv = nextKv
                backbonePos++
            }

            trace.appendLine(
                "4. Acoustic+Decode OK: ${generatedFrames.size} frames " +
                    "(stop=$stopReason, ~${String.format(Locale.US, "%.2f", generatedFrames.size * 0.08f)}s)"
            )
            if (generatedFrames.isEmpty()) {
                return VieNeuV3SynthesisOutput(
                    waveform = null,
                    sampleRate = config.outputSampleRate,
                    traceLog = trace.toString(),
                    errorMessage = "Backbone + Acoustic không sinh ra frame âm thanh nào."
                )
            }

            // 6. Decode `INT32[1, numFrames, 16]` -> 48 kHz PCM via `moss_audio_tokenizer_decode_full.onnx`
            val rawAudio = runMossCodecDecodeFull(generatedFrames)
            val elapsedMs = System.currentTimeMillis() - startMs
            trace.appendLine("5. MOSS 48kHz Decode OK: ${rawAudio.size} samples (${elapsedMs}ms)")

            if (rawAudio.size < 256) {
                return VieNeuV3SynthesisOutput(
                    waveform = null,
                    sampleRate = config.outputSampleRate,
                    traceLog = trace.toString(),
                    errorMessage = "moss_audio_tokenizer_decode_full trả về sóng âm quá ngắn (${rawAudio.size} mẫu)."
                )
            }

            return VieNeuV3SynthesisOutput(
                waveform = rawAudio,
                sampleRate = config.outputSampleRate,
                traceLog = trace.toString(),
                errorMessage = null
            )
        } catch (e: Throwable) {
            val fullErr = OnnxModelAssetHelper.formatFullThrowable(e)
            trace.appendLine("ERROR: $fullErr")
            Log.e(TAG, "VieNeuV3 synthesis error:\n$trace", e)
            return VieNeuV3SynthesisOutput(
                waveform = null,
                sampleRate = config.outputSampleRate,
                traceLog = trace.toString(),
                errorMessage = "$fullErr\n\n--- Trace ---\n${trace.toString().trimEnd()}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Stage 1: Exact `text_emb` lookup & `xvec` LayerNorm projection from `heads.bin`
    // -------------------------------------------------------------------------

    private fun lookupSingleTextEmbedding(tokenId: Long): FloatArray {
        val store = weightStore
        if (textEmbKey != null && store != null) {
            val table = store.getFloats(textEmbKey)
            val meta = store.getMeta(textEmbKey)
            if (table != null && meta != null && meta.shape.size >= 2) {
                val rows = meta.shape[0].coerceAtLeast(1)
                val dim = meta.shape[1].coerceAtLeast(1)
                val row = tokenId.toInt().coerceIn(0, rows - 1)
                val out = FloatArray(hiddenSize)
                System.arraycopy(table, row * dim, out, 0, minOf(dim, hiddenSize))
                return out
            }
        }

        // Fallback via `text_head_gather.onnx` (passing both `vec` and `code`)
        val th = textHeadGatherSession
        if (th != null) {
            val (_, emb) = runHeadGatherDual(th, zeroHiddenVec, tokenId.toInt().coerceAtLeast(0))
            if (emb != null && emb.size >= hiddenSize) return emb.copyOf(hiddenSize)
        }

        return FloatArray(hiddenSize)
    }

    private fun buildSpeakerConditioningEmbedding(speakerEmbedding: FloatArray): FloatArray? {
        if (speakerEmbedding.isEmpty()) return null
        val store = weightStore ?: return null
        val wKey = xvecWKey ?: return null
        val w = store.getFloats(wKey) ?: return null
        val wMeta = store.getMeta(wKey) ?: return null
        val b = xvecBKey?.let { store.getFloats(it) }

        val inDim = if (wMeta.shape.size >= 2) {
            if (wMeta.shape[1] == speakerEmbedding.size) wMeta.shape[1]
            else if (wMeta.shape[0] == speakerEmbedding.size) wMeta.shape[0]
            else wMeta.shape[1]
        } else {
            speakerEmbedding.size
        }
        val fitted = if (speakerEmbedding.size == inDim) speakerEmbedding else FloatArray(inDim) { i ->
            if (i < speakerEmbedding.size) speakerEmbedding[i] else 0f
        }
        // 1. Linear projection: xvec_w[768x192] @ spk[192] + xvec_b[768]
        var proj = VieNeuV3WeightStore.linearProject(fitted, w, wMeta.shape, b)

        // 2. Exact LayerNorm: xvec_ln_w[768], xvec_ln_b[768]
        val lnW = xvecLnWKey?.let { store.getFloats(it) }
        val lnB = xvecLnBKey?.let { store.getFloats(it) }
        if (lnW != null || lnB != null) {
            proj = VieNeuV3WeightStore.layerNorm(proj, lnW, lnB, eps = 1e-5f)
        }

        return if (proj.size == hiddenSize) proj else FloatArray(hiddenSize) { i ->
            if (i < proj.size) proj[i] else 0f
        }
    }

    /**
     * Runs `text_head_gather.onnx` or `audio_head_q.onnx` with BOTH required inputs:
     * `vec: FLOAT[1, 768]` and `code: INT32[1]` -> returns `(logits, emb768)`.
     */
    private fun runHeadGatherDual(
        session: OrtSession,
        vec: FloatArray,
        code: Int
    ): Pair<FloatArray, FloatArray?> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in session.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT
                val lower = name.lowercase(Locale.ROOT)
                inputs[name] = when {
                    type == OnnxJavaType.INT32 || lower.contains("code") || lower.contains("id") -> {
                        val dims = if (shape != null && shape.size == 2) longArrayOf(1L, 1L) else longArrayOf(1L)
                        if (type == OnnxJavaType.INT64) {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(code.toLong())), dims)
                        } else {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(code)), dims)
                        }
                    }
                    else -> {
                        val dims = when (shape?.size) {
                            3 -> longArrayOf(1L, 1L, vec.size.toLong())
                            2 -> longArrayOf(1L, vec.size.toLong())
                            else -> longArrayOf(vec.size.toLong())
                        }
                        VieNeuV3WeightStore.createFloatOrFp16Tensor(env, vec, dims, type)
                    }
                }
            }

            session.run(inputs).use { res ->
                val logits = (res[0] as? OnnxTensor)?.let { VieNeuV3WeightStore.readTensorAsFloatsSafe(it) }
                    ?: FloatArray(0)
                val emb = if (res.size() >= 2) {
                    (res[1] as? OnnxTensor)?.let { VieNeuV3WeightStore.readTensorAsFloatsSafe(it) }
                } else {
                    null
                }
                return logits to emb
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Checks whether the backbone hidden state predicts `<|SPEECH_GENERATION_END|>` (6) or `<|eos|>` (2)
     * through `text_head_gather.onnx`.
     */
    private fun checkSpeechEndViaTextHead(backboneHidden: FloatArray): Boolean {
        val th = textHeadGatherSession ?: return false
        val (logits, _) = runHeadGatherDual(th, backboneHidden, 0)
        if (logits.isEmpty()) return false

        val endIdx = speechEndTokenId.toInt()
        val eosIdx = eosTokenId.toInt()

        var maxVal = Float.NEGATIVE_INFINITY
        var argmax = 0
        for (i in logits.indices) {
            val v = logits[i]
            if (v.isFinite() && v > maxVal) {
                maxVal = v
                argmax = i
            }
        }
        if (argmax == endIdx || argmax == eosIdx) return true

        var sumExp = 0.0
        for (v in logits) {
            if (v.isFinite()) sumExp += exp((v - maxVal).toDouble())
        }
        if (sumExp <= 0.0) return false
        val pEnd = if (endIdx in logits.indices) exp((logits[endIdx] - maxVal).toDouble()) / sumExp else 0.0
        val pEos = if (eosIdx in logits.indices) exp((logits[eosIdx] - maxVal).toDouble()) / sumExp else 0.0
        return (pEnd + pEos) >= 0.35
    }

    // -------------------------------------------------------------------------
    // Stage 2: 12-Layer Backbone (`vieneu_prefill.onnx` & `vieneu_decode_step.onnx`)
    // -------------------------------------------------------------------------

    private fun runBackbonePrefill(
        promptEmbeds: FloatArray,
        seqLen: Int
    ): Pair<FloatArray, LinkedHashMap<String, SafeTensorPayload>> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in prefillSession.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT
                val lower = name.lowercase(Locale.ROOT)
                inputs[name] = when {
                    lower.contains("embed") || (lower.contains("input") && type in setOf(OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16)) -> {
                        VieNeuV3WeightStore.createFloatOrFp16Tensor(
                            env = env,
                            floats = promptEmbeds,
                            shape = longArrayOf(1L, seqLen.toLong(), hiddenSize.toLong()),
                            expectedType = type
                        )
                    }
                    lower.contains("position") || lower == "pos" -> {
                        val dims = if (shape != null && shape.size == 1) longArrayOf(seqLen.toLong()) else longArrayOf(1L, seqLen.toLong())
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(seqLen) { it }), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seqLen) { it.toLong() }), dims)
                        }
                    }
                    else -> {
                        val dims = shape?.map { if (it > 0L) it else 1L }?.toLongArray() ?: longArrayOf(1L)
                        VieNeuV3WeightStore.createEmptyTypedTensor(env, dims, type)
                    }
                }
            }

            prefillSession.run(inputs).use { res ->
                val outNames = prefillSession.outputNames.toList()
                val hiddenTensor = res[0] as OnnxTensor
                val allHidden = VieNeuV3WeightStore.readTensorAsFloatsSafe(hiddenTensor)
                val lastHidden = extractLastHiddenVector(allHidden, hiddenSize)
                val kv = extractKvCacheSafe(outNames, res)
                return lastHidden to kv
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun runBackboneDecodeStep(
        stepEmbed: FloatArray,
        position: Int,
        pastKv: Map<String, SafeTensorPayload>
    ): Pair<FloatArray, LinkedHashMap<String, SafeTensorPayload>> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in decodeStepSession.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT
                val lower = name.lowercase(Locale.ROOT)

                inputs[name] = when {
                    lower.contains("past") || lower.contains("cache") ||
                        lower.startsWith("k_") || lower.startsWith("v_") -> {
                        val matched = findMatchingKv(name, pastKv)
                        if (matched != null) {
                            VieNeuV3WeightStore.createOnnxTensorFromPayload(env, matched, type)
                        } else {
                            VieNeuV3WeightStore.createEmptyTypedTensor(env, defaultZeroKvShape(shape), type)
                        }
                    }
                    lower.contains("embed") || lower.contains("hidden") ||
                        (type in setOf(OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16) && shape?.lastOrNull() == hiddenSize.toLong()) -> {
                        val dims = when (shape?.size) {
                            2 -> longArrayOf(1L, hiddenSize.toLong())
                            else -> longArrayOf(1L, 1L, hiddenSize.toLong())
                        }
                        VieNeuV3WeightStore.createFloatOrFp16Tensor(env, stepEmbed, dims, type)
                    }
                    lower.contains("position") || lower == "pos" -> {
                        val dims = if (shape != null && shape.size == 1) longArrayOf(1L) else longArrayOf(1L, 1L)
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(position)), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(position.toLong())), dims)
                        }
                    }
                    else -> {
                        val dims = shape?.map { if (it > 0L) it else 1L }?.toLongArray() ?: longArrayOf(1L)
                        VieNeuV3WeightStore.createEmptyTypedTensor(env, dims, type)
                    }
                }
            }

            decodeStepSession.run(inputs).use { res ->
                val outNames = decodeStepSession.outputNames.toList()
                val hiddenTensor = res[0] as OnnxTensor
                val allHidden = VieNeuV3WeightStore.readTensorAsFloatsSafe(hiddenTensor)
                val lastHidden = extractLastHiddenVector(allHidden, hiddenSize)
                val kv = extractKvCacheSafe(outNames, res)
                return lastHidden to kv
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    // -------------------------------------------------------------------------
    // Stage 3: 1-Layer Acoustic Depth Transformer + 16 Tied `audio_head_0..15.onnx`
    // -------------------------------------------------------------------------

    private fun decodeSingleAcousticFrame(
        backboneHidden: FloatArray,
        recentC0: List<Int>,
        rng: Random
    ): Pair<IntArray, FloatArray> {
        val codes = IntArray(numCodebooks)
        val summedEmb = FloatArray(hiddenSize)
        var acousticKv = LinkedHashMap<String, SafeTensorPayload>()
        var currentInputEmb = backboneHidden

        for (q in 0 until numCodebooks) {
            // 1. Run 1-layer acoustic depth transformer at depth position `q`
            val (acHidden, nextKv) = runAcousticStep(
                tokenEmb = currentInputEmb,
                depthPos = q,
                pastKv = acousticKv
            )
            acousticKv = nextKv

            val headSession = audioHeadSessions.getOrNull(q)
            if (headSession != null) {
                // 2. Predict codebook `q` logits[1024] from `acHidden`
                val (logits, _) = runHeadGatherDual(headSession, acHidden, 0)
                val sampled = VieNeuV3WeightStore.sampleLogits(
                    logits = logits,
                    temperature = if (q == 0) 0.72f else 0.55f,
                    topK = if (q == 0) 40 else 24,
                    topP = 0.88f,
                    maxValidIndex = (codebookSize - 1).coerceAtMost(logits.size - 1),
                    recentTokens = if (q == 0 && recentC0.isNotEmpty()) recentC0.toIntArray() else null,
                    repetitionPenalty = if (q == 0) 1.14f else 1.0f,
                    rng = rng
                ).coerceIn(0, codebookSize - 1)
                codes[q] = sampled

                // 3. Gather exact tied embedding `embT[768x1]` for the sampled codebook token `sampled`
                val (_, gatheredEmb) = runHeadGatherDual(headSession, zeroHiddenVec, sampled)
                if (gatheredEmb != null && gatheredEmb.size >= hiddenSize) {
                    currentInputEmb = if (gatheredEmb.size == hiddenSize) gatheredEmb else gatheredEmb.copyOf(hiddenSize)
                    for (d in 0 until hiddenSize) {
                        summedEmb[d] += currentInputEmb[d]
                    }
                }
            }
        }
        return codes to summedEmb
    }

    private fun runAcousticStep(
        tokenEmb: FloatArray,
        depthPos: Int,
        pastKv: Map<String, SafeTensorPayload>
    ): Pair<FloatArray, LinkedHashMap<String, SafeTensorPayload>> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in acousticSession.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT
                val lower = name.lowercase(Locale.ROOT)

                inputs[name] = when {
                    lower.contains("past") || lower.contains("cache") ||
                        lower.startsWith("k_") || lower.startsWith("v_") -> {
                        val matched = findMatchingKv(name, pastKv)
                        if (matched != null) {
                            VieNeuV3WeightStore.createOnnxTensorFromPayload(env, matched, type)
                        } else {
                            VieNeuV3WeightStore.createEmptyTypedTensor(env, defaultZeroKvShape(shape), type)
                        }
                    }
                    lower.contains("position") || lower == "pos" -> {
                        val dims = if (shape != null && shape.size == 1) longArrayOf(1L) else longArrayOf(1L, 1L)
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(depthPos)), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(depthPos.toLong())), dims)
                        }
                    }
                    lower.contains("emb") || lower.contains("hidden") || lower.contains("input") -> {
                        val dims = when (shape?.size) {
                            2 -> longArrayOf(1L, hiddenSize.toLong())
                            else -> longArrayOf(1L, 1L, hiddenSize.toLong())
                        }
                        VieNeuV3WeightStore.createFloatOrFp16Tensor(env, tokenEmb, dims, type)
                    }
                    else -> {
                        val dims = shape?.map { if (it > 0L) it else 1L }?.toLongArray() ?: longArrayOf(1L)
                        VieNeuV3WeightStore.createEmptyTypedTensor(env, dims, type)
                    }
                }
            }

            acousticSession.run(inputs).use { res ->
                val outNames = acousticSession.outputNames.toList()
                val hiddenTensor = res[0] as OnnxTensor
                val allHidden = VieNeuV3WeightStore.readTensorAsFloatsSafe(hiddenTensor)
                val lastHidden = extractLastHiddenVector(allHidden, hiddenSize)
                val kv = extractKvCacheSafe(outNames, res)
                return lastHidden to kv
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    // -------------------------------------------------------------------------
    // Stage 4: MOSS-Audio-Tokenizer-Nano Full Decoder (48,000 Hz)
    // -------------------------------------------------------------------------

    private fun runMossCodecDecodeFull(frames: List<IntArray>): FloatArray {
        val numFrames = frames.size
        val nq = numCodebooks
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in mossDecodeFullSession.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.INT32
                val lower = name.lowercase(Locale.ROOT)

                inputs[name] = when {
                    lower.contains("len") -> {
                        if (type == OnnxJavaType.INT64) {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(numFrames.toLong())), longArrayOf(1L))
                        } else {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(numFrames)), longArrayOf(1L))
                        }
                    }
                    else -> {
                        // Verified device signature: `audio_codes:INT32[-1x-1x16]` -> [1, numFrames, 16]
                        val codebooksLast = shape != null && shape.size == 3 && (shape[2] == nq.toLong() || shape[2] == 16L)
                        val dims = if (codebooksLast) {
                            longArrayOf(1L, numFrames.toLong(), nq.toLong())
                        } else {
                            longArrayOf(1L, nq.toLong(), numFrames.toLong())
                        }
                        val flatInts = IntArray(numFrames * nq)
                        if (codebooksLast) {
                            for (f in 0 until numFrames) {
                                val row = frames[f]
                                val off = f * nq
                                for (q in 0 until nq) {
                                    flatInts[off + q] = row[q].coerceIn(0, codebookSize - 1)
                                }
                            }
                        } else {
                            for (q in 0 until nq) {
                                val off = q * numFrames
                                for (f in 0 until numFrames) {
                                    flatInts[off + f] = frames[f][q].coerceIn(0, codebookSize - 1)
                                }
                            }
                        }
                        if (type == OnnxJavaType.INT64) {
                            OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(LongArray(flatInts.size) { flatInts[it].toLong() }),
                                dims
                            )
                        } else {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(flatInts), dims)
                        }
                    }
                }
            }

            mossDecodeFullSession.run(inputs).use { res ->
                val audioTensor = res[0] as OnnxTensor
                val outShape = audioTensor.info.shape
                val rawFloats = VieNeuV3WeightStore.readTensorAsFloatsSafe(audioTensor)

                var validLen = rawFloats.size
                if (res.size() >= 2) {
                    val lenTensor = res[1] as? OnnxTensor
                    if (lenTensor != null) {
                        val lengths = VieNeuV3WeightStore.readTensorAsIntsSafe(lenTensor)
                        val declared = lengths.firstOrNull() ?: 0
                        if (declared in 256..rawFloats.size) {
                            validLen = declared
                        }
                    }
                }

                if (outShape.size == 3 && outShape[1] == 2L && rawFloats.size >= 2) {
                    val half = rawFloats.size / 2
                    val count = minOf(validLen, half)
                    return FloatArray(count) { i -> (rawFloats[i] + rawFloats[half + i]) * 0.5f }
                }
                return if (validLen < rawFloats.size) rawFloats.copyOf(validLen) else rawFloats
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun extractLastHiddenVector(flat: FloatArray, dim: Int): FloatArray {
        if (flat.size <= dim) {
            return if (flat.size == dim) flat else FloatArray(dim) { i -> if (i < flat.size) flat[i] else 0f }
        }
        return flat.copyOfRange(flat.size - dim, flat.size)
    }

    private fun extractKvCacheSafe(
        outputNames: List<String>,
        result: OrtSession.Result
    ): LinkedHashMap<String, SafeTensorPayload> {
        val kv = LinkedHashMap<String, SafeTensorPayload>()
        for (i in 0 until result.size()) {
            val outName = outputNames.getOrNull(i) ?: continue
            val lower = outName.lowercase(Locale.ROOT)
            if (lower.contains("present") || lower.contains("cache") || lower.contains("past") ||
                lower.startsWith("k_") || lower.startsWith("v_")
            ) {
                val tensor = result[i] as? OnnxTensor ?: continue
                kv[outName] = VieNeuV3WeightStore.readSafePayload(tensor)
            }
        }
        return kv
    }

    private fun findMatchingKv(
        inputName: String,
        pastKv: Map<String, SafeTensorPayload>
    ): SafeTensorPayload? {
        if (pastKv.isEmpty()) return null
        pastKv[inputName]?.let { return it }
        val alias = inputName
            .replace("past_key_values", "present")
            .replace("past_", "present_")
        pastKv[alias]?.let { return it }

        val digits = inputName.filter { it.isDigit() }
        val lower = inputName.lowercase(Locale.ROOT)
        val isK = lower.contains("_k_") || lower.contains("key") || lower.startsWith("k_")
        val isV = lower.contains("_v_") || lower.contains("val") || lower.startsWith("v_")
        for ((k, v) in pastKv) {
            if (k.filter { it.isDigit() } == digits) {
                val kl = k.lowercase(Locale.ROOT)
                if ((isK && (kl.contains("_k_") || kl.contains("key") || kl.startsWith("k_"))) ||
                    (isV && (kl.contains("_v_") || kl.contains("val") || kl.startsWith("v_")))
                ) {
                    return v
                }
            }
        }
        return null
    }

    private fun defaultZeroKvShape(shape: LongArray?): LongArray {
        if (shape == null || shape.isEmpty()) return longArrayOf(1L, 8L, 0L, 96L)
        return LongArray(shape.size) { idx ->
            val d = shape[idx]
            when {
                d > 0L -> d
                idx == 0 -> 1L
                shape.size == 4 && idx == 2 -> 0L
                else -> 1L
            }
        }
    }

    companion object {
        private const val TAG = "VieNeuV3OnnxRunner"
    }
}
