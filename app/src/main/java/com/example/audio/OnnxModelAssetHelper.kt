package com.example.audio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Discovered VieNeu-TTS ONNX model files inside `context.filesDir/models/` (including any subdirectories such as `onnx/`).
 */
data class DiscoveredOnnxModels(
    val modelsRootDir: File,
    val sampleRate: Int,
    val speakerEncoderFile: File?,
    val prefillModelFile: File?,
    val decodeStepModelFile: File?,
    val backboneOrAcousticFile: File?,
    val codecDecoderFile: File?,
    val g2pDictionaryFile: File?,
    val voicesJsonFile: File?,
    val tokenizerJsonFile: File?,
    val configJsonFile: File?,
    val manifestJsonFile: File?,
    val allFiles: List<File>,
    val copyErrors: List<String>
) {
    val hasAnyOnnxModel: Boolean
        get() = allFiles.any { it.name.endsWith(".onnx", ignoreCase = true) }

    fun describeSummary(): String {
        val parts = mutableListOf<String>()
        speakerEncoderFile?.let { parts.add("enc=${it.name}") }
        prefillModelFile?.let { parts.add("prefill=${it.name}") }
        decodeStepModelFile?.let { parts.add("decode=${it.name}") }
        backboneOrAcousticFile?.let { parts.add("model=${it.name}") }
        codecDecoderFile?.let { parts.add("codec=${it.name}") }
        g2pDictionaryFile?.let { parts.add("g2p=${it.name}") }
        voicesJsonFile?.let { parts.add("voices=${it.name}") }
        return if (parts.isEmpty()) "Không có file ONNX trong ${modelsRootDir.absolutePath}" else parts.joinToString(", ")
    }
}

/**
 * Parsed HuggingFace `tokenizer.json` + `config.json` vocabulary and speech token metadata.
 */
data class ParsedTokenizerData(
    val tokenToId: Map<String, Long>,
    val idToToken: Map<Long, String>,
    val bosTokenId: Long?,
    val eosTokenId: Long?,
    val speechStartTokenId: Long?,
    val speechEndTokenId: Long?,
    val speechTokenBaseId: Long?,
    val speechTokenCount: Int,
    val outputSampleRate: Int?
)

/**
 * Asset copier, recursive model resolver, G2P/tokenizer/voices parser, and FFT log-mel DSP helper
 * for VieNeu-TTS ONNX Runtime on Android.
 */
object OnnxModelAssetHelper {

    private const val TAG = "VieNeuOnnxAssets"
    const val ASSETS_MODELS_DIR = "models"
    const val FILES_MODELS_DIR = "models"

    /**
     * Recursively copies the ENTIRE contents of `assets/models/` (including `.onnx`, `.onnx_data`,
     * `.data`, `.bin`, `.json`, and all subdirectories such as `onnx/`) into `context.filesDir/models/`
     * preserving the exact folder hierarchy and filenames.
     *
     * Uses streaming 64KB chunks + atomic `.tmp` rename (NEVER `readBytes()` into RAM) so large
     * external weight files (`.onnx_data`) copy safely on low-RAM phones.
     */
    fun syncAssetsModelsToFilesDir(context: Context): DiscoveredOnnxModels {
        val appCtx = context.applicationContext
        val destRoot = File(appCtx.filesDir, FILES_MODELS_DIR)
        val copyErrors = mutableListOf<String>()

        val topEntries = runCatching {
            appCtx.assets.list(ASSETS_MODELS_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        if (topEntries.isNotEmpty()) {
            destRoot.mkdirs()
            copyAssetDirectoryRecursive(
                context = appCtx,
                assetRelPath = ASSETS_MODELS_DIR,
                destDir = destRoot,
                errors = copyErrors
            )
        }

        val allFiles = if (destRoot.exists()) {
            destRoot.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".tmp") }
                .sortedBy { it.relativeTo(destRoot).path }
                .toList()
        } else {
            emptyList()
        }

        return classifyDiscoveredFiles(destRoot, allFiles, copyErrors)
    }

