package com.example.audio

import android.util.Log
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

enum class VieNeuPromptLayout {
    BOS_STYLE_XVEC_REF_TEXT,
    BOS_XVEC_REF_TEXT,
    BOS_REF_STYLE_TEXT,
    BOS_TEXT_XVEC_REF,
    BOS_TEXT_STYLE_XVEC_REF,
    BOS_STYLE_EMO_XVEC_REF_TEXT,
    BOS_XVEC_ONLY_TEXT
}

enum class VieNeuAcousticMode {
    /** RQ-Transformer: `ac(h_t, pos=0) -> head_0 -> c_0`, `ac(emb_0, pos=1) -> head_1 -> c_1`, ... */
    AC_ALL_16,
    /** Dual-AR with h_t in local KV: `head_0(h_t) -> c_0`, `ac([h_t, emb_0], pos=[0,1]) -> head_1 -> c_1`, ... */
    DIRECT_C0_PAIR,
    /** Dual-AR without h_t in local KV: `head_0(h_t) -> c_0`, `ac(emb_0, pos=0) -> head_1 -> c_1`, ... */
    DIRECT_C0_SOLO
}

data class VieNeuCalibrationProfile(
    val promptLayout: VieNeuPromptLayout,
    val refSlotAddBias: Boolean,
    val audioEmbedScale: Float,
    val acousticMode: VieNeuAcousticMode,
    val diagnosticSummary: String
)

/**
 * Self-calibrates VieNeu-TTS v3 Turbo's exact inference contract directly against the loaded ONNX weights
 * (`vieneu_prefill.onnx`, `text_head_gather.onnx`, `vieneu_acoustic_cached.onnx`, `audio_head_0..15.onnx`)
 * and real ground-truth `referenceCodes` / `speakerEmbedding` presets from `voices.json`.
 *
 * Runs once per app session (~250–400 ms total) with zero guesswork:
 * 1. Measures embedding L2 norms (`text_emb`, `xvecCond`, `audio_head_0`, `sum16(audio_head)`).
 * 2. Probes `text_head_gather` next-token probabilities after `<|bos|>`, `<|style_0|>`, `<|audio_ref_slot|>`,
 *    `<|TEXT_PROMPT_START|>`, and `<|TEXT_PROMPT_END|>` to read the model's trained prompt grammar.
 * 3. Teacher-forces real `voices.json` `referenceCodes[0..11]` across embedding scales (`1.0`, `1/4`, `1/16`)
 *    and all 3 `VieNeuAcousticMode`s, choosing the exact `(scale, acousticMode)` with lowest ground-truth
 *    negative log-likelihood (NLL) on `c_0` + `c_1`.
 * 4. Scores candidate `VieNeuPromptLayout`s on `<|SPEECH_GENERATION_START|>` likelihood & `c_0` sharpness.
 */
object VieNeuV3Calibration {
    private const val TAG = "VieNeuV3Calibration"

