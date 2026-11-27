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
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

data class VieNeuV3SynthesisOutput(
    val waveform: FloatArray?,
    val sampleRate: Int,
    val traceLog: String,
    val errorMessage: String? = null
)

/**
 * Dual-AR / RQ-Transformer ONNX inference runner for **VieNeu-TTS v3 Turbo (`VieNeuV3TurboForTTS`)**
 * + **MOSS-Audio-Tokenizer-Nano (48 kHz)**:
 *
 * - ByteLevel BPE tokenization (`tokenizer.json`: 44 special + 256 ByteLevel + 119 BPE merges = 419 tokens)
 * - Dual voice-cloning conditioning:
 *     1. Global 192-d `speakerEmbedding` -> `LayerNorm(xvec_w @ spk + xvec_b, xvec_ln_w, xvec_ln_b)`
 *     2. Reference acoustic frames `referenceCodes[T_ref][16]` -> embedded via batched `audio_head_0..15.onnx`
 *        (`embT[768, T_ref]`, transposed row-major) into the `<|audio_ref_slot|>` prompt context
 * - 12-layer Qwen3 backbone (`vieneu_prefill.onnx` + `vieneu_decode_step.onnx`)
 * - 1-layer Acoustic Depth Transformer (`vieneu_acoustic_cached.onnx`) + 16 tied `audio_head_0..15.onnx`
 *   with automatic `c_0` head calibration (`audio_head_0(h_t)` direct Dual-AR vs `audio_head_0(ac(h_t, pos=0))`)
 * - 48 kHz waveform synthesis (`moss_audio_tokenizer_decode_full.onnx`)
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
        speed: Float,
        referenceCodes: Array<IntArray>? = null
    ): VieNeuV3SynthesisOutput {
        val trace = StringBuilder()
        val startMs = System.currentTimeMillis()
        try {
            // 1. Phonemize & ByteLevel-BPE encode Vietnamese text
            val (rawPhonemeIds, phonemeStr) = VietnameseIpaPhonemizer.encodeTextForVieNeuV3(
                cleanText = cleanText,
                tokenizer = tokenizer,
                config = null // Exact VieNeuV3TurboForTTS special tokens are wrapped explicitly below
            )
            val contentIds = rawPhonemeIds.filter {
                it != bosTokenId && it != eosTokenId && it != speechStartTokenId && it != speechEndTokenId
            }
            val tokPreview = contentIds.take(18).joinToString(",")
            trace.appendLine(
                "1. G2P/BPE: ${contentIds.size} tok (byteBpe=${tokenizer?.isByteLevelBpe == true}, merges=${tokenizer?.merges?.size ?: 0}) " +
                    "| ids=[$tokPreview] | IPA=\"${phonemeStr.take(90)}\""
            )
            if (contentIds.isEmpty()) {
                return VieNeuV3SynthesisOutput(null, config.outputSampleRate, trace.toString(), "Chuỗi token văn bản rỗng.")
            }

            // 2. Project 192-dim speakerEmbedding -> 768-dim via exact LayerNorm(xvec_w @ spk + xvec_b)
            val spkNorm = vectorNorm(speakerEmbedding)
            val xvecCond768 = buildSpeakerConditioningEmbedding(speakerEmbedding)

            // 3. Embed optional referenceCodes[T_ref][16] (from voices.json or moss_audio_tokenizer_encode.onnx)
            val refFrameEmbeds = if (!referenceCodes.isNullOrEmpty()) {
                embedReferenceAudioFramesBatched(referenceCodes)
            } else {
                emptyList()
            }

            // 4. Build VieNeuV3TurboForTTS prompt embedding sequence:
            //    [<|bos|>, <|style_0|>, xvecCond, ...refFrameEmbeds (@ audio_ref_slot)...,
            //     <|TEXT_PROMPT_START|>, ...ByteLevel BPE phoneme tokens..., <|TEXT_PROMPT_END|>,
            //     <|SPEECH_GENERATION_START|>]
            val promptVectors = ArrayList<FloatArray>(contentIds.size + refFrameEmbeds.size + 6)
            promptVectors.add(lookupSingleTextEmbedding(bosTokenId))
            promptVectors.add(lookupSingleTextEmbedding(defaultStyleTokenId))
            if (xvecCond768 != null) {
                promptVectors.add(xvecCond768)
            } else {
                promptVectors.add(lookupSingleTextEmbedding(audioRefSlotTokenId))
            }
            if (refFrameEmbeds.isNotEmpty()) {
                val refSlotBias = lookupSingleTextEmbedding(audioRefSlotTokenId)
                val refSlotNorm = vectorNorm(refSlotBias)
                // Add learned audio_ref_slot embedding if non-zero (keeps norm aligned with backbone space)
                for (rf in refFrameEmbeds) {
                    if (refSlotNorm > 1e-4f) {
                        val combined = FloatArray(hiddenSize) { d -> rf[d] + refSlotBias[d] }
                        promptVectors.add(combined)
                    } else {
                        promptVectors.add(rf)
                    }
                }
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
            trace.appendLine(
                "2. Prompt: [1, $promptLen, $hiddenSize] " +
                    "(spkNorm=${String.format(Locale.US, "%.2f", spkNorm)}, refFrames=${refFrameEmbeds.size}, style=$defaultStyleTokenId)"
            )

            // 5. Backbone Prefill (`vieneu_prefill.onnx`)
            var (backboneHidden, backboneKv) = runBackbonePrefill(promptEmbeds, promptLen)
            val th0Info = inspectTextHeadTop3(backboneHidden)

            // 6. Auto-calibrate whether `audio_head_0` expects `backboneHidden` directly (Dual-AR CSM/Qwen3 style)
            //    or `vieneu_acoustic_cached(backboneHidden, pos=0)` by comparing logit entropy on frame 0!
            val (useDirectC0FromBackbone, c0CalibNote) = calibrateC0Path(backboneHidden)
            trace.appendLine("3. Prefill OK: textHead@0=[$th0Info] | $c0CalibNote")

            // 7. Autoregressive Frame Generation Loop
            val wordCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
            val minFrames = (wordCount * 1.1f).roundToInt().coerceIn(5, 24)
            val maxFrames = ((wordCount * 5.0f) / speed.coerceIn(0.75f, 1.5f))
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

                // Decode all 16 RVQ codebooks for frame `frameIdx`
                val (frameCodes, frameEmbSum) = decodeSingleAcousticFrame(
                    backboneHidden = backboneHidden,
                    useDirectC0 = useDirectC0FromBackbone,
                    recentC0 = recentC0,
                    rng = rng
                )
                generatedFrames.add(frameCodes)

                val c0 = frameCodes[0]
                if (c0 == prevC0) {
                    repeatRun++
                    if (repeatRun >= 14 && frameIdx >= minFrames) {
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

            val thEndInfo = inspectTextHeadTop3(backboneHidden)
            trace.appendLine(
                "4. Acoustic+Decode OK: ${generatedFrames.size} frames " +
                    "(stop=$stopReason, ~${String.format(Locale.US, "%.2f", generatedFrames.size * 0.08f)}s, textHead@end=[$thEndInfo])"
            )
            if (generatedFrames.isEmpty()) {
                return VieNeuV3SynthesisOutput(
                    waveform = null,
                    sampleRate = config.outputSampleRate,
                    traceLog = trace.toString(),
                    errorMessage = "Backbone + Acoustic không sinh ra frame âm thanh nào."
                )
            }

            // 8. Decode `INT32[1, numFrames, 16]` -> 48 kHz PCM via `moss_audio_tokenizer_decode_full.onnx`
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
    // Stage 1: Exact `text_emb`, `xvec` LayerNorm, and Batched `referenceCodes` Embedding
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
        val th = textHeadGatherSession
        if (th != null) {
            val (_, emb) = runHeadGatherBatch(th, zeroHiddenVec, intArrayOf(tokenId.toInt().coerceAtLeast(0)))
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
        var proj = VieNeuV3WeightStore.linearProject(fitted, w, wMeta.shape, b)

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
     * Embeds `referenceCodes[T_ref][16]` into `T_ref` 768-dim vectors in only 16 batched calls
     * to `audio_head_0..15.onnx`, accurately transposing `embT: FLOAT[768, T_ref]`.
     */
    private fun embedReferenceAudioFramesBatched(referenceCodes: Array<IntArray>): List<FloatArray> {
        val frameCount = referenceCodes.size.coerceAtMost(96)
        if (frameCount <= 0 || audioHeadSessions.isEmpty()) return emptyList()

        val summedFrames = Array(frameCount) { FloatArray(hiddenSize) }
        val qLimit = minOf(numCodebooks, audioHeadSessions.size)
        val colCodes = IntArray(frameCount)

        for (q in 0 until qLimit) {
            for (t in 0 until frameCount) {
                val row = referenceCodes[t]
                colCodes[t] = if (q < row.size) row[q].coerceIn(0, codebookSize - 1) else 0
            }
            val (_, embT) = runHeadGatherBatch(audioHeadSessions[q], zeroHiddenVec, colCodes)
            if (embT != null && embT.size >= hiddenSize * frameCount) {
                // `embT` shape is `[768, frameCount]` (row-major transposed!): element (d, t) = embT[d * frameCount + t]
                for (d in 0 until hiddenSize) {
                    val rowOff = d * frameCount
                    for (t in 0 until frameCount) {
                        summedFrames[t][d] += embT[rowOff + t]
                    }
                }
            }
        }
        return summedFrames.toList()
    }

    /**
     * Runs `text_head_gather.onnx` or `audio_head_q.onnx` with `vec: FLOAT[1, 768]` and `code: INT32[N]`.
     * Returns `(logits, embT_flat)` where `embT` has shape `[768, N]`.
     */
    private fun runHeadGatherBatch(
        session: OrtSession,
        vec: FloatArray,
        codes: IntArray
    ): Pair<FloatArray, FloatArray?> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in session.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.FLOAT
                val lower = name.lowercase(Locale.ROOT)
                inputs[name] = when {
                    type == OnnxJavaType.INT32 || type == OnnxJavaType.INT64 ||
                        lower.contains("code") || lower.contains("id") -> {
                        val dims = if (shape != null && shape.size == 2) {
                            longArrayOf(1L, codes.size.toLong())
                        } else {
                            longArrayOf(codes.size.toLong())
                        }
                        if (type == OnnxJavaType.INT64) {
                            OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(LongArray(codes.size) { codes[it].toLong() }),
                                dims
                            )
                        } else {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(codes), dims)
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

    private fun checkSpeechEndViaTextHead(backboneHidden: FloatArray): Boolean {
        val th = textHeadGatherSession ?: return false
        val (logits, _) = runHeadGatherBatch(th, backboneHidden, intArrayOf(0))
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

    private fun inspectTextHeadTop3(backboneHidden: FloatArray): String {
        val th = textHeadGatherSession ?: return "n/a"
        val (logits, _) = runHeadGatherBatch(th, backboneHidden, intArrayOf(0))
        if (logits.isEmpty()) return "empty"
        var maxVal = Float.NEGATIVE_INFINITY
        for (v in logits) if (v.isFinite() && v > maxVal) maxVal = v
        var sumExp = 0.0
        for (v in logits) if (v.isFinite()) sumExp += exp((v - maxVal).toDouble())
        if (sumExp <= 0.0) return "nan"

        val top = logits.indices
            .sortedByDescending { if (logits[it].isFinite()) logits[it] else Float.NEGATIVE_INFINITY }
            .take(3)
        return top.joinToString(",") { idx ->
            val p = exp((logits[idx] - maxVal).toDouble()) / sumExp
            val lbl = tokenizer?.idToToken?.get(idx.toLong())?.take(12) ?: "$idx"
            "$idx($lbl)=${String.format(Locale.US, "%.2f", p)}"
        }
    }

    /**
     * Compares `audio_head_0(backboneHidden)` (direct Dual-AR `c_0`) vs `audio_head_0(ac(backboneHidden, pos=0))`
     * on frame 0 by Shannon entropy & top-1 probability. Whichever representation `audio_head_0` was trained on
     * produces a much sharper (lower entropy, higher confidence) distribution.
     */
    private fun calibrateC0Path(backboneHidden: FloatArray): Pair<Boolean, String> {
        val head0 = audioHeadSessions.firstOrNull() ?: return false to "c0=acoustic(no_head0)"
        val (directLogits, _) = runHeadGatherBatch(head0, backboneHidden, intArrayOf(0))
        val (directEnt, directMaxP) = computeEntropyAndMaxProb(directLogits)

        val (ac0Hidden, _) = runAcousticStepMulti(
            tokenEmbeds = backboneHidden,
            seqLen = 1,
            positions = longArrayOf(0L),
            pastKv = emptyMap()
        )
        val (acLogits, _) = runHeadGatherBatch(head0, ac0Hidden, intArrayOf(0))
        val (acEnt, acMaxP) = computeEntropyAndMaxProb(acLogits)

        // Pick direct Dual-AR c_0 if significantly sharper (lower entropy or higher peak prob)
        val useDirect = (directEnt + 0.25 < acEnt) || (directMaxP > acMaxP * 1.35 && directEnt <= acEnt + 0.1)
        val note = "c0Path=${if (useDirect) "DIRECT(h_t)" else "ACOUSTIC(pos=0)"} " +
            "[dir:H=${String.format(Locale.US, "%.2f", directEnt)},p=${String.format(Locale.US, "%.3f", directMaxP)} | " +
            "ac0:H=${String.format(Locale.US, "%.2f", acEnt)},p=${String.format(Locale.US, "%.3f", acMaxP)}]"
        return useDirect to note
    }

    private fun computeEntropyAndMaxProb(logits: FloatArray): Pair<Double, Double> {
        if (logits.isEmpty()) return 99.0 to 0.0
        var maxV = Float.NEGATIVE_INFINITY
        for (v in logits) if (v.isFinite() && v > maxV) maxV = v
        if (!maxV.isFinite()) return 99.0 to 0.0
        var sumExp = 0.0
        for (v in logits) if (v.isFinite()) sumExp += exp((v - maxV).toDouble())
        if (sumExp <= 0.0) return 99.0 to 0.0
        var entropy = 0.0
        var maxP = 0.0
        for (v in logits) {
            if (!v.isFinite()) continue
            val p = exp((v - maxV).toDouble()) / sumExp
            if (p > maxP) maxP = p
            if (p > 1e-12) entropy -= p * ln(p)
        }
        return entropy to maxP
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
        useDirectC0: Boolean,
        recentC0: List<Int>,
        rng: Random
    ): Pair<IntArray, FloatArray> {
        val codes = IntArray(numCodebooks)
        val summedEmb = FloatArray(hiddenSize)
        var acousticKv = LinkedHashMap<String, SafeTensorPayload>()

        if (useDirectC0 && audioHeadSessions.isNotEmpty()) {
            // Dual-AR CSM / Qwen3-TTS path:
            //   c_0 predicted directly from `backboneHidden` via `audio_head_0`
            //   c_1 predicted from `vieneu_acoustic_cached([backboneHidden, emb_0], pos=[0, 1])` via `audio_head_1`
            //   c_q (q=2..15) predicted from `vieneu_acoustic_cached([emb_{q-1}], pos=[q])` via `audio_head_q`
            val head0 = audioHeadSessions[0]
            val (logits0, _) = runHeadGatherBatch(head0, backboneHidden, intArrayOf(0))
            val c0 = VieNeuV3WeightStore.sampleLogits(
                logits = logits0,
                temperature = 0.72f,
                topK = 40,
                topP = 0.88f,
                maxValidIndex = (codebookSize - 1).coerceAtMost(logits0.size - 1),
                recentTokens = if (recentC0.isNotEmpty()) recentC0.toIntArray() else null,
                repetitionPenalty = 1.14f,
                rng = rng
            ).coerceIn(0, codebookSize - 1)
            codes[0] = c0

            val (_, emb0Raw) = runHeadGatherBatch(head0, zeroHiddenVec, intArrayOf(c0))
            var prevEmb = if (emb0Raw != null && emb0Raw.size >= hiddenSize) {
                emb0Raw.copyOf(hiddenSize)
            } else {
                FloatArray(hiddenSize)
            }
            for (d in 0 until hiddenSize) summedEmb[d] += prevEmb[d]

            for (q in 1 until numCodebooks) {
                val (acHidden, nextKv) = if (q == 1) {
                    val pairEmbeds = FloatArray(hiddenSize * 2)
                    System.arraycopy(backboneHidden, 0, pairEmbeds, 0, hiddenSize)
                    System.arraycopy(prevEmb, 0, pairEmbeds, hiddenSize, hiddenSize)
                    runAcousticStepMulti(
                        tokenEmbeds = pairEmbeds,
                        seqLen = 2,
                        positions = longArrayOf(0L, 1L),
                        pastKv = acousticKv
                    )
                } else {
                    runAcousticStepMulti(
                        tokenEmbeds = prevEmb,
                        seqLen = 1,
                        positions = longArrayOf(q.toLong()),
                        pastKv = acousticKv
                    )
                }
                acousticKv = nextKv

                val headSession = audioHeadSessions.getOrNull(q) ?: continue
                val (logits, _) = runHeadGatherBatch(headSession, acHidden, intArrayOf(0))
                val sampled = VieNeuV3WeightStore.sampleLogits(
                    logits = logits,
                    temperature = 0.55f,
                    topK = 24,
                    topP = 0.88f,
                    maxValidIndex = (codebookSize - 1).coerceAtMost(logits.size - 1),
                    recentTokens = null,
                    repetitionPenalty = 1.0f,
                    rng = rng
                ).coerceIn(0, codebookSize - 1)
                codes[q] = sampled

                val (_, gatheredEmb) = runHeadGatherBatch(headSession, zeroHiddenVec, intArrayOf(sampled))
                if (gatheredEmb != null && gatheredEmb.size >= hiddenSize) {
                    prevEmb = if (gatheredEmb.size == hiddenSize) gatheredEmb else gatheredEmb.copyOf(hiddenSize)
                    for (d in 0 until hiddenSize) summedEmb[d] += prevEmb[d]
                }
            }
            return codes to summedEmb
        }

        // Standard RQ-Transformer path: `vieneu_acoustic_cached` runs for q = 0..15 starting from `backboneHidden`
        var currentInputEmb = backboneHidden
        for (q in 0 until numCodebooks) {
            val (acHidden, nextKv) = runAcousticStepMulti(
                tokenEmbeds = currentInputEmb,
                seqLen = 1,
                positions = longArrayOf(q.toLong()),
                pastKv = acousticKv
            )
            acousticKv = nextKv

            val headSession = audioHeadSessions.getOrNull(q)
            if (headSession != null) {
                val (logits, _) = runHeadGatherBatch(headSession, acHidden, intArrayOf(0))
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

                val (_, gatheredEmb) = runHeadGatherBatch(headSession, zeroHiddenVec, intArrayOf(sampled))
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

    private fun runAcousticStepMulti(
        tokenEmbeds: FloatArray,
        seqLen: Int,
        positions: LongArray,
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
                        val dims = if (shape != null && shape.size == 1) {
                            longArrayOf(seqLen.toLong())
                        } else {
                            longArrayOf(1L, seqLen.toLong())
                        }
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(
                                env,
                                IntBuffer.wrap(IntArray(seqLen) { positions[it].toInt() }),
                                dims
                            )
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(positions), dims)
                        }
                    }
                    lower.contains("emb") || lower.contains("hidden") || lower.contains("input") -> {
                        val dims = when (shape?.size) {
                            2 -> longArrayOf(seqLen.toLong(), hiddenSize.toLong())
                            else -> longArrayOf(1L, seqLen.toLong(), hiddenSize.toLong())
                        }
                        VieNeuV3WeightStore.createFloatOrFp16Tensor(env, tokenEmbeds, dims, type)
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

    private fun vectorNorm(v: FloatArray): Float {
        var sq = 0.0
        for (x in v) if (x.isFinite()) sq += x * x
        return sqrt(sq).toFloat()
    }

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