    private fun copyAssetDirectoryRecursive(
        context: Context,
        assetRelPath: String,
        destDir: File,
        errors: MutableList<String>
    ) {
        val children = runCatching { context.assets.list(assetRelPath)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (children.isEmpty()) return
        destDir.mkdirs()

        for (child in children) {
            if (child.isBlank()) continue
            val childAssetPath = "$assetRelPath/$child"
            val grandChildren = runCatching { context.assets.list(childAssetPath) }.getOrNull()
            val destTarget = File(destDir, child)

            if (grandChildren != null && grandChildren.isNotEmpty()) {
                // Subdirectory (e.g. assets/models/onnx/) -> recurse & preserve folder structure
                copyAssetDirectoryRecursive(context, childAssetPath, destTarget, errors)
            } else {
                // File (.onnx, .onnx_data, .data, .bin, .json, ...)
                copySingleAssetFile(context, childAssetPath, destTarget, errors)
            }
        }
    }

    private fun copySingleAssetFile(
        context: Context,
        assetPath: String,
        destFile: File,
        errors: MutableList<String>
    ) {
        val expectedLength = runCatching {
            context.assets.openFd(assetPath).use { it.length }
        }.getOrElse { -1L }

        // Skip copy if already intact with matching exact byte length
        if (destFile.exists() && destFile.length() > 0L) {
            if (expectedLength > 0L && destFile.length() == expectedLength) {
                return
            }
            if (expectedLength < 0L && destFile.length() > 16L) {
                // Compressed asset where openFd length is unavailable; verify size by marker if small
                return
            }
        }

        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            Log.i(TAG, "Copied asset $assetPath -> ${destFile.absolutePath} (${destFile.length()} bytes)")
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            val msg = "Lỗi sao chép $assetPath: ${e.message}"
            Log.e(TAG, msg, e)
            errors.add(msg)
        }
    }

