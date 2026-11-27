package com.example.audio

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Parsed `config.json` + `codec_browser_onnx_meta.json` parameters for VieNeu-TTS v3 Turbo.
 */
data class VieNeuConfigData(
    val hiddenSize: Int = 768,
    val numCodebooks: Int = 16,
    val codebookSize: Int = 1024,
    val speakerDim: Int = 192,
    val outputSampleRate: Int = 48_000,
    val bosTokenId: Long? = null,
    val eosTokenId: Long? = null,
    val padTokenId: Long? = null,
    val textBosTokenId: Long? = null,
    val textEosTokenId: Long? = null,
    val audioBosTokenId: Long? = null,
    val audioEosTokenId: Long? = null,
    val audioPadTokenId: Long? = null,
    val audioRefSlotTokenId: Long? = null,
    val defaultStyleTokenId: Long? = null,
    val rmsNormEps: Float = 1e-6f,
    val rawSummary: String = ""
)

/**
 * Single tensor descriptor parsed from `heads.json` pointing into `heads.bin`.
 */
data class HeadTensorMeta(
    val name: String,
    val offset: Int,
    val byteLength: Int,
    val shape: IntArray,
    val dtype: String,
    val scale: Float = 1.0f,
    val zeroPoint: Float = 0.0f
) {
    val elementCount: Int
        get() = if (shape.isEmpty()) 0 else shape.fold(1) { acc, d -> acc * d.coerceAtLeast(1) }

    fun describe(): String = "$name:${dtype}[${shape.joinToString("x")}]@$offset"
}

/**
 * Parsed preset voice entry from `voices.json`.
 */
data class VieNeuPresetVoice(
    val id: String,
    val speakerEmbedding: FloatArray?,
    val refCodes: Array<IntArray>?,
    val refText: String?,
    val refPhonemes: String?,
    val keysSummary: String
)

/**
 * Type-safe container for KV-cache and intermediate ONNX tensors.
 * Eliminates `NullPointerException: FloatBuffer.remaining()` by checking exact `OnnxJavaType`
 * before touching any typed NIO buffer.
 */
data class SafeTensorPayload(
    val shape: LongArray,
    val type: OnnxJavaType,
    val floats: FloatArray? = null,
    val shorts: ShortArray? = null,
    val ints: IntArray? = null,
    val longs: LongArray? = null,
    val bools: BooleanArray? = null
)

/**
 * Loads & decodes `heads.json` + `heads.bin`, `config.json`, `codec_browser_onnx_meta.json`,
 * and `voices.json` for VieNeu-TTS v3 Turbo, and provides fast pure-Kotlin math + safe ONNX tensor helpers.
 */
