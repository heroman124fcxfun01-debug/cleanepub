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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

data class VieNeuV3SynthesisOutput(
    val waveform: FloatArray?,
    val sampleRate: Int,
    val traceLog: String,
    val errorMessage: String? = null
)

/**
 * Full Dual-AR / RQ-Transformer ONNX inference runner for **VieNeu-TTS v3 Turbo + MOSS-Audio-Tokenizer-Nano (48 kHz)**.
 *
 * Dynamically adapts to the exact tensor signatures of:
 * - `vieneu_prefill.onnx` (`inputs_embeds: [1, T, 768]` -> `hidden`, `present_k_0..11`, `present_v_0..11`)
 * - `vieneu_decode_step.onnx` (`inputs_embeds: [1, 1, 768]`, `past_k_0..11`, `past_v_0..11` -> `hidden`, `present_k_0..11`, `present_v_0..11`)
 * - `vieneu_acoustic_cached.onnx` (`token_emb: [1, 1, 768]`, `position_ids: [1, 1]`, `past_k_0`, `past_v_0` -> `hidden`, `present_k_0`, `present_v_0`)
 * - `text_head_gather.onnx` (text embedding lookup / text projection)
 * - `audio_head_0.onnx` .. `audio_head_15.onnx` (16 RVQ codebook heads/embeddings)
 * - `heads.json` + `heads.bin` (`speaker_proj`, `text_embed`, `audio_embed`, `norm`, `stop_head`, etc.)
 * - `moss_audio_tokenizer_decode_full.onnx` (`audio_codes: INT32[1, frames, 16]`, `audio_code_lengths: INT32[1]` -> `audio: FLOAT[1, 1, N]`, `audio_lengths: INT32[1]`)
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

    // Pre-resolved weight keys inside `heads.bin` (if present)
    private val textEmbedKey: String? = weightStore?.findKey(
        "text_embed", "text_embeddings", "embed_tokens", "text_emb", "wte"
    )
    private val speakerProjWKey: String? = weightStore?.findKey(
        "speaker_proj.weight", "spk_proj.weight", "speaker_proj", "spk_proj", "speaker_fc.weight", "speaker_mlp.0.weight"
    )
    private val speakerProjBKey: String? = weightStore?.findKey(
        "speaker_proj.bias", "spk_proj.bias", "speaker_fc.bias", "speaker_mlp.0.bias"
    )
    private val speakerProjW2Key: String? = weightStore?.findKey(
        "speaker_mlp.2.weight", "speaker_proj.2.weight", "speaker_fc2.weight"
    )
    private val speakerProjB2Key: String? = weightStore?.findKey(
        "speaker_mlp.2.bias", "speaker_proj.2.bias", "speaker_fc2.bias"
    )
    private val stopHeadWKey: String? = weightStore?.findKey(
        "stop_head.weight", "eos_head.weight", "stop_proj.weight", "stop_head", "eos_head"
    )
    private val stopHeadBKey: String? = weightStore?.findKey(
        "stop_head.bias", "eos_head.bias", "stop_proj.bias"
    )
    private val backboneNormKey: String? = weightStore?.findKey(
        "backbone_norm", "final_norm", "norm.weight"
    )
    private val acousticNormKey: String? = weightStore?.findKey(
        "acoustic_norm", "depth_norm"
    )

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
            // 1. Phonemize & encode text to token IDs
            val (tokenIds, phonemeStr) = VietnameseIpaPhonemizer.encodeTextForVieNeuV3(
                cleanText = cleanText,
                tokenizer = tokenizer,
                config = config
            )
            trace.appendLine("1. G2P/Tokens: ${tokenIds.size} token | IPA=\"${phonemeStr.take(90)}\"")
            if (tokenIds.isEmpty()) {
                return VieNeuV3SynthesisOutput(null, config.outputSampleRate, trace.toString(), "Chuỗi token văn bản rỗng.")
            }

            // 2. Build text embeddings `[textLen, hiddenSize]` + optional speaker conditioning prefix
            val textEmbeds = lookupTextEmbeddings(tokenIds)
            val spkPrefix = buildSpeakerPrefixEmbedding(speakerEmbedding)
            val promptLen = tokenIds.size + (if (spkPrefix != null) 1 else 0)
            val promptEmbeds = FloatArray(promptLen * hiddenSize)
            var writeOff = 0
            if (spkPrefix != null) {
                System.arraycopy(spkPrefix, 0, promptEmbeds, 0, hiddenSize)
                writeOff += hiddenSize
            }
            System.arraycopy(textEmbeds, 0, promptEmbeds, writeOff, textEmbeds.size)

            trace.appendLine("2. Prompt embeds: [1, $promptLen, $hiddenSize] (spkPrefix=${spkPrefix != null}, headsBin=${weightStore?.entries?.size ?: 0})")

            // 3. Backbone Prefill (`vieneu_prefill.onnx`)
            var (backboneHidden, backboneKv) = runBackbonePrefill(promptEmbeds, promptLen)
            trace.appendLine("3. Prefill OK: hidden[${backboneHidden.size}], kv=${backboneKv.size} tensors")

            // 4. Autoregressive Frame Generation Loop (Backbone + Acoustic RQ-Transformer)
            // MOSS-Audio-Tokenizer-Nano runs at 12.5 Hz (80ms per frame) @ 48kHz.
            // Vietnamese speech averages ~4-5 syllables/sec (~3 frames per syllable).
            val wordCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
            val minFrames = (wordCount * 1.4f).roundToInt().coerceIn(8, 36)
            val maxFrames = ((wordCount * 4.8f) / speed.coerceIn(0.75f, 1.5f))
                .roundToInt()
                .coerceIn(32, 260)

            val generatedFrames = ArrayList<IntArray>(maxFrames)
            val recentC0 = ArrayList<Int>(32)
            val rng = Random(cleanText.hashCode() xor speakerEmbedding.contentHashCode())
            var backbonePos = promptLen
            var stopReason = "max_frames"
            var repeatRun = 0
            var prevC0 = -1

            for (frameIdx in 0 until maxFrames) {
                val normedBackbone = applyOptionalNorm(backboneHidden, backboneNormKey)

                // Check dedicated binary/linear stop head if present in heads.bin
                if (frameIdx >= minFrames && shouldStopByStopHead(normedBackbone)) {
                    stopReason = "stop_head@$frameIdx"
                    break
                }

                // Run 1-layer Acoustic Depth Transformer (`vieneu_acoustic_cached.onnx`) + `audio_head_0..15`
                val (frameCodes, hitAudioEos) = decodeSingleAcousticFrame(
                    backboneHidden = normedBackbone,
                    allowEos = frameIdx >= minFrames,
                    recentC0 = recentC0,
                    rng = rng
                )

                if (hitAudioEos) {
                    stopReason = "audio_eos@$frameIdx"
                    break
                }

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

                // Prepare next backbone step embedding = sum of all 16 codebook embeddings
                val nextStepEmb = sumAudioCodebookEmbeddings(frameCodes)
                val (nextHidden, nextKv) = runBackboneDecodeStep(
                    stepEmbed = nextStepEmb,
                    position = backbonePos,
                    pastKv = backboneKv
                )
                backboneHidden = nextHidden
                backboneKv = nextKv
                backbonePos++
            }

            trace.appendLine("4. Acoustic + Decode OK: ${generatedFrames.size} frames (stop=$stopReason, ~${String.format(Locale.US, "%.2f", generatedFrames.size * 0.08f)}s)")
            if (generatedFrames.isEmpty()) {
                return VieNeuV3SynthesisOutput(
                    waveform = null,
                    sampleRate = config.outputSampleRate,
                    traceLog = trace.toString(),
                    errorMessage = "Backbone + Acoustic không sinh ra frame âm thanh nào."
                )
            }

            // 5. Decode 16-codebook RVQ frames into 48kHz PCM via `moss_audio_tokenizer_decode_full.onnx`
            val rawAudio = runMossCodecDecodeFull(generatedFrames)
            val elapsedMs = System.currentTimeMillis() - startMs
            trace.appendLine("5. MOSS Decode OK: ${rawAudio.size} samples @ ${config.outputSampleRate} Hz (${elapsedMs}ms)")

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
    // Stage 1: Text & Speaker Embedding Construction
    // -------------------------------------------------------------------------

    private fun lookupTextEmbeddings(tokenIds: LongArray): FloatArray {
        val count = tokenIds.size
        // Priority 1: `text_head_gather.onnx` if it accepts integer indices and outputs [..., hiddenSize]
        val gatherSession = textHeadGatherSession
        if (gatherSession != null) {
            val inEntry = gatherSession.inputInfo.entries.firstOrNull()
            val outEntry = gatherSession.outputInfo.entries.firstOrNull()
            val inInfo = inEntry?.value?.info as? TensorInfo
            val outInfo = outEntry?.value?.info as? TensorInfo
            val inIsInt = inInfo?.type == OnnxJavaType.INT64 || inInfo?.type == OnnxJavaType.INT32
            val outLastDim = outInfo?.shape?.lastOrNull()?.toInt()
            if (inIsInt && (outLastDim == null || outLastDim <= 0 || outLastDim == hiddenSize)) {
                return runGatherOnnxSession(gatherSession, tokenIds)
            }
        }

        // Priority 2: Direct lookup table inside `heads.bin`
        if (textEmbedKey != null && weightStore != null) {
            val table = weightStore.getFloats(textEmbedKey)
            val meta = weightStore.getMeta(textEmbedKey)
            if (table != null && meta != null && meta.shape.size >= 2) {
                val vocabRows = meta.shape[0].coerceAtLeast(1)
                val dim = meta.shape[1].coerceAtLeast(1)
                val out = FloatArray(count * hiddenSize)
                val copyDim = minOf(dim, hiddenSize)
                for (i in 0 until count) {
                    val row = tokenIds[i].toInt().coerceIn(0, vocabRows - 1)
                    System.arraycopy(table, row * dim, out, i * hiddenSize, copyDim)
                }
                return out
            }
        }

        // Priority 3: Deterministic sinusoidal projection fallback (only if neither table exists)
        val out = FloatArray(count * hiddenSize)
        for (i in 0 until count) {
            val id = tokenIds[i].toFloat()
            val base = i * hiddenSize
            for (d in 0 until hiddenSize) {
                out[base + d] = (kotlin.math.sin((id + 1f) * (d + 1f) * 0.013f) * 0.05f)
            }
        }
        return out
    }

    private fun runGatherOnnxSession(session: OrtSession, ids: LongArray): FloatArray {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in session.inputInfo) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                val type = tInfo?.type ?: OnnxJavaType.INT64
                val dims = when {
                    shape != null && shape.size == 1 -> longArrayOf(ids.size.toLong())
                    shape != null && shape.size == 2 -> longArrayOf(1L, ids.size.toLong())
                    else -> longArrayOf(ids.size.toLong())
                }
                inputs[name] = if (type == OnnxJavaType.INT32) {
                    OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(ids.size) { ids[it].toInt() }), dims)
                } else {
                    OnnxTensor.createTensor(env, LongBuffer.wrap(ids), dims)
                }
            }
            session.run(inputs).use { res ->
                val outTensor = res[0] as OnnxTensor
                val flat = VieNeuV3WeightStore.readTensorAsFloatsSafe(outTensor)
                val expected = ids.size * hiddenSize
                return if (flat.size == expected) flat else FloatArray(expected) { i ->
                    if (i < flat.size) flat[i] else 0f
                }
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun buildSpeakerPrefixEmbedding(speakerEmbedding: FloatArray): FloatArray? {
        if (speakerEmbedding.isEmpty()) return null
        val store = weightStore ?: return null
        val wKey = speakerProjWKey ?: return null
        val w = store.getFloats(wKey) ?: return null
        val wMeta = store.getMeta(wKey) ?: return null
        val b = speakerProjBKey?.let { store.getFloats(it) }

        val inDim = if (wMeta.shape.size >= 2) {
            if (wMeta.shape[1] == speakerEmbedding.size) wMeta.shape[1]
            else if (wMeta.shape[0] == speakerEmbedding.size) wMeta.shape[0]
            else wMeta.shape[1]
        } else {
            speakerEmbedding.size
        }
        val fittedSpk = if (speakerEmbedding.size == inDim) speakerEmbedding else FloatArray(inDim) { i ->
            if (i < speakerEmbedding.size) speakerEmbedding[i] else 0f
        }
        var h = VieNeuV3WeightStore.linearProject(fittedSpk, w, wMeta.shape, b)

        // Optional 2nd layer MLP (SiLU + Linear)
        val w2Key = speakerProjW2Key
        if (w2Key != null) {
            val w2 = store.getFloats(w2Key)
            val w2Meta = store.getMeta(w2Key)
            val b2 = speakerProjB2Key?.let { store.getFloats(it) }
            if (w2 != null && w2Meta != null) {
                VieNeuV3WeightStore.siluInPlace(h)
                h = VieNeuV3WeightStore.linearProject(h, w2, w2Meta.shape, b2)
            }
        }

        if (h.size != hiddenSize) {
            return FloatArray(hiddenSize) { i -> if (i < h.size) h[i] else 0f }
        }
        return h
    }

    // -------------------------------------------------------------------------
    // Stage 2: Backbone Prefill & Decode-Step (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`)
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
                    lower.contains("embed") || lower.contains("input") && type in setOf(OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16) -> {
                        VieNeuV3WeightStore.createFloatOrFp16Tensor(
                            env = env,
                            floats = promptEmbeds,
                            shape = longArrayOf(1L, seqLen.toLong(), hiddenSize.toLong()),
                            expectedType = type
                        )
                    }
                    lower.contains("position") || lower == "pos" -> {
                        val pos = LongArray(seqLen) { it.toLong() }
                        val dims = if (shape != null && shape.size == 1) longArrayOf(seqLen.toLong()) else longArrayOf(1L, seqLen.toLong())
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(seqLen) { it }), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(pos), dims)
                        }
                    }
                    lower.contains("mask") -> {
                        val dims = longArrayOf(1L, seqLen.toLong())
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(seqLen) { 1 }), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seqLen) { 1L }), dims)
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
                            val dims = defaultZeroKvShape(shape)
                            VieNeuV3WeightStore.createEmptyTypedTensor(env, dims, type)
                        }
                    }
                    lower.contains("embed") || lower.contains("hidden") ||
                        (type in setOf(OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16) && shape != null && shape.lastOrNull() == hiddenSize.toLong()) -> {
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
                    lower.contains("mask") -> {
                        val totalLen = position + 1
                        val dims = longArrayOf(1L, totalLen.toLong())
                        if (type == OnnxJavaType.INT32) {
                            OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(totalLen) { 1 }), dims)
                        } else {
                            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(totalLen) { 1L }), dims)
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
    // Stage 3: Acoustic Depth Transformer (`vieneu_acoustic_cached.onnx` + `audio_head_0..15.onnx`)
    // -------------------------------------------------------------------------

    private fun decodeSingleAcousticFrame(
        backboneHidden: FloatArray,
        allowEos: Boolean,
        recentC0: List<Int>,
        rng: Random
    ): Pair<IntArray, Boolean> {
        val codes = IntArray(numCodebooks)
        var acousticKv = LinkedHashMap<String, SafeTensorPayload>()
        var currentEmb = backboneHidden

        for (q in 0 until numCodebooks) {
            val (acHidden, nextKv) = runAcousticStep(
                tokenEmb = currentEmb,
                depthPos = q,
                pastKv = acousticKv
            )
            acousticKv = nextKv

            val normedAc = applyOptionalNorm(acHidden, acousticNormKey)
            val logits = computeAudioHeadLogits(q, normedAc)

            val eosId = config.audioEosTokenId?.toInt()
                ?: if (logits.size > codebookSize) codebookSize else -1

            val maxValid = if (q == 0 && allowEos && eosId in 0 until logits.size) {
                logits.size - 1
            } else {
                (codebookSize - 1).coerceAtMost(logits.size - 1)
            }

            val sampled = VieNeuV3WeightStore.sampleLogits(
                logits = logits,
                temperature = if (q == 0) 0.72f else 0.55f,
                topK = if (q == 0) 40 else 25,
                topP = 0.88f,
                maxValidIndex = maxValid,
                recentTokens = if (q == 0 && recentC0.isNotEmpty()) recentC0.toIntArray() else null,
                repetitionPenalty = if (q == 0) 1.15f else 1.0f,
                rng = rng
            )

            if (q == 0 && allowEos && eosId >= 0 && sampled >= codebookSize) {
                return codes to true
            }

            val clampedCode = sampled.coerceIn(0, codebookSize - 1)
            codes[q] = clampedCode

            if (q + 1 < numCodebooks) {
                currentEmb = lookupAudioCodebookEmbedding(q, clampedCode)
            }
        }
        return codes to false
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
                            // Initial step: [1, 8, 0, 96]
                            val dims = defaultZeroKvShape(shape)
                            VieNeuV3WeightStore.createEmptyTypedTensor(env, dims, type)
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

    private fun computeAudioHeadLogits(codebookIdx: Int, hidden: FloatArray): FloatArray {
        val headSession = audioHeadSessions.getOrNull(codebookIdx)
        if (headSession != null) {
            val firstIn = headSession.inputInfo.values.firstOrNull()?.info as? TensorInfo
            if (firstIn == null || firstIn.type == OnnxJavaType.FLOAT || firstIn.type == OnnxJavaType.FLOAT16) {
                val inputs = LinkedHashMap<String, OnnxTensor>()
                try {
                    for ((name, nodeInfo) in headSession.inputInfo) {
                        val tInfo = nodeInfo.info as? TensorInfo
                        val shape = tInfo?.shape
                        val type = tInfo?.type ?: OnnxJavaType.FLOAT
                        val dims = when (shape?.size) {
                            3 -> longArrayOf(1L, 1L, hidden.size.toLong())
                            2 -> longArrayOf(1L, hidden.size.toLong())
                            else -> longArrayOf(hidden.size.toLong())
                        }
                        inputs[name] = VieNeuV3WeightStore.createFloatOrFp16Tensor(env, hidden, dims, type)
                    }
                    headSession.run(inputs).use { res ->
                        val outTensor = res[0] as OnnxTensor
                        return VieNeuV3WeightStore.readTensorAsFloatsSafe(outTensor)
                    }
                } finally {
                    inputs.values.forEach { runCatching { it.close() } }
                }
            }
        }

        // Fallback: Check `heads.bin` for `audio_head_{q}` weight matrix
        val store = weightStore
        if (store != null) {
            val wKey = store.findKey("audio_head_$codebookIdx", "audio_heads.$codebookIdx", "head_$codebookIdx")
            if (wKey != null) {
                val w = store.getFloats(wKey)
                val meta = store.getMeta(wKey)
                if (w != null && meta != null) {
                    return VieNeuV3WeightStore.linearProject(hidden, w, meta.shape)
                }
            }
        }
        return FloatArray(codebookSize)
    }

    private fun lookupAudioCodebookEmbedding(codebookIdx: Int, tokenId: Int): FloatArray {
        // Priority 1: `heads.bin` per-codebook or stacked audio embedding table
        val store = weightStore
        if (store != null) {
            val perQKey = store.findKey(
                "audio_embed_$codebookIdx", "audio_embeds.$codebookIdx",
                "audio_emb_$codebookIdx", "codebook_embed_$codebookIdx",
                "emb_code.$codebookIdx"
            )
            if (perQKey != null) {
                val table = store.getFloats(perQKey)
                val meta = store.getMeta(perQKey)
                if (table != null && meta != null && meta.shape.size >= 2) {
                    val rows = meta.shape[0].coerceAtLeast(1)
                    val dim = meta.shape[1].coerceAtLeast(1)
                    val row = tokenId.coerceIn(0, rows - 1)
                    val out = FloatArray(hiddenSize)
                    System.arraycopy(table, row * dim, out, 0, minOf(dim, hiddenSize))
                    return out
                }
            }

            // Stacked table `[numCodebooks, vocab, hidden]` or `[numCodebooks * vocab, hidden]`
            val stackedKey = store.findKey("audio_embed", "audio_embeddings", "audio_emb", "codebook_embed")
            if (stackedKey != null) {
                val table = store.getFloats(stackedKey)
                val meta = store.getMeta(stackedKey)
                if (table != null && meta != null) {
                    if (meta.shape.size == 3) {
                        val nq = meta.shape[0].coerceAtLeast(1)
                        val vocab = meta.shape[1].coerceAtLeast(1)
                        val dim = meta.shape[2].coerceAtLeast(1)
                        val q = codebookIdx.coerceIn(0, nq - 1)
                        val tok = tokenId.coerceIn(0, vocab - 1)
                        val off = (q * vocab + tok) * dim
                        val out = FloatArray(hiddenSize)
                        if (off + minOf(dim, hiddenSize) <= table.size) {
                            System.arraycopy(table, off, out, 0, minOf(dim, hiddenSize))
                        }
                        return out
                    } else if (meta.shape.size == 2) {
                        val totalRows = meta.shape[0].coerceAtLeast(1)
                        val dim = meta.shape[1].coerceAtLeast(1)
                        val stride = (totalRows / numCodebooks).coerceAtLeast(codebookSize)
                        val row = (codebookIdx * stride + tokenId).coerceIn(0, totalRows - 1)
                        val out = FloatArray(hiddenSize)
                        System.arraycopy(table, row * dim, out, 0, minOf(dim, hiddenSize))
                        return out
                    }
                }
            }
        }

        // Priority 2: If `audio_head_q.onnx` takes INT64/INT32 index (Gather mode)
        val headSession = audioHeadSessions.getOrNull(codebookIdx)
        if (headSession != null) {
            val firstIn = headSession.inputInfo.values.firstOrNull()?.info as? TensorInfo
            if (firstIn?.type == OnnxJavaType.INT64 || firstIn?.type == OnnxJavaType.INT32) {
                return runGatherOnnxSession(headSession, longArrayOf(tokenId.toLong()))
            }
        }

        // Priority 3: Smooth deterministic embedding fallback
        return FloatArray(hiddenSize) { d ->
            (kotlin.math.sin(((codebookIdx * 1031 + tokenId + 1) * (d + 1)) * 0.009f) * 0.04f)
        }
    }

    private fun sumAudioCodebookEmbeddings(frameCodes: IntArray): FloatArray {
        val sum = FloatArray(hiddenSize)
        for (q in frameCodes.indices) {
            val emb = lookupAudioCodebookEmbedding(q, frameCodes[q])
            for (d in 0 until hiddenSize) {
                sum[d] += emb[d]
            }
        }
        return sum
    }

    private fun shouldStopByStopHead(hidden: FloatArray): Boolean {
        val store = weightStore ?: return false
        val wKey = stopHeadWKey ?: return false
        val w = store.getFloats(wKey) ?: return false
        val meta = store.getMeta(wKey) ?: return false
        val b = stopHeadBKey?.let { store.getFloats(it) }
        val logits = VieNeuV3WeightStore.linearProject(hidden, w, meta.shape, b)
        return when {
            logits.size == 1 -> logits[0] > 0.5f // sigmoid > 0.62
            logits.size >= 2 -> logits[1] > logits[0]
            else -> false
        }
    }

    private fun applyOptionalNorm(vec: FloatArray, normKey: String?): FloatArray {
        if (normKey == null || weightStore == null) return vec
        val w = weightStore.getFloats(normKey) ?: return vec
        if (w.size != vec.size) return vec
        return VieNeuV3WeightStore.rmsNorm(vec, w, config.rmsNormEps)
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
                        // Inspect exact axis of `16` codebooks:
                        // Real device log showed: `audio_codes:INT32[-1x-1x16]` -> [1, numFrames, 16]!
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

                // Respect `audio_lengths` output tensor if present (`result[1]`)
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

                // Mix down stereo `[1, 2, N]` -> Mono `[N]` if needed
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