    fun calibrate(runner: VieNeuV3OnnxRunner): VieNeuCalibrationProfile {
        val sb = StringBuilder()
        val preset = runner.firstPresetWithCodes()

        // 1. Inspect L2 norms of text_emb, xvec, single audio head emb, and 16-head sum
        val textSampleNorms = listOf(1L, 3L, 5L, 7L, 16L, 100L, 305L).map {
            runner.vectorNormPublic(runner.lookupSingleTextEmbeddingPublic(it))
        }
        val avgTextNorm = textSampleNorms.average().toFloat()
        val refSlotNorm = runner.vectorNormPublic(runner.lookupSingleTextEmbeddingPublic(runner.audioRefSlotTokenId))
        val xvec768 = preset?.speakerEmbedding?.let { runner.buildSpeakerConditioningEmbeddingPublic(it) }
        val xvecNorm = xvec768?.let { runner.vectorNormPublic(it) } ?: 0f

        var singleHeadNorm = 0f
        var sum16Norm = 0f
        val refCodes = preset?.refCodes
        if (refCodes != null && refCodes.size >= 14) {
            val raw16 = runner.embedReferenceAudioFramesBatchedPublic(refCodes.copyOfRange(0, 14), scale = 1.0f)
            if (raw16.isNotEmpty()) sum16Norm = runner.vectorNormPublic(raw16[0])
            val h0Emb = runner.gatherSingleAudioHeadEmbPublic(0, refCodes[0][0])
            if (h0Emb != null) singleHeadNorm = runner.vectorNormPublic(h0Emb)
        }
        sb.append(
            "norms[text=${fmt(avgTextNorm)}, refSlot=${fmt(refSlotNorm)}, xvec=${fmt(xvecNorm)}, " +
                "head0=${fmt(singleHeadNorm)}, sum16=${fmt(sum16Norm)}]"
        )

        // 2. Causal next-token prompt grammar probes via text_head_gather
        val afterBos = probeNextTokens(runner, listOf(runner.bosTokenId))
        val afterStyle = probeNextTokens(runner, listOf(runner.bosTokenId, runner.defaultStyleTokenId))
        val afterRefSlot = probeNextTokens(runner, listOf(runner.bosTokenId, runner.audioRefSlotTokenId))
        val afterTextStart = probeNextTokens(runner, listOf(runner.bosTokenId, runner.textStartTokenId))
        val sampleTextIds = runner.encodeSampleTextIds("một ngày mưa")
        val afterTextEndPlain = probeNextTokens(
            runner,
            buildList {
                add(runner.bosTokenId)
                add(runner.textStartTokenId)
                addAll(sampleTextIds)
                add(runner.textEndTokenId)
            }
        )
        sb.append(
            " | grammar[bos->$afterBos ; bos+style->$afterStyle ; bos+refSlot->$afterRefSlot ; " +
                "bos+txtStart->$afterTextStart ; bos+txtEnd->$afterTextEndPlain]"
        )

        // 3. Ground-truth teacher-forced calibration on real voices.json referenceCodes[0..11]
        var bestScale = 1.0f
        var bestAcousticMode = VieNeuAcousticMode.AC_ALL_16
        var bestJointNll = Double.POSITIVE_INFINITY
        val tfNotes = ArrayList<String>()

        if (refCodes != null && refCodes.size >= 14) {
            val evalFrames = 10 // evaluate predicting frames 1..10 from prefix [SPEECH_START, frame 0..9]
            val evalSlice = refCodes.copyOfRange(0, evalFrames + 1)
            val candidateScales = listOf(1.0f to "1", 0.25f to "1/4", (1f / 16f) to "1/16")

            for ((scale, scaleLabel) in candidateScales) {
                val frameEmbeds = runner.embedReferenceAudioFramesBatchedPublic(evalSlice, scale = scale)
                if (frameEmbeds.size < evalFrames + 1) continue

                // Sequence: [SPEECH_GENERATION_START, refFrame[0], ..., refFrame[evalFrames-1]]
                // Position t (0..evalFrames-1) predicts evalSlice[t] (at t=0 -> frame 0, t=1 -> frame 1, ...)
                val seqVecs = ArrayList<FloatArray>(evalFrames + 1)
                seqVecs.add(runner.lookupSingleTextEmbeddingPublic(runner.speechStartTokenId))
                for (t in 0 until evalFrames) {
                    seqVecs.add(frameEmbeds[t])
                }
                val allHidden = runner.runBackbonePrefillAllHiddenPublic(seqVecs)
                if (allHidden.size < (evalFrames + 1) * runner.hiddenSizePublic) continue

                var nllC0Direct = 0.0
                var nllC0Ac0 = 0.0
                var nllC1AcAll = 0.0
                var nllC1Pair = 0.0
                var nllC1Solo = 0.0
                var count = 0

                // Evaluate frames 2..evalFrames (skip frame 0 which has no text prompt context)
                for (t in 2 until evalFrames) {
                    val h = allHidden.copyOfRange(t * runner.hiddenSizePublic, (t + 1) * runner.hiddenSizePublic)
                    val gtC0 = evalSlice[t][0]
                    val gtC1 = evalSlice[t][1]

                    // Direct c_0: audio_head_0(h)
                    val dirLogits0 = runner.runAudioHeadLogitsPublic(0, h)
                    nllC0Direct += negativeLogProb(dirLogits0, gtC0)

                    // AC_ALL_16 c_0 & c_1: ac(h, pos=0) -> head_0, then ac(emb(gtC0), pos=1) -> head_1
                    val (ac0H, ac0Kv) = runner.runAcousticStepMultiPublic(h, 1, longArrayOf(0L), emptyMap())
                    val acLogits0 = runner.runAudioHeadLogitsPublic(0, ac0H)
                    nllC0Ac0 += negativeLogProb(acLogits0, gtC0)

                    val embGtC0 = runner.gatherSingleAudioHeadEmbPublic(0, gtC0) ?: FloatArray(runner.hiddenSizePublic)
                    val (ac1AllH, _) = runner.runAcousticStepMultiPublic(embGtC0, 1, longArrayOf(1L), ac0Kv)
                    val ac1AllLogits = runner.runAudioHeadLogitsPublic(1, ac1AllH)
                    nllC1AcAll += negativeLogProb(ac1AllLogits, gtC1)

                    // DIRECT_C0_PAIR c_1: ac([h, emb(gtC0)], pos=[0,1]) -> head_1
                    val pair = FloatArray(runner.hiddenSizePublic * 2)
                    System.arraycopy(h, 0, pair, 0, runner.hiddenSizePublic)
                    System.arraycopy(embGtC0, 0, pair, runner.hiddenSizePublic, runner.hiddenSizePublic)
                    val (ac1PairH, _) = runner.runAcousticStepMultiPublic(pair, 2, longArrayOf(0L, 1L), emptyMap())
                    val ac1PairLogits = runner.runAudioHeadLogitsPublic(1, ac1PairH)
                    nllC1Pair += negativeLogProb(ac1PairLogits, gtC1)

                    // DIRECT_C0_SOLO c_1: ac([emb(gtC0)], pos=[0]) -> head_1
                    val (ac1SoloH, _) = runner.runAcousticStepMultiPublic(embGtC0, 1, longArrayOf(0L), emptyMap())
                    val ac1SoloLogits = runner.runAudioHeadLogitsPublic(1, ac1SoloH)
                    nllC1Solo += negativeLogProb(ac1SoloLogits, gtC1)

                    count++
                }

                if (count > 0) {
                    val mC0Dir = nllC0Direct / count
                    val mC0Ac = nllC0Ac0 / count
                    val mC1All = nllC1AcAll / count
                    val mC1Pair = nllC1Pair / count
                    val mC1Solo = nllC1Solo / count

                    val candidates = listOf(
                        VieNeuAcousticMode.AC_ALL_16 to (mC0Ac + mC1All),
                        VieNeuAcousticMode.DIRECT_C0_PAIR to (mC0Dir + mC1Pair),
                        VieNeuAcousticMode.DIRECT_C0_SOLO to (mC0Dir + mC1Solo)
                    )
                    for ((mode, joint) in candidates) {
                        if (joint < bestJointNll) {
                            bestJointNll = joint
                            bestScale = scale
                            bestAcousticMode = mode
                        }
                    }
                    tfNotes.add(
                        "s=$scaleLabel(acAll=${fmt(mC0Ac)}+${fmt(mC1All)}, " +
                            "dirPair=${fmt(mC0Dir)}+${fmt(mC1Pair)}, dirSolo=${fmt(mC0Dir)}+${fmt(mC1Solo)})"
                    )
                }
            }
        }
        sb.append(" | teacherForce[${tfNotes.joinToString(" | ")} => BEST scale=${fmt(bestScale)}, mode=$bestAcousticMode]")

        // 4. Score candidate prompt layouts using text_head P(SPEECH_START) & c_0 entropy at speech start
        var bestLayout = VieNeuPromptLayout.BOS_STYLE_XVEC_REF_TEXT
        var bestRefAddBias = false
        var bestLayoutScore = Double.NEGATIVE_INFINITY
        val layoutNotes = ArrayList<String>()

        val shortRefCodes = refCodes?.let { it.copyOfRange(0, minOf(12, it.size)) }
        val rawRefEmbeds = if (shortRefCodes != null) {
            runner.embedReferenceAudioFramesBatchedPublic(shortRefCodes, scale = bestScale)
        } else {
            emptyList()
        }

        val layoutCandidates = listOf(
            VieNeuPromptLayout.BOS_STYLE_XVEC_REF_TEXT to false,
            VieNeuPromptLayout.BOS_STYLE_XVEC_REF_TEXT to true,
            VieNeuPromptLayout.BOS_XVEC_REF_TEXT to false,
            VieNeuPromptLayout.BOS_REF_STYLE_TEXT to false,
            VieNeuPromptLayout.BOS_TEXT_XVEC_REF to false,
            VieNeuPromptLayout.BOS_TEXT_STYLE_XVEC_REF to false,
            VieNeuPromptLayout.BOS_STYLE_EMO_XVEC_REF_TEXT to false,
            VieNeuPromptLayout.BOS_XVEC_ONLY_TEXT to false
        )

        for ((layout, addBias) in layoutCandidates) {
            val vecs = runner.assemblePromptVectorsPublic(
                layout = layout,
                refSlotAddBias = addBias,
                xvecCond768 = xvec768,
                refFrameEmbeds = rawRefEmbeds,
                contentIds = sampleTextIds
            )
            if (vecs.size < 3) continue
            val allH = runner.runBackbonePrefillAllHiddenPublic(vecs)
            val dim = runner.hiddenSizePublic
            if (allH.size < vecs.size * dim) continue

            // Hidden right BEFORE <|SPEECH_GENERATION_START|> (index vecs.size - 2)
            val preSpeechH = allH.copyOfRange((vecs.size - 2) * dim, (vecs.size - 1) * dim)
            val pSpeechStart = runner.probOfTextTokenPublic(preSpeechH, runner.speechStartTokenId.toInt())

            // Hidden AT <|SPEECH_GENERATION_START|> (last token, index vecs.size - 1)
            val speechStartH = allH.copyOfRange((vecs.size - 1) * dim, vecs.size * dim)
            val c0Logits = when (bestAcousticMode) {
                VieNeuAcousticMode.AC_ALL_16 -> {
                    val (acH, _) = runner.runAcousticStepMultiPublic(speechStartH, 1, longArrayOf(0L), emptyMap())
                    runner.runAudioHeadLogitsPublic(0, acH)
                }
                else -> runner.runAudioHeadLogitsPublic(0, speechStartH)
            }
            val (c0Ent, c0MaxP) = computeEntropyAndMaxProb(c0Logits)
            // Higher P(SPEECH_START) and lower c_0 entropy (sharper speech onset) = better match to training format
            val score = pSpeechStart * 3.0 + c0MaxP * 2.0 - c0Ent * 0.5
            layoutNotes.add(
                "${layout.name}(bias=$addBias):pSS=${fmt(pSpeechStart)},H0=${fmt(c0Ent)},p0=${fmt(c0MaxP)}"
            )
            if (score > bestLayoutScore) {
                bestLayoutScore = score
                bestLayout = layout
                bestRefAddBias = addBias
            }
        }
        sb.append(" | layouts[${layoutNotes.joinToString(" ; ")} => BEST $bestLayout(bias=$bestRefAddBias)]")

        val summary = sb.toString()
        Log.i(TAG, "VieNeuV3 Calibration complete: $summary")
        return VieNeuCalibrationProfile(
            promptLayout = bestLayout,
            refSlotAddBias = bestRefAddBias,
            audioEmbedScale = bestScale,
            acousticMode = bestAcousticMode,
            diagnosticSummary = summary
        )
    }