class VieNeuV3WeightStore(
    val entries: Map<String, HeadTensorMeta>,
    private val binBytes: ByteArray
) {
    private val decodedCache = HashMap<String, FloatArray>()

    fun isEmpty(): Boolean = entries.isEmpty() || binBytes.isEmpty()

    fun findKey(vararg patterns: String): String? {
        for (pat in patterns) {
            val lowerPat = pat.lowercase(Locale.ROOT)
            entries.keys.firstOrNull { it.equals(pat, ignoreCase = true) }?.let { return it }
            entries.keys.firstOrNull { it.lowercase(Locale.ROOT).contains(lowerPat) }?.let { return it }
        }
        return null
    }

    fun findKeysMatching(predicate: (String, HeadTensorMeta) -> Boolean): List<HeadTensorMeta> {
        return entries.values.filter { predicate(it.name, it) }
    }

    fun getMeta(name: String): HeadTensorMeta? = entries[name]

    fun getFloats(name: String): FloatArray? {
        decodedCache[name]?.let { return it }
        val meta = entries[name] ?: return null
        val arr = decodeTensorToFloatArray(meta, binBytes) ?: return null
        decodedCache[name] = arr
        return arr
    }

    fun describeAll(): String {
        if (entries.isEmpty()) return "(trống)"
        return entries.values.joinToString(", ") { it.describe() }
    }

    companion object {
        fun loadFromFiles(headsJsonFile: File?, headsBinFile: File?): VieNeuV3WeightStore? {
            if (headsJsonFile == null || !headsJsonFile.exists()) return null
            if (headsBinFile == null || !headsBinFile.exists()) return null
            return runCatching {
                val jsonText = headsJsonFile.readText(Charsets.UTF_8).trim()
                val binBytes = headsBinFile.readBytes()
                val parsed = parseHeadsJson(jsonText, binBytes.size)
                VieNeuV3WeightStore(parsed, binBytes)
            }.getOrNull()
        }

        private fun parseHeadsJson(raw: String, totalBytes: Int): Map<String, HeadTensorMeta> {
            val result = LinkedHashMap<String, HeadTensorMeta>()
            var runningOffset = 0

            fun bytesPerElement(dtype: String): Int = when (dtype.lowercase(Locale.ROOT)) {
                "float16", "fp16", "f16", "bfloat16", "bf16", "int16", "uint16", "i16", "u16" -> 2
                "int8", "uint8", "i8", "u8", "qint8", "quint8", "bool" -> 1
                "float64", "fp64", "f64", "int64", "i64" -> 8
                else -> 4
            }

            fun parseEntryObj(name: String, obj: JSONObject) {
                val shapeArr = obj.optJSONArray("shape") ?: obj.optJSONArray("dims")
                val shape = if (shapeArr != null) {
                    IntArray(shapeArr.length()) { shapeArr.optInt(it, 1) }
                } else {
                    intArrayOf()
                }
                val dtype = obj.optString("dtype")
                    .ifBlank { obj.optString("type") }
                    .ifBlank { "float32" }
                val elemCount = if (shape.isEmpty()) 0 else shape.fold(1) { a, d -> a * d.coerceAtLeast(1) }
                val inferredLen = elemCount * bytesPerElement(dtype)
                val byteLen = obj.optInt(
                    "nbytes",
                    obj.optInt(
                        "byteLength",
                        obj.optInt(
                            "byte_length",
                            obj.optInt("length", inferredLen)
                        )
                    )
                ).let { if (it > 0) it else inferredLen }

                val hasExplicitOffset = obj.has("offset") || obj.has("byteOffset") || obj.has("byte_offset")
                val offset = if (hasExplicitOffset) {
                    obj.optInt("offset", obj.optInt("byteOffset", obj.optInt("byte_offset", runningOffset)))
                } else {
                    runningOffset
                }
                val scale = obj.optDouble("scale", 1.0).toFloat()
                val zp = obj.optDouble("zero_point", obj.optDouble("zeroPoint", 0.0)).toFloat()

                if (offset in 0..totalBytes) {
                    result[name] = HeadTensorMeta(
                        name = name,
                        offset = offset,
                        byteLength = byteLen.coerceAtMost((totalBytes - offset).coerceAtLeast(0)),
                        shape = shape,
                        dtype = dtype,
                        scale = scale,
                        zeroPoint = zp
                    )
                }
                runningOffset = max(runningOffset, offset + byteLen)
            }

            if (raw.startsWith("[")) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", obj.optString("key", "tensor_$i"))
                    parseEntryObj(name, obj)
                }
            } else if (raw.startsWith("{")) {
                val root = JSONObject(raw)
                val container = root.opt("tensors") ?: root.opt("weights") ?: root.opt("entries") ?: root
                when (container) {
                    is JSONArray -> {
                        for (i in 0 until container.length()) {
                            val obj = container.optJSONObject(i) ?: continue
                            val name = obj.optString("name", obj.optString("key", "tensor_$i"))
                            parseEntryObj(name, obj)
                        }
                    }
                    is JSONObject -> {
                        val keys = container.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val child = container.optJSONObject(k) ?: continue
                            parseEntryObj(k, child)
                        }
                    }
                }
            }
            return result
        }

        private fun decodeTensorToFloatArray(meta: HeadTensorMeta, bin: ByteArray): FloatArray? {
            if (meta.offset < 0 || meta.offset >= bin.size) return null
            val avail = (bin.size - meta.offset).coerceAtMost(meta.byteLength.takeIf { it > 0 } ?: (bin.size - meta.offset))
            val bb = ByteBuffer.wrap(bin, meta.offset, avail).order(ByteOrder.LITTLE_ENDIAN)
            val dt = meta.dtype.lowercase(Locale.ROOT)
            val expected = meta.elementCount
            return when (dt) {
                "float32", "fp32", "f32" -> {
                    val count = (avail / 4).let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { bb.float }
                }
                "float16", "fp16", "f16" -> {
                    val count = (avail / 2).let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { halfToFloat(bb.short) }
                }
                "bfloat16", "bf16" -> {
                    val count = (avail / 2).let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { Float.fromBits((bb.short.toInt() and 0xFFFF) shl 16) }
                }
                "int8", "i8", "qint8" -> {
                    val count = avail.let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { (bb.get().toFloat() - meta.zeroPoint) * meta.scale }
                }
                "uint8", "u8", "quint8" -> {
                    val count = avail.let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { ((bb.get().toInt() and 0xFF).toFloat() - meta.zeroPoint) * meta.scale }
                }
                "int32", "i32" -> {
                    val count = (avail / 4).let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { bb.int.toFloat() }
                }
                else -> {
                    val count = (avail / 4).let { if (expected > 0) minOf(it, expected) else it }
                    FloatArray(count) { bb.float }
                }
            }
        }

        fun parseConfigAndCodecMeta(configFile: File?, codecMetaFile: File?): VieNeuConfigData {
            var hiddenSize = 768
            var numCodebooks = 16
            var codebookSize = 1024
            var speakerDim = 192
            var sampleRate = 48_000
            var bosId: Long? = null
            var eosId: Long? = null
            var padId: Long? = null
            var textBosId: Long? = null
            var textEosId: Long? = null
            var audioBosId: Long? = null
            var audioEosId: Long? = null
            var audioPadId: Long? = null
            var audioRefSlotId: Long? = null
            var defaultStyleId: Long? = null
            var rmsEps = 1e-6f
            val summaryParts = ArrayList<String>()

            fun optLongOrNull(obj: JSONObject, vararg keys: String): Long? {
                for (k in keys) {
                    if (obj.has(k) && !obj.isNull(k)) {
                        val v = obj.opt(k)
                        when (v) {
                            is Number -> return v.toLong()
                            is JSONArray -> if (v.length() > 0) return v.optLong(0)
                        }
                    }
                }
                return null
            }

            configFile?.takeIf { it.exists() }?.let { cf ->
                runCatching {
                    val root = JSONObject(cf.readText(Charsets.UTF_8))
                    hiddenSize = root.optInt("hidden_size", root.optInt("d_model", hiddenSize))
                    numCodebooks = root.optInt(
                        "num_codebooks",
                        root.optInt("n_q", root.optInt("num_quantizers", root.optInt("audio_channels", numCodebooks)))
                    )
                    codebookSize = root.optInt(
                        "codebook_size",
                        root.optInt("audio_vocab_size", codebookSize)
                    )
                    speakerDim = root.optInt("speaker_dim", root.optInt("speaker_embed_dim", speakerDim))
                    val sr = root.optInt(
                        "audio_sample_rate",
                        root.optInt("sample_rate", root.optInt("sampling_rate", 0))
                    )
                    if (sr in setOf(16_000, 22_050, 24_000, 44_100, 48_000)) sampleRate = sr
                    bosId = optLongOrNull(root, "bos_token_id", "bos_id")
                    eosId = optLongOrNull(root, "eos_token_id", "eos_id")
                    padId = optLongOrNull(root, "pad_token_id", "pad_id")
                    textBosId = optLongOrNull(root, "text_bos_token_id", "text_start_token_id", "text_prompt_start_token_id")
                    textEosId = optLongOrNull(root, "text_eos_token_id", "text_end_token_id", "text_prompt_end_token_id")
                    audioBosId = optLongOrNull(root, "audio_bos_token_id", "audio_start_token_id", "speech_generation_start_token_id")
                    audioEosId = optLongOrNull(root, "audio_eos_token_id", "audio_end_token_id", "speech_generation_end_token_id", "stop_token_id")
                    audioPadId = optLongOrNull(root, "audio_pad_token_id")
                    audioRefSlotId = optLongOrNull(root, "audio_ref_slot_token_id")
                    defaultStyleId = optLongOrNull(root, "default_style_token_id")
                    rmsEps = root.optDouble("rms_norm_eps", 1e-6).toFloat()

                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = root.opt(k)
                        val vStr = v?.toString().orEmpty()
                        if (vStr.length <= 48) {
                            summaryParts.add("$k=$vStr")
                        } else {
                            summaryParts.add("$k=[...]")
                        }
                    }
                }
            }

            codecMetaFile?.takeIf { it.exists() }?.let { cm ->
                runCatching {
                    val root = JSONObject(cm.readText(Charsets.UTF_8))
                    val sr = root.optInt("sample_rate", root.optInt("sampling_rate", 0))
                    if (sr in setOf(16_000, 22_050, 24_000, 44_100, 48_000)) sampleRate = sr
                    val nq = root.optInt("num_codebooks", root.optInt("num_quantizers", root.optInt("n_q", 0)))
                    if (nq in 1..32) numCodebooks = nq
                }
            }

            return VieNeuConfigData(
                hiddenSize = hiddenSize,
                numCodebooks = numCodebooks,
                codebookSize = codebookSize,
                speakerDim = speakerDim,
                outputSampleRate = sampleRate,
                bosTokenId = bosId,
                eosTokenId = eosId,
                padTokenId = padId,
                textBosTokenId = textBosId,
                textEosTokenId = textEosId,
                audioBosTokenId = audioBosId,
                audioEosTokenId = audioEosId,
                audioPadTokenId = audioPadId,
                audioRefSlotTokenId = audioRefSlotId,
                defaultStyleTokenId = defaultStyleId,
                rmsNormEps = rmsEps,
                rawSummary = summaryParts.take(28).joinToString(", ")
            )
        }

        fun parseRichVoicesJson(voicesFile: File?): List<VieNeuPresetVoice> {
            if (voicesFile == null || !voicesFile.exists()) return emptyList()
            return runCatching {
                val raw = voicesFile.readText(Charsets.UTF_8).trim()
                val out = ArrayList<VieNeuPresetVoice>()

                fun parseVoiceObj(id: String, item: JSONObject) {
                    val embArr = item.optJSONArray("speakerEmbedding")
                        ?: item.optJSONArray("embedding")
                        ?: item.optJSONArray("speaker_embedding")
                        ?: item.optJSONArray("xvector")
                        ?: item.optJSONArray("xvec")
                        ?: item.optJSONArray("spk_emb")
                    val emb = embArr?.let { a -> FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() } }

                    val codesArr = item.optJSONArray("referenceCodes")
                        ?: item.optJSONArray("codes")
                        ?: item.optJSONArray("ref_codes")
                        ?: item.optJSONArray("audio_codes")
                    val refCodes = parseCodesMatrix(codesArr)
                    val refText = item.optString("text", item.optString("ref_text", "")).ifBlank { null }
                    val refPhonemes = item.optString("phonemes", item.optString("ref_phonemes", "")).ifBlank { null }

                    val subKeys = ArrayList<String>()
                    val ik = item.keys()
                    while (ik.hasNext()) {
                        val sk = ik.next()
                        val sv = item.opt(sk)
                        subKeys.add(
                            when (sv) {
                                is JSONArray -> {
                                    if (sk == "referenceCodes" && refCodes != null && refCodes.isNotEmpty()) {
                                        "$sk[${refCodes.size}x${refCodes[0].size}]"
                                    } else if (sk == "speakerEmbedding" && emb != null) {
                                        var sq = 0.0
                                        for (v in emb) sq += v * v
                                        "$sk[${emb.size},norm=${String.format(Locale.US, "%.2f", sqrt(sq))}]"
                                    } else {
                                        "$sk[${sv.length()}]"
                                    }
                                }
                                is String -> "$sk(len=${sv.length})"
                                else -> "$sk=$sv"
                            }
                        )
                    }
                    out.add(
                        VieNeuPresetVoice(
                            id = id,
                            speakerEmbedding = emb,
                            refCodes = refCodes,
                            refText = refText,
                            refPhonemes = refPhonemes,
                            keysSummary = subKeys.joinToString(", ")
                        )
                    )
                }

                if (raw.startsWith("{")) {
                    val root = JSONObject(raw)
                    val arrVoices = root.optJSONArray("voices") ?: root.optJSONArray("presets")
                    if (arrVoices != null) {
                        for (i in 0 until arrVoices.length()) {
                            val item = arrVoices.optJSONObject(i) ?: continue
                            val id = item.optString("id", item.optString("name", "voice_$i"))
                            parseVoiceObj(id, item)
                        }
                        return out
                    }
                    val container = root.optJSONObject("presets")
                        ?: root.optJSONObject("voices")
                        ?: root
                    val keys = container.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        when (val item = container.opt(id)) {
                            is JSONArray -> {
                                val vec = FloatArray(item.length()) { item.optDouble(it, 0.0).toFloat() }
                                out.add(VieNeuPresetVoice(id, vec, null, null, null, "array[${vec.size}]"))
                            }
                            is JSONObject -> parseVoiceObj(id, item)
                        }
                    }
                }
                out
            }.getOrDefault(emptyList())
        }

        private fun parseCodesMatrix(arr: JSONArray?): Array<IntArray>? {
            if (arr == null || arr.length() == 0) return null
            val first = arr.opt(0)
            if (first is JSONArray) {
                return Array(arr.length()) { r ->
                    val row = arr.optJSONArray(r)
                    if (row != null) IntArray(row.length()) { c -> row.optInt(c, 0) } else IntArray(0)
                }
            }
            // Flat 1D array -> reshape into frames x 16 if divisible by 16
            val len = arr.length()
            if (len >= 16 && len % 16 == 0) {
                val frames = len / 16
                return Array(frames) { f -> IntArray(16) { q -> arr.optInt(f * 16 + q, 0) } }
            }
            return null
        }

        // ---------------------------------------------------------------------
        // IEEE-754 Float16 Conversion & Safe Typed ONNX Tensor Helpers
        // ---------------------------------------------------------------------

        fun halfToFloat(hbits: Short): Float {
            val h = hbits.toInt() and 0xFFFF
            val sign = (h and 0x8000) shl 16
            var exp = (h ushr 10) and 0x1F
            var mant = h and 0x03FF
            return when (exp) {
                0 -> {
                    if (mant == 0) {
                        Float.fromBits(sign)
                    } else {
                        exp = 1
                        while ((mant and 0x0400) == 0) {
                            mant = mant shl 1
                            exp--
                        }
                        mant = mant and 0x03FF
                        Float.fromBits(sign or ((exp + 112) shl 23) or (mant shl 13))
                    }
                }
                31 -> Float.fromBits(sign or 0x7F800000 or (mant shl 13))
                else -> Float.fromBits(sign or ((exp + 112) shl 23) or (mant shl 13))
            }
        }

        fun floatToHalf(fval: Float): Short {
            val fbits = fval.toRawBits()
            val sign = (fbits ushr 16) and 0x8000
            var valBits = (fbits and 0x7FFFFFFF) + 0x1000
            if (valBits >= 0x47800000) {
                return if ((fbits and 0x7FFFFFFF) >= 0x47800000) {
                    if (valBits < 0x7F800000) (sign or 0x7C00).toShort()
                    else (sign or 0x7C00 or ((fbits and 0x007FFFFF) ushr 13)).toShort()
                } else {
                    (sign or 0x7BFF).toShort()
                }
            }
            if (valBits >= 0x38800000) {
                return (sign or ((valBits - 0x38000000) ushr 13)).toShort()
            }
            if (valBits < 0x33000000) return sign.toShort()
            valBits = (fbits and 0x7FFFFFFF) ushr 23
            return (sign or ((((fbits and 0x007FFFFF) or 0x00800000) + (0x00800000 ushr (valBits - 102))) ushr (126 - valBits))).toShort()
        }

        /**
         * Safely reads ANY `OnnxTensor` into a [SafeTensorPayload] without ever hitting NPE
         * on non-float buffers (`INT32`, `INT64`, `FLOAT16`, `BOOL`).
         */
        fun readSafePayload(tensor: OnnxTensor): SafeTensorPayload {
            val info = tensor.info
            val shape = info.shape.copyOf()
            return when (info.type) {
                OnnxJavaType.FLOAT -> {
                    val fb = tensor.floatBuffer
                    if (fb != null) {
                        val arr = FloatArray(fb.remaining())
                        fb.get(arr)
                        SafeTensorPayload(shape, OnnxJavaType.FLOAT, floats = arr)
                    } else {
                        SafeTensorPayload(shape, OnnxJavaType.FLOAT, floats = FloatArray(0))
                    }
                }
                OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16 -> {
                    val sb = tensor.shortBuffer
                    if (sb != null) {
                        val arr = ShortArray(sb.remaining())
                        sb.get(arr)
                        SafeTensorPayload(shape, info.type, shorts = arr)
                    } else {
                        SafeTensorPayload(shape, info.type, shorts = ShortArray(0))
                    }
                }
                OnnxJavaType.INT32 -> {
                    val ib = tensor.intBuffer
                    if (ib != null) {
                        val arr = IntArray(ib.remaining())
                        ib.get(arr)
                        SafeTensorPayload(shape, OnnxJavaType.INT32, ints = arr)
                    } else {
                        SafeTensorPayload(shape, OnnxJavaType.INT32, ints = IntArray(0))
                    }
                }
                OnnxJavaType.INT64 -> {
                    val lb = tensor.longBuffer
                    if (lb != null) {
                        val arr = LongArray(lb.remaining())
                        lb.get(arr)
                        SafeTensorPayload(shape, OnnxJavaType.INT64, longs = arr)
                    } else {
                        SafeTensorPayload(shape, OnnxJavaType.INT64, longs = LongArray(0))
                    }
                }
                OnnxJavaType.BOOL -> {
                    val bb = tensor.byteBuffer
                    if (bb != null) {
                        val arr = BooleanArray(bb.remaining()) { bb.get().toInt() != 0 }
                        SafeTensorPayload(shape, OnnxJavaType.BOOL, bools = arr)
                    } else {
                        SafeTensorPayload(shape, OnnxJavaType.BOOL, bools = BooleanArray(0))
                    }
                }
                else -> {
                    val fb = tensor.floatBuffer
                    if (fb != null) {
                        val arr = FloatArray(fb.remaining())
                        fb.get(arr)
                        SafeTensorPayload(shape, OnnxJavaType.FLOAT, floats = arr)
                    } else {
                        SafeTensorPayload(shape, info.type)
                    }
                }
            }
        }

        fun readTensorAsFloatsSafe(tensor: OnnxTensor): FloatArray {
            val p = readSafePayload(tensor)
            return when {
                p.floats != null -> p.floats
                p.shorts != null && p.type == OnnxJavaType.FLOAT16 -> FloatArray(p.shorts.size) { halfToFloat(p.shorts[it]) }
                p.shorts != null && p.type == OnnxJavaType.BFLOAT16 -> FloatArray(p.shorts.size) { Float.fromBits((p.shorts[it].toInt() and 0xFFFF) shl 16) }
                p.ints != null -> FloatArray(p.ints.size) { p.ints[it].toFloat() }
                p.longs != null -> FloatArray(p.longs.size) { p.longs[it].toFloat() }
                else -> FloatArray(0)
            }
        }

        fun readTensorAsIntsSafe(tensor: OnnxTensor): IntArray {
            val p = readSafePayload(tensor)
            return when {
                p.ints != null -> p.ints
                p.longs != null -> IntArray(p.longs.size) { p.longs[it].toInt() }
                p.floats != null -> IntArray(p.floats.size) { p.floats[it].toInt() }
                else -> IntArray(0)
            }
        }

        fun createOnnxTensorFromPayload(
            env: OrtEnvironment,
            payload: SafeTensorPayload,
            expectedType: OnnxJavaType
        ): OnnxTensor {
            return when (expectedType) {
                OnnxJavaType.FLOAT16 -> {
                    val shorts = payload.shorts ?: payload.floats?.let { f -> ShortArray(f.size) { floatToHalf(f[it]) } } ?: ShortArray(0)
                    val bb = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
                    bb.asShortBuffer().put(shorts)
                    bb.rewind()
                    OnnxTensor.createTensor(env, bb, payload.shape, OnnxJavaType.FLOAT16)
                }
                OnnxJavaType.INT32 -> {
                    val ints = payload.ints ?: payload.longs?.let { l -> IntArray(l.size) { l[it].toInt() } } ?: IntArray(0)
                    OnnxTensor.createTensor(env, IntBuffer.wrap(ints), payload.shape)
                }
                OnnxJavaType.INT64 -> {
                    val longs = payload.longs ?: payload.ints?.let { i -> LongArray(i.size) { i[it].toLong() } } ?: LongArray(0)
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longs), payload.shape)
                }
                else -> {
                    val floats = payload.floats
                        ?: payload.shorts?.let { s -> FloatArray(s.size) { halfToFloat(s[it]) } }
                        ?: FloatArray(0)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), payload.shape)
                }
            }
        }

        fun createFloatOrFp16Tensor(
            env: OrtEnvironment,
            floats: FloatArray,
            shape: LongArray,
            expectedType: OnnxJavaType
        ): OnnxTensor {
            return if (expectedType == OnnxJavaType.FLOAT16) {
                val bb = ByteBuffer.allocateDirect(floats.size * 2).order(ByteOrder.nativeOrder())
                val sb = bb.asShortBuffer()
                for (f in floats) sb.put(floatToHalf(f))
                bb.rewind()
                OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.FLOAT16)
            } else {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), shape)
            }
        }

        fun createEmptyTypedTensor(env: OrtEnvironment, shape: LongArray, type: OnnxJavaType): OnnxTensor {
            val count = shape.fold(1L) { acc, d -> acc * d.coerceAtLeast(0L) }.toInt().coerceAtLeast(0)
            return when (type) {
                OnnxJavaType.FLOAT16 -> {
                    val bb = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
                    OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.FLOAT16)
                }
                OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(count)), shape)
                OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(count)), shape)
                OnnxJavaType.BOOL -> {
                    val bb = ByteBuffer.allocateDirect(count)
                    OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.BOOL)
                }
                else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(count)), shape)
            }
        }

        // ---------------------------------------------------------------------
        // Fast Vector / Linear / Activation / Sampling Math
        // ---------------------------------------------------------------------

        /**
         * Linear projection `y = x @ W^T + b` (handles `[outDim, inDim]` and `[inDim, outDim]` layouts).
         */
        fun linearProject(
            x: FloatArray,
            weight: FloatArray,
            weightShape: IntArray,
            bias: FloatArray? = null
        ): FloatArray {
            if (weightShape.size < 2) return x.copyOf()
            val rows = weightShape[0]
            val cols = weightShape[1]
            val out: FloatArray
            if (cols == x.size) {
                // PyTorch Linear layout: [outDim, inDim]
                out = FloatArray(rows)
                for (r in 0 until rows) {
                    val rowOff = r * cols
                    var acc = 0f
                    for (c in 0 until cols) {
                        acc += weight[rowOff + c] * x[c]
                    }
                    out[r] = acc + (bias?.getOrNull(r) ?: 0f)
                }
            } else if (rows == x.size) {
                // Transposed layout: [inDim, outDim]
                out = FloatArray(cols)
                for (c in 0 until cols) {
                    var acc = 0f
                    for (r in 0 until rows) {
                        acc += x[r] * weight[r * cols + c]
                    }
                    out[c] = acc + (bias?.getOrNull(c) ?: 0f)
                }
            } else {
                out = FloatArray(rows)
            }
            return out
        }

        fun rmsNorm(x: FloatArray, weight: FloatArray, eps: Float = 1e-6f): FloatArray {
            var ss = 0.0
            for (v in x) ss += v * v
            val invRms = (1.0 / sqrt(ss / x.size.coerceAtLeast(1) + eps)).toFloat()
            return FloatArray(x.size) { i -> x[i] * invRms * (weight.getOrNull(i) ?: 1f) }
        }

        fun layerNorm(
            x: FloatArray,
            weight: FloatArray?,
            bias: FloatArray?,
            eps: Float = 1e-5f
        ): FloatArray {
            val n = x.size.coerceAtLeast(1)
            var mean = 0.0
            for (v in x) mean += v
            mean /= n
            var varSum = 0.0
            for (v in x) {
                val d = v - mean
                varSum += d * d
            }
            val invStd = (1.0 / sqrt(varSum / n + eps)).toFloat()
            return FloatArray(x.size) { i ->
                val norm = ((x[i] - mean).toFloat()) * invStd
                norm * (weight?.getOrNull(i) ?: 1f) + (bias?.getOrNull(i) ?: 0f)
            }
        }

        fun siluInPlace(x: FloatArray) {
            for (i in x.indices) {
                val v = x[i]
                x[i] = (v / (1.0 + exp(-v.toDouble()))).toFloat()
            }
        }

        /**
         * Temperature + Top-K + Top-P nucleus sampler with optional repetition penalty and EOS guard.
         */
        fun sampleLogits(
            logits: FloatArray,
            temperature: Float = 0.7f,
            topK: Int = 40,
            topP: Float = 0.90f,
            maxValidIndex: Int = logits.size - 1,
            recentTokens: IntArray? = null,
            repetitionPenalty: Float = 1.12f,
            rng: Random = Random.Default
        ): Int {
            val limit = (maxValidIndex + 1).coerceIn(1, logits.size)
            if (temperature <= 0.05f || limit == 1) {
                var bestIdx = 0
                var bestVal = Float.NEGATIVE_INFINITY
                for (i in 0 until limit) {
                    val v = logits[i]
                    if (v.isFinite() && v > bestVal) {
                        bestVal = v
                        bestIdx = i
                    }
                }
                return bestIdx
            }

            val adjusted = FloatArray(limit) { i ->
                val v = logits[i]
                if (v.isFinite()) v else -1e9f
            }

            if (recentTokens != null && repetitionPenalty > 1.0f) {
                for (tok in recentTokens) {
                    if (tok in 0 until limit) {
                        val v = adjusted[tok]
                        adjusted[tok] = if (v > 0f) v / repetitionPenalty else v * repetitionPenalty
                    }
                }
            }

            val invTemp = 1f / temperature
            var maxLogit = Float.NEGATIVE_INFINITY
            for (i in 0 until limit) {
                adjusted[i] *= invTemp
                if (adjusted[i] > maxLogit) maxLogit = adjusted[i]
            }

            val k = topK.coerceIn(1, limit)
            val indices = IntArray(limit) { it }
            // Partial selection sort for top-K indices (fast for V ~ 1025)
            for (i in 0 until k) {
                var best = i
                var bestV = adjusted[indices[i]]
                for (j in i + 1 until limit) {
                    val v = adjusted[indices[j]]
                    if (v > bestV) {
                        bestV = v
                        best = j
                    }
                }
                if (best != i) {
                    val tmp = indices[i]
                    indices[i] = indices[best]
                    indices[best] = tmp
                }
            }

            val probs = DoubleArray(k)
            var probSum = 0.0
            for (i in 0 until k) {
                val p = exp((adjusted[indices[i]] - maxLogit).toDouble())
                probs[i] = p
                probSum += p
            }
            if (probSum <= 0.0) return indices[0]

            // Top-P cutoff
            var cumProb = 0.0
            var kept = k
            for (i in 0 until k) {
                probs[i] /= probSum
                cumProb += probs[i]
                if (cumProb >= topP && i >= 0) {
                    kept = i + 1
                    break
                }
            }
            var keptSum = 0.0
            for (i in 0 until kept) keptSum += probs[i]
            var r = rng.nextDouble() * keptSum
            for (i in 0 until kept) {
                r -= probs[i]
                if (r <= 0.0) return indices[i]
            }
            return indices[0]
        }
    }
}