    private fun classifyDiscoveredFiles(
        rootDir: File,
        allFiles: List<File>,
        copyErrors: List<String>
    ): DiscoveredOnnxModels {
        val manifestFile = allFiles.firstOrNull { it.name.equals("manifest.json", ignoreCase = true) }
        val configFile = allFiles.firstOrNull { it.name.equals("config.json", ignoreCase = true) }
        val tokenizerFile = allFiles.firstOrNull { it.name.equals("tokenizer.json", ignoreCase = true) }
        val voicesFile = allFiles.firstOrNull {
            it.name.equals("voices.json", ignoreCase = true) ||
                (it.name.contains("voice", ignoreCase = true) && it.name.endsWith(".json", ignoreCase = true))
        }
        val g2pFile = allFiles.firstOrNull {
            it.name.equals("sea_g2p.bin", ignoreCase = true) ||
                (it.name.contains("g2p", ignoreCase = true) && it.name.endsWith(".bin", ignoreCase = true))
        }

        // Read optional sampleRate & role hints from manifest.json
        var manifestSampleRate = 24_000
        var manifestSpeakerHint: String? = null
        var manifestCodecHint: String? = null
        var manifestBackboneHint: String? = null

        manifestFile?.let { mf ->
            runCatching {
                val root = JSONObject(mf.readText(Charsets.UTF_8))
                val sr = root.optInt("sampleRate", root.optInt("sample_rate", 24_000))
                if (sr in setOf(16_000, 22_050, 24_000, 44_100, 48_000)) {
                    manifestSampleRate = sr
                }
                val modelsObj = root.optJSONObject("models")
                manifestSpeakerHint = root.optString("speaker_encoder").ifBlank {
                    modelsObj?.optString("speaker_encoder").orEmpty()
                }.ifBlank { null }
                manifestCodecHint = root.optString("codec_decoder")
                    .ifBlank { root.optString("vocoder") }
                    .ifBlank { modelsObj?.optString("codec_decoder").orEmpty() }
                    .ifBlank { modelsObj?.optString("vocoder").orEmpty() }
                    .ifBlank { null }
                manifestBackboneHint = root.optString("backbone")
                    .ifBlank { root.optString("acoustic") }
                    .ifBlank { root.optString("tts") }
                    .ifBlank { modelsObj?.optString("backbone").orEmpty() }
                    .ifBlank { modelsObj?.optString("acoustic").orEmpty() }
                    .ifBlank { null }
            }
        }

        val onnxFiles = allFiles.filter { it.name.endsWith(".onnx", ignoreCase = true) }

        fun findByHint(hint: String?): File? {
            if (hint.isNullOrBlank()) return null
            val normHint = hint.replace('\\', '/').trimStart('/')
            return onnxFiles.firstOrNull {
                it.relativeTo(rootDir).path.replace('\\', '/').equals(normHint, ignoreCase = true)
            } ?: onnxFiles.firstOrNull {
                it.name.equals(normHint.substringAfterLast('/'), ignoreCase = true)
            }
        }

        // 1. Speaker Encoder (`speaker_encoder*.onnx`, fallback `codec_encoder` / `audio_tokenizer_encode`)
        val speakerEncoder = findByHint(manifestSpeakerHint)
            ?: onnxFiles.firstOrNull { f ->
                val n = f.name.lowercase(Locale.ROOT)
                n.contains("speaker") || n.contains("ecapa") || n.contains("spk_enc") || n.contains("voice_enc")
            }
            ?: onnxFiles.firstOrNull { f ->
                val n = f.name.lowercase(Locale.ROOT)
                (n.contains("codec") || n.contains("tokenizer") || n.contains("neucodec")) && n.contains("enc")
            }

        // 2. Audio Codec / Vocoder Decoder (STRICTLY exclude LLM decode_step / decoder_model_merged!)
        fun isAudioCodecDecoderName(lower: String): Boolean {
            if (lower.contains("decode_step") || lower.contains("vieneu_decode") ||
                lower.contains("prefill") || lower.contains("backbone") ||
                lower.contains("lm_head") || lower.contains("qwen") ||
                lower.contains("decoder_model_merged")
            ) {
                return false
            }
            return lower.contains("neucodec") ||
                lower.contains("vocoder") ||
                lower.contains("hifigan") ||
                lower.contains("istft") ||
                (lower.contains("codec") && lower.contains("dec")) ||
                (lower.contains("audio_tokenizer") && lower.contains("dec")) ||
                (lower.contains("moss") && lower.contains("dec")) ||
                lower == "codec.onnx" ||
                lower == "decoder.onnx" ||
                lower == "audio_decoder.onnx"
        }

        val codecDecoder = findByHint(manifestCodecHint)
            ?: onnxFiles.firstOrNull { f ->
                f != speakerEncoder && isAudioCodecDecoderName(f.name.lowercase(Locale.ROOT))
            }

        // 3. Split Autoregressive Prefill + Decode-Step (e.g. VieNeu-TTS v3 Turbo)
        val prefillModel = onnxFiles.firstOrNull { f ->
            f != speakerEncoder && f != codecDecoder && f.name.lowercase(Locale.ROOT).contains("prefill")
        }
        val decodeStepModel = onnxFiles.firstOrNull { f ->
            val n = f.name.lowercase(Locale.ROOT)
            f != speakerEncoder && f != codecDecoder && f != prefillModel &&
                (n.contains("decode_step") || n.contains("vieneu_decode") || n.contains("step_decode"))
        }

        // 4. Unified Backbone / Generator / Acoustic Model
        val backboneOrAcoustic = findByHint(manifestBackboneHint)?.takeIf {
            it != speakerEncoder && it != codecDecoder && it != prefillModel && it != decodeStepModel
        } ?: onnxFiles.firstOrNull { f ->
            val n = f.name.lowercase(Locale.ROOT)
            f != speakerEncoder && f != codecDecoder && f != prefillModel && f != decodeStepModel &&
                (n.contains("backbone") || n.contains("vieneu") || n.contains("generator") ||
                    n.contains("acoustic") || n.contains("tts") || n.contains("vits") ||
                    n.contains("decoder_model") || n.startsWith("model"))
        } ?: onnxFiles.firstOrNull { f ->
            val n = f.name.lowercase(Locale.ROOT)
            f != speakerEncoder && f != codecDecoder && f != prefillModel && f != decodeStepModel &&
                !n.contains("enc")
        }

        // Detect codec output sample rate (48kHz for MOSS-Audio-Tokenizer, 24kHz for NeuCodec / VieNeu default)
        val codecLower = codecDecoder?.name?.lowercase(Locale.ROOT).orEmpty()
        val resolvedSampleRate = when {
            codecLower.contains("moss") && manifestSampleRate == 24_000 -> 48_000
            else -> manifestSampleRate
        }

        return DiscoveredOnnxModels(
            modelsRootDir = rootDir,
            sampleRate = resolvedSampleRate,
            speakerEncoderFile = speakerEncoder,
            prefillModelFile = prefillModel,
            decodeStepModelFile = decodeStepModel,
            backboneOrAcousticFile = backboneOrAcoustic,
            codecDecoderFile = codecDecoder,
            g2pDictionaryFile = g2pFile,
            voicesJsonFile = voicesFile,
            tokenizerJsonFile = tokenizerFile,
            configJsonFile = configFile,
            manifestJsonFile = manifestFile,
            allFiles = allFiles,
            copyErrors = copyErrors
        )
    }