    private fun probeNextTokens(runner: VieNeuV3OnnxRunner, tokenIds: List<Long>): String {
        val vecs = tokenIds.map { runner.lookupSingleTextEmbeddingPublic(it) }
        val allH = runner.runBackbonePrefillAllHiddenPublic(vecs)
        val dim = runner.hiddenSizePublic
        if (allH.size < dim) return "err"
        val lastH = allH.copyOfRange(allH.size - dim, allH.size)
        return runner.inspectTextHeadTop3Public(lastH)
    }

    private fun negativeLogProb(logits: FloatArray, targetIdx: Int): Double {
        if (logits.isEmpty() || targetIdx !in logits.indices) return 6.9315
        var maxV = Float.NEGATIVE_INFINITY
        for (v in logits) if (v.isFinite() && v > maxV) maxV = v
        if (!maxV.isFinite()) return 6.9315
        var sumExp = 0.0
        for (v in logits) if (v.isFinite()) sumExp += exp((v - maxV).toDouble())
        if (sumExp <= 0.0) return 6.9315
        val tv = logits[targetIdx]
        if (!tv.isFinite()) return 6.9315
        return (ln(sumExp) - (tv - maxV).toDouble()).coerceIn(0.0, 20.0)
    }

    private fun computeEntropyAndMaxProb(logits: FloatArray): Pair<Double, Double> {
        if (logits.isEmpty()) return 6.93 to 0.0
        var maxV = Float.NEGATIVE_INFINITY
        for (v in logits) if (v.isFinite() && v > maxV) maxV = v
        if (!maxV.isFinite()) return 6.93 to 0.0
        var sumExp = 0.0
        for (v in logits) if (v.isFinite()) sumExp += exp((v - maxV).toDouble())
        if (sumExp <= 0.0) return 6.93 to 0.0
        var ent = 0.0
        var maxP = 0.0
        for (v in logits) {
            if (!v.isFinite()) continue
            val p = exp((v - maxV).toDouble()) / sumExp
            if (p > maxP) maxP = p
            if (p > 1e-12) ent -= p * ln(p)
        }
        return ent to maxP
    }

    private fun fmt(v: Number): String = String.format(Locale.US, "%.2f", v.toDouble())
}