    // -------------------------------------------------------------------------
    // G2P, Tokenizer & Voices JSON Parsers
    // -------------------------------------------------------------------------

    fun parseSeaG2pBinary(file: File?): Map<String, IntArray> {
        if (file == null || !file.exists()) return emptyMap()
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return emptyMap()
            val map = HashMap<String, IntArray>(4096)

            // Format A: Binary G2P header ("G2P1" / "G2P")
            if (bytes.size >= 8 && bytes[0] == 'G'.code.toByte() && bytes[1] == '2'.code.toByte()) {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(4)
                val entryCount = bb.int.coerceIn(0, 500_000)
                for (i in 0 until entryCount) {
                    if (bb.remaining() < 4) break
                    val wordLen = bb.short.toInt() and 0xFFFF
                    if (wordLen <= 0 || bb.remaining() < wordLen + 2) break
                    val wordBytes = ByteArray(wordLen)
                    bb.get(wordBytes)
                    val word = String(wordBytes, Charsets.UTF_8).lowercase(Locale.ROOT)
                    val phCount = bb.short.toInt() and 0xFFFF
                    if (bb.remaining() < phCount * 2) break
                    val ids = IntArray(phCount) { bb.short.toInt() and 0xFFFF }
                    map[word] = ids
                }
                if (map.isNotEmpty()) return map
            }

            // Format B: UTF-8 tab-separated lexicon ("từ\t12 34 56" or "từ\tph1 ph2")
            val text = String(bytes, Charsets.UTF_8)
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val parts = trimmed.split('\t', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase(Locale.ROOT)
                    val tokens = parts[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (key.isNotEmpty() && tokens.isNotEmpty()) {
                        val ids = IntArray(tokens.size) { idx ->
                            tokens[idx].toIntOrNull() ?: ((tokens[idx].hashCode() and 0x7FFFFFFF) % 180 + 4)
                        }
                        map[key] = ids
                    }
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun parseTokenizerAndConfig(tokenizerFile: File?, configFile: File?): ParsedTokenizerData? {
        if (tokenizerFile == null || !tokenizerFile.exists()) return null
        return runCatching {
            val root = JSONObject(tokenizerFile.readText(Charsets.UTF_8))
            val tokenToId = HashMap<String, Long>(16_384)
            val idToToken = HashMap<Long, String>(16_384)

            val modelObj = root.optJSONObject("model")
            val vocabObj = modelObj?.optJSONObject("vocab")
            if (vocabObj != null) {
                val keys = vocabObj.keys()
                while (keys.hasNext()) {
                    val tok = keys.next()
                    val id = vocabObj.optLong(tok, -1L)
                    if (id >= 0L) {
                        tokenToId[tok] = id
                        idToToken[id] = tok
                    }
                }
            }
            val addedArr = root.optJSONArray("added_tokens")
            if (addedArr != null) {
                for (i in 0 until addedArr.length()) {
                    val item = addedArr.optJSONObject(i) ?: continue
                    val content = item.optString("content")
                    val id = item.optLong("id", -1L)
                    if (content.isNotEmpty() && id >= 0L) {
                        tokenToId[content] = id
                        idToToken[id] = content
                    }
                }
            }

            var bosId: Long? = tokenToId["<s>"] ?: tokenToId["<|im_start|>"] ?: tokenToId["<bos>"]
            var eosId: Long? = tokenToId["</s>"] ?: tokenToId["<|im_end|>"] ?: tokenToId["<|endoftext|>"]
            var sampleRate: Int? = null

            configFile?.takeIf { it.exists() }?.let { cf ->
                runCatching {
                    val cfg = JSONObject(cf.readText(Charsets.UTF_8))
                    if (cfg.has("bos_token_id")) bosId = cfg.optLong("bos_token_id")
                    if (cfg.has("eos_token_id")) {
                        val eosRaw = cfg.opt("eos_token_id")
                        eosId = when (eosRaw) {
                            is Number -> eosRaw.toLong()
                            is JSONArray -> eosRaw.optLong(0, eosId ?: 2L)
                            else -> eosId
                        }
                    }
                    val sr = cfg.optInt("sample_rate", cfg.optInt("sampling_rate", 0))
                    if (sr in setOf(16_000, 22_050, 24_000, 44_100, 48_000)) {
                        sampleRate = sr
                    }
                }
            }

            val speechStart = tokenToId["<|SPEECH_GENERATION_START|>"]
                ?: tokenToId["<|speech_start|>"]
                ?: tokenToId["<|AUDIO_START|>"]
            val speechEnd = tokenToId["<|SPEECH_GENERATION_END|>"]
                ?: tokenToId["<|speech_end|>"]
                ?: tokenToId["<|AUDIO_END|>"]

            // Detect speech token range (e.g., `<|speech_0|>` .. `<|speech_65535|>`)
            val speech0 = tokenToId["<|speech_0|>"] ?: tokenToId["<|s_0|>"] ?: tokenToId["<|audio_0|>"]
            var speechCount = 0
            if (speech0 != null) {
                for ((tok, _) in tokenToId) {
                    if (tok.startsWith("<|speech_") || tok.startsWith("<|s_") || tok.startsWith("<|audio_")) {
                        speechCount++
                    }
                }
            }

            ParsedTokenizerData(
                tokenToId = tokenToId,
                idToToken = idToToken,
                bosTokenId = bosId,
                eosTokenId = eosId,
                speechStartTokenId = speechStart,
                speechEndTokenId = speechEnd,
                speechTokenBaseId = speech0,
                speechTokenCount = speechCount,
                outputSampleRate = sampleRate
            )
        }.getOrNull()
    }

    fun parseVoicesJson(voicesFile: File?): Map<String, FloatArray> {
        if (voicesFile == null || !voicesFile.exists()) return emptyMap()
        return runCatching {
            val raw = voicesFile.readText(Charsets.UTF_8).trim()
            val map = LinkedHashMap<String, FloatArray>()
            if (raw.startsWith("{")) {
                val root = JSONObject(raw)
                val targetObj = root.optJSONObject("presets")
                    ?: root.optJSONObject("voices")
                    ?: root
                val keys = targetObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val v = targetObj.opt(key)
                    val arr = when (v) {
                        is JSONArray -> v
                        is JSONObject -> v.optJSONArray("embedding")
                            ?: v.optJSONArray("speaker_embedding")
                            ?: v.optJSONArray("codes")
                        else -> null
                    }
                    if (arr != null && arr.length() >= 8) {
                        map[key] = FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() }
                    }
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    // -------------------------------------------------------------------------
    // Standard 80-bin FFT Log-Mel Filterbank (16kHz, 25ms Hann, 10ms Hop, 512 FFT)
    // -------------------------------------------------------------------------

    /**
     * Computes a real 512-point Radix-2 FFT log-mel filterbank spectrogram at 16kHz
     * matching ECAPA-TDNN / SpeechBrain / Kaldi / WeSpeaker `speaker_encoder.onnx` frontends.
     *
     * @param timeFirst if true returns layout `[frames * melBins]` (`[1, T, melBins]`),
     *                  if false returns layout `[melBins * frames]` (`[1, melBins, T]`).
     */
    fun computeLogMelSpectrogram(
        samples16k: FloatArray,
        sampleRate: Int = 16_000,
        melBins: Int = 80,
        timeFirst: Boolean = true
    ): Pair<FloatArray, Int> {
        val nFft = 512
        val winLen = (sampleRate * 25) / 1000 // 400 samples @ 16kHz
        val hopLen = (sampleRate * 10) / 1000 // 160 samples @ 16kHz
        val nFreqs = nFft / 2 + 1

        // Pad short signals to at least 1 frame
        val padded = if (samples16k.size < winLen) {
            FloatArray(winLen).also { System.arraycopy(samples16k, 0, it, 0, samples16k.size) }
        } else {
            samples16k
        }
        val frameCount = (((padded.size - winLen) / hopLen) + 1).coerceIn(1, 800)

        // Precompute Hann window
        val window = FloatArray(winLen) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (winLen - 1).coerceAtLeast(1))).toFloat()
        }

        // Precompute triangular mel filterbank (20 Hz .. sampleRate/2 - 400 Hz)
        val filterbank = buildMelFilterbank(
            melBins = melBins,
            nFft = nFft,
            sampleRate = sampleRate,
            fMin = 20.0,
            fMax = (sampleRate / 2.0 - 400.0).coerceAtLeast(3800.0)
        )

        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val powerSpec = DoubleArray(nFreqs)
        val rawMel = Array(frameCount) { FloatArray(melBins) }

        for (f in 0 until frameCount) {
            val offset = f * hopLen
            re.fill(0.0)
            im.fill(0.0)
            // Apply pre-emphasis (0.97) + Hann window
            var prev = if (offset > 0) padded[offset - 1] else 0f
            for (i in 0 until winLen) {
                val cur = padded[offset + i]
                val preEmph = cur - 0.97f * prev
                prev = cur
                re[i] = (preEmph * window[i]).toDouble()
            }
            fftRadix2(re, im)
            for (k in 0 until nFreqs) {
                powerSpec[k] = re[k] * re[k] + im[k] * im[k]
            }
            val frameMel = rawMel[f]
            for (m in 0 until melBins) {
                val weights = filterbank[m]
                var energy = 0.0
                for (k in 0 until nFreqs) {
                    val w = weights[k]
                    if (w > 0.0) energy += powerSpec[k] * w
                }
                frameMel[m] = ln(energy + 1e-6).toFloat()
            }
        }

        // Cepstral Mean Normalization (CMVN) per mel bin (standard for speaker encoders)
        for (m in 0 until melBins) {
            var mean = 0.0
            for (f in 0 until frameCount) mean += rawMel[f][m]
            mean /= frameCount
            for (f in 0 until frameCount) {
                rawMel[f][m] = (rawMel[f][m] - mean).toFloat()
            }
        }

        val out = FloatArray(frameCount * melBins)
        if (timeFirst) {
            for (f in 0 until frameCount) {
                System.arraycopy(rawMel[f], 0, out, f * melBins, melBins)
            }
        } else {
            for (m in 0 until melBins) {
                val rowOff = m * frameCount
                for (f in 0 until frameCount) {
                    out[rowOff + f] = rawMel[f][m]
                }
            }
        }
        return out to frameCount
    }

    private fun buildMelFilterbank(
        melBins: Int,
        nFft: Int,
        sampleRate: Int,
        fMin: Double,
        fMax: Double
    ): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val nFreqs = nFft / 2 + 1
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val binPoints = IntArray(melBins + 2) { i ->
            val mel = melMin + (melMax - melMin) * i / (melBins + 1)
            val hz = melToHz(mel)
            floor((nFft + 1) * hz / sampleRate).toInt().coerceIn(0, nFreqs - 1)
        }

        return Array(melBins) { m ->
            val filter = DoubleArray(nFreqs)
            val left = binPoints[m]
            val center = binPoints[m + 1].coerceAtLeast(left + 1)
            val right = binPoints[m + 2].coerceAtLeast(center + 1)
            for (k in left until min(center, nFreqs)) {
                filter[k] = (k - left).toDouble() / (center - left).toDouble()
            }
            for (k in center until min(right, nFreqs)) {
                filter[k] = (right - k).toDouble() / (right - center).toDouble()
            }
            filter
        }
    }

    private fun fftRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wStepRe = cos(ang)
            val wStepIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun estimateSpeakerPitchRatio(samples: FloatArray, sampleRate: Int): Float {
        if (samples.size < sampleRate / 4 || sampleRate <= 0) return 1.0f
        val minLag = (sampleRate / 320).coerceAtLeast(16)
        val maxLag = (sampleRate / 85).coerceAtMost(samples.size / 3)
        if (maxLag <= minLag) return 1.0f

        val frameSize = (sampleRate / 20).coerceIn(512, 1600)
        var f0Sum = 0.0
        var voicedFrames = 0
        var start = sampleRate / 5

        while (start + frameSize + maxLag < samples.size && voicedFrames < 40) {
            var energy = 0.0
            for (i in 0 until frameSize) {
                val s = samples[start + i]
                energy += s * s
            }
            if (energy / frameSize > 0.0015) {
                var bestLag = -1
                var bestCorr = 0.0
                for (lag in minLag..maxLag step 2) {
                    var corr = 0.0
                    for (i in 0 until frameSize step 2) {
                        corr += samples[start + i] * samples[start + i + lag]
                    }
                    if (corr > bestCorr) {
                        bestCorr = corr
                        bestLag = lag
                    }
                }
                if (bestLag > 0 && bestCorr > energy * 0.22) {
                    val f0 = sampleRate.toDouble() / bestLag.toDouble()
                    if (f0 in 85.0..320.0) {
                        f0Sum += f0
                        voicedFrames++
                    }
                }
            }
            start += frameSize
        }

        if (voicedFrames == 0) return 1.0f
        val meanF0 = (f0Sum / voicedFrames).toFloat()
        return (1.0f + ((meanF0 - 175f) / 260f)).coerceIn(0.78f, 1.24f)
    }

    fun l2Normalize(vec: FloatArray): FloatArray {
        var sq = 0.0
        for (v in vec) sq += v * v
        val norm = sqrt(sq).toFloat()
        if (norm <= 1e-7f) return vec
        return FloatArray(vec.size) { vec[it] / norm }
    }

    fun normalizeOutputWaveform(raw: FloatArray): FloatArray {
        var peak = 0f
        for (v in raw) {
            if (!v.isFinite()) continue
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak <= 1e-6f) return FloatArray(raw.size)
        val gain = if (peak > 0.92f) 0.90f / peak else if (peak < 0.25f) (0.75f / peak).coerceAtMost(4.0f) else 1.0f
        return FloatArray(raw.size) { i ->
            val v = raw[i]
            if (v.isFinite()) (v * gain).coerceIn(-1f, 1f) else 0f
        }
    }

    fun computeFallbackVoiceprintEmbedding(samples: FloatArray, sampleRate: Int): FloatArray {
        val (mel, frames) = computeLogMelSpectrogram(samples, sampleRate, melBins = 80, timeFirst = true)
        val dim = 192
        val embedding = FloatArray(dim)
        if (frames <= 0) return embedding
        for (m in 0 until 80) {
            var sum = 0.0
            var sqSum = 0.0
            for (f in 0 until frames) {
                val v = mel[f * 80 + m].toDouble()
                sum += v
                sqSum += v * v
            }
            val mean = sum / frames
            val std = sqrt(max(0.0, sqSum / frames - mean * mean))
            embedding[m] = mean.toFloat()
            embedding[80 + m] = std.toFloat()
            if (m < 32) {
                embedding[160 + m] = (mean * 0.5 + std * 0.5).toFloat()
            }
        }
        return l2Normalize(embedding)
    }
}
