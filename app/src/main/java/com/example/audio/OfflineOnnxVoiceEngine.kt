package com.example.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class OnnxVoiceEngineMode {
    UNINITIALIZED,
    ONNX_READY,
    SAFE_TTS_FALLBACK
}

data class OnnxModelManifest(
    val version: String,
    val sampleRate: Int,
    val speakerEncoderFile: String,
    val acousticModelFile: String?,
    val vocoderModelFile: String?,
    val g2pDictionaryFile: String,
    val allFiles: List<String>
)

data class SpeakerEmbeddingResult(
    val embedding: FloatArray,
    val usedOnnxEncoder: Boolean,
    val estimatedPitchRatio: Float
)

data class SynthesizedSentenceAudio(
    val pcmFloat: FloatArray,
    val sampleRate: Int
)

/**
 * 100% Offline ONNX Runtime AI Voice Cloning & Synthesis Engine.
 *
 * Architectural highlights:
 * 1. Dedicated single-thread background worker (`onnx-voice-worker`) so model loading,
 *    `speaker_encoder.onnx` extraction, G2P lookup, and sentence synthesis NEVER block Compose UI.
 * 2. Reads `models/manifest.json` from persistent local cache (`filesDir/ai_models_cache/`) or
 *    bundled APK assets (`assets/models/manifest.json`), caching flat `.onnx` models and
 *    `sea_g2p.bin` on first launch for instant subsequent startup.
 * 3. Safe Fallback Mode: When `models/manifest.json` or `.onnx` weights are not bundled
 *    (e.g., AI Studio preview environment), never throws or shows UI errors; cleanly reports
 *    [OnnxVoiceEngineMode.SAFE_TTS_FALLBACK] so playback routes through native Android TTS
 *    using acoustic speaker characteristics extracted from the recorded voice sample.
 */
class OfflineOnnxVoiceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val modelCacheDir = File(appContext.filesDir, MODEL_CACHE_DIR_NAME)

    // Dedicated worker thread (Android equivalent of a Web Worker)
    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "onnx-voice-worker").apply { isDaemon = true }
    }
    private val workerDispatcher = workerExecutor.asCoroutineDispatcher()

    @Volatile
    var engineMode: OnnxVoiceEngineMode = OnnxVoiceEngineMode.UNINITIALIZED
        private set

    @Volatile
    var targetSampleRate: Int = VoiceSampleRecorder.DEFAULT_TARGET_SAMPLE_RATE
        private set

    private var manifest: OnnxModelManifest? = null
    private var ortEnv: OrtEnvironment? = null
    private var speakerEncoderSession: OrtSession? = null
    private var acousticSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null
    private var g2pLexicon: Map<String, IntArray> = emptyMap()

    /**
     * Initializes models from cache or `assets/models/manifest.json` on the background worker.
     * Safe to call repeatedly; returns immediately once initialized.
     */
    suspend fun ensureInitialized(): OnnxVoiceEngineMode = withContext(workerDispatcher) {
        if (engineMode != OnnxVoiceEngineMode.UNINITIALIZED) {
            return@withContext engineMode
        }

        val resolvedManifest = loadAndCacheManifestAndFiles()
        if (resolvedManifest == null) {
            engineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
            return@withContext engineMode
        }

        manifest = resolvedManifest
        targetSampleRate = resolvedManifest.sampleRate

        // Parse sea_g2p.bin phoneme dictionary into memory cache
        val g2pFile = File(modelCacheDir, resolvedManifest.g2pDictionaryFile)
        if (g2pFile.exists()) {
            g2pLexicon = parseSeaG2pBinary(g2pFile)
        }

        // Load ONNX Runtime sessions cleanly
        val loadedAnyOnnx = runCatching {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            val encoderFile = File(modelCacheDir, resolvedManifest.speakerEncoderFile)
            if (encoderFile.exists() && encoderFile.length() > 64L) {
                speakerEncoderSession = env.createSession(encoderFile.absolutePath, opts)
            }

            resolvedManifest.acousticModelFile?.let { name ->
                val f = File(modelCacheDir, name)
                if (f.exists() && f.length() > 64L) {
                    acousticSession = env.createSession(f.absolutePath, opts)
                }
            }

            resolvedManifest.vocoderModelFile?.let { name ->
                val f = File(modelCacheDir, name)
                if (f.exists() && f.length() > 64L) {
                    vocoderSession = env.createSession(f.absolutePath, opts)
                }
            }

            speakerEncoderSession != null || acousticSession != null
        }.getOrDefault(false)

        engineMode = if (loadedAnyOnnx) {
            OnnxVoiceEngineMode.ONNX_READY
        } else {
            OnnxVoiceEngineMode.SAFE_TTS_FALLBACK
        }
        engineMode
    }

    fun isFullOnnxSynthesisReady(): Boolean {
        return engineMode == OnnxVoiceEngineMode.ONNX_READY && acousticSession != null
    }

    /**
     * Extracts a speaker embedding vector (`FloatArray`) from a 3–8s Mono 16kHz/24kHz voice sample
     * inside the background worker.
     * - Uses `speaker_encoder.onnx` via ONNX Runtime when bundled.
     * - Falls back cleanly to a deterministic 256-dim acoustic voiceprint + F0 pitch ratio when
     *   running in Safe Fallback Mode without `.onnx` binaries.
     */
    suspend fun extractSpeakerEmbedding(
        samples: FloatArray,
        sampleRate: Int
    ): SpeakerEmbeddingResult = withContext(workerDispatcher) {
        ensureInitialized()
        val estimatedPitchRatio = estimateSpeakerPitchRatio(samples, sampleRate)

        val encoder = speakerEncoderSession
        val env = ortEnv
        if (encoder != null && env != null && samples.isNotEmpty()) {
            val onnxEmbedding = runCatching {
                runSpeakerEncoderSession(env, encoder, samples, sampleRate)
            }.getOrNull()
            if (onnxEmbedding != null && onnxEmbedding.isNotEmpty()) {
                return@withContext SpeakerEmbeddingResult(
                    embedding = l2Normalize(onnxEmbedding),
                    usedOnnxEncoder = true,
                    estimatedPitchRatio = estimatedPitchRatio
                )
            }
        }

        // Safe Fallback acoustic voiceprint embedding (256-dim normalized vector)
        val acousticVector = computeAcousticVoiceprintEmbedding(samples, sampleRate)
        SpeakerEmbeddingResult(
            embedding = acousticVector,
            usedOnnxEncoder = false,
            estimatedPitchRatio = estimatedPitchRatio
        )
    }

    /**
     * Synthesizes a single short Vietnamese sentence using the loaded ONNX TTS pipeline +
     * `sea_g2p.bin` + cloned speaker embedding on the background worker.
     *
     * Returns `null` cleanly in Safe Fallback Mode so [BookAudioPlaybackService] routes
     * playback seamlessly to Android's native offline Vietnamese TTS with zero errors.
     */
    suspend fun synthesizeSentence(
        sentence: String,
        speakerEmbedding: FloatArray,
        speed: Float
    ): SynthesizedSentenceAudio? = withContext(workerDispatcher) {
        ensureInitialized()
        val env = ortEnv ?: return@withContext null
        val acoustic = acousticSession ?: return@withContext null
        val cleanText = VietnameseTtsTextNormalizer.normalizeForTts(sentence)
        if (cleanText.isBlank()) return@withContext null

        val clampedSpeed = speed.coerceIn(0.8f, 1.5f)
        val phonemeIds = textToPhonemeIds(cleanText)
        if (phonemeIds.isEmpty()) return@withContext null

        runCatching {
            val acousticOut = runAcousticSession(
                env = env,
                session = acoustic,
                phonemeIds = phonemeIds,
                speakerEmbedding = speakerEmbedding,
                speed = clampedSpeed
            ) ?: return@runCatching null

            val vocoder = vocoderSession
            val waveform = if (vocoder != null) {
                runVocoderSession(env, vocoder, acousticOut) ?: acousticOut
            } else {
                acousticOut
            }

            if (waveform.size < 256) return@runCatching null
            SynthesizedSentenceAudio(
                pcmFloat = normalizeOutputWaveform(waveform),
                sampleRate = targetSampleRate
            )
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Manifest & Persistent Cache Loading (`assets/models/` -> `filesDir/ai_models_cache/`)
    // -------------------------------------------------------------------------

    private fun loadAndCacheManifestAndFiles(): OnnxModelManifest? {
        val cachedManifestFile = File(modelCacheDir, MANIFEST_FILE_NAME)

        // 1. Check APK assets/models/manifest.json first so updates are synced to cache
        val assetManifestJson = runCatching {
            appContext.assets.open("$ASSETS_MODELS_DIR/$MANIFEST_FILE_NAME")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()

        val manifestJsonText = when {
            !assetManifestJson.isNullOrBlank() -> {
                val parsedAsset = parseManifestJson(assetManifestJson) ?: return null
                val cachedVersion = if (cachedManifestFile.exists()) {
                    runCatching {
                        JSONObject(cachedManifestFile.readText(Charsets.UTF_8)).optString("version")
                    }.getOrNull()
                } else null

                val bundledAssetFiles = runCatching {
                    appContext.assets.list(ASSETS_MODELS_DIR)?.toList().orEmpty()
                }.getOrDefault(emptyList())
                    .filter { it.isNotBlank() && it != MANIFEST_FILE_NAME }

                val filesToSync = (parsedAsset.allFiles + bundledAssetFiles).distinct()

                val needsSync = cachedVersion != parsedAsset.version ||
                    filesToSync.any { !File(modelCacheDir, it.substringAfterLast('/')).exists() }

                if (needsSync) {
                    modelCacheDir.mkdirs()
                    filesToSync.forEach { fileName ->
                        copyAssetModelFileToCache(fileName)
                    }
                    cachedManifestFile.writeText(assetManifestJson, Charsets.UTF_8)
                }
                assetManifestJson
            }

            cachedManifestFile.exists() -> {
                runCatching { cachedManifestFile.readText(Charsets.UTF_8) }.getOrNull()
            }

            else -> null
        } ?: return null

        return parseManifestJson(manifestJsonText)
    }

    private fun copyAssetModelFileToCache(fileName: String) {
        val cleanName = fileName.substringAfterLast('/').trim()
        if (cleanName.isEmpty()) return
        val destFile = File(modelCacheDir, cleanName)
        runCatching {
            appContext.assets.open("$ASSETS_MODELS_DIR/$cleanName").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
        }
    }

    private fun parseManifestJson(rawJson: String): OnnxModelManifest? {
        return runCatching {
            val root = JSONObject(rawJson)
            val version = root.optString("version", "1.0")
            val sampleRate = root.optInt("sampleRate", root.optInt("sample_rate", 16_000))
                .let { if (it == 24_000 || it == 22_050 || it == 16_000) it else 16_000 }

            val filesList = mutableListOf<String>()
            val filesArr = root.optJSONArray("files")
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    val item = filesArr.opt(i)
                    when (item) {
                        is String -> if (item.isNotBlank()) filesList.add(item.substringAfterLast('/'))
                        is JSONObject -> {
                            val name = item.optString("name", item.optString("file", item.optString("path")))
                                .substringAfterLast('/')
                                .trim()
                            if (name.isNotEmpty()) filesList.add(name)
                        }
                    }
                }
            }

            val modelsObj = root.optJSONObject("models")
            val speakerEncoder = root.optString("speaker_encoder")
                .ifBlank { modelsObj?.optString("speaker_encoder").orEmpty() }
                .ifBlank { filesList.firstOrNull { it.contains("speaker", ignoreCase = true) }.orEmpty() }
                .ifBlank { "speaker_encoder.onnx" }
                .substringAfterLast('/')

            val g2pDict = root.optString("g2p")
                .ifBlank { modelsObj?.optString("g2p").orEmpty() }
                .ifBlank { filesList.firstOrNull { it.contains("g2p", ignoreCase = true) }.orEmpty() }
                .ifBlank { "sea_g2p.bin" }
                .substringAfterLast('/')

            val acoustic = root.optString("acoustic")
                .ifBlank { root.optString("tts") }
                .ifBlank { modelsObj?.optString("acoustic").orEmpty() }
                .ifBlank { modelsObj?.optString("tts").orEmpty() }
                .ifBlank {
                    filesList.firstOrNull {
                        it.endsWith(".onnx", ignoreCase = true) &&
                            !it.equals(speakerEncoder, ignoreCase = true) &&
                            !it.contains("vocoder", ignoreCase = true) &&
                            !it.contains("hifigan", ignoreCase = true)
                    }.orEmpty()
                }
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }

            val vocoder = root.optString("vocoder")
                .ifBlank { modelsObj?.optString("vocoder").orEmpty() }
                .ifBlank {
                    filesList.firstOrNull {
                        it.endsWith(".onnx", ignoreCase = true) &&
                            (it.contains("vocoder", ignoreCase = true) || it.contains("hifigan", ignoreCase = true))
                    }.orEmpty()
                }
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }

            val combinedFiles = linkedSetOf<String>().apply {
                addAll(filesList)
                add(speakerEncoder)
                add(g2pDict)
                if (acoustic != null) add(acoustic)
                if (vocoder != null) add(vocoder)
            }.toList()

            OnnxModelManifest(
                version = version,
                sampleRate = sampleRate,
                speakerEncoderFile = speakerEncoder,
                acousticModelFile = acoustic,
                vocoderModelFile = vocoder,
                g2pDictionaryFile = g2pDict,
                allFiles = combinedFiles
            )
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // ONNX Runtime Execution Helpers
    // -------------------------------------------------------------------------

    private fun runSpeakerEncoderSession(
        env: OrtEnvironment,
        session: OrtSession,
        samples: FloatArray,
        sampleRate: Int
    ): FloatArray? {
        val inputName = session.inputNames.firstOrNull() ?: return null
        val info = session.inputInfo[inputName]?.info as? TensorInfo
        val shape = info?.shape

        val tensor = when {
            shape != null && shape.size == 3 -> {
                // Model expects [1, frames, mel_bins] (e.g. 80-bin log-mel spectrogram)
                val melBins = shape[2].toInt().takeIf { it in 40..128 } ?: 80
                val mel = computeSimpleLogMelFrames(samples, sampleRate, melBins)
                val frames = (mel.size / melBins).coerceAtLeast(1)
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(mel),
                    longArrayOf(1L, frames.toLong(), melBins.toLong())
                )
            }
            shape != null && shape.size == 2 -> {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(samples),
                    longArrayOf(1L, samples.size.toLong())
                )
            }
            else -> {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(samples),
                    longArrayOf(samples.size.toLong())
                )
            }
        }

        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                if (result.size() <= 0) return null
                flattenOnnxFloatOutput(result[0].value)
            }
        } finally {
            tensor.close()
        }
    }

    private fun runAcousticSession(
        env: OrtEnvironment,
        session: OrtSession,
        phonemeIds: LongArray,
        speakerEmbedding: FloatArray,
        speed: Float
    ): FloatArray? {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, nodeInfo) in session.inputInfo) {
                val lower = name.lowercase(Locale.ROOT)
                val tensorInfo = nodeInfo.info as? TensorInfo
                val isInt64 = tensorInfo?.type == ai.onnxruntime.OnnxJavaType.INT64

                val tensor = when {
                    lower.contains("len") -> {
                        OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(longArrayOf(phonemeIds.size.toLong())),
                            longArrayOf(1L)
                        )
                    }
                    lower.contains("spk") || lower.contains("embed") || lower.contains("style") || lower.contains("voice") -> {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(speakerEmbedding),
                            longArrayOf(1L, speakerEmbedding.size.toLong())
                        )
                    }
                    lower.contains("scale") || lower.contains("speed") || lower.contains("alpha") -> {
                        val lengthScale = (1.0f / speed).coerceIn(0.65f, 1.35f)
                        val count = tensorInfo?.shape?.lastOrNull()?.toInt()?.takeIf { it in 1..4 } ?: 1
                        val vals = FloatArray(count) { idx ->
                            when (idx) {
                                0 -> 0.667f
                                1 -> lengthScale
                                else -> 0.8f
                            }
                        }
                        if (count == 1) {
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf(1L))
                        } else {
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(vals), longArrayOf(count.toLong()))
                        }
                    }
                    isInt64 || lower.contains("input") || lower.contains("token") || lower.contains("phoneme") || lower.contains("text") -> {
                        OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(phonemeIds),
                            longArrayOf(1L, phonemeIds.size.toLong())
                        )
                    }
                    else -> {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(speakerEmbedding),
                            longArrayOf(1L, speakerEmbedding.size.toLong())
                        )
                    }
                }
                inputs[name] = tensor
            }

            return session.run(inputs).use { result ->
                if (result.size() <= 0) return null
                flattenOnnxFloatOutput(result[0].value)
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun runVocoderSession(
        env: OrtEnvironment,
        session: OrtSession,
        melFlat: FloatArray
    ): FloatArray? {
        val inputName = session.inputNames.firstOrNull() ?: return null
        val info = session.inputInfo[inputName]?.info as? TensorInfo
        val shape = info?.shape
        val melBins = shape?.getOrNull(1)?.toInt()?.takeIf { it in 40..128 } ?: 80
        val frames = (melFlat.size / melBins).coerceAtLeast(1)
        val trimmed = if (frames * melBins == melFlat.size) melFlat else melFlat.copyOf(frames * melBins)

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(trimmed),
            longArrayOf(1L, melBins.toLong(), frames.toLong())
        )
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                if (result.size() <= 0) return null
                flattenOnnxFloatOutput(result[0].value)
            }
        } finally {
            tensor.close()
        }
    }

    private fun flattenOnnxFloatOutput(raw: Any?): FloatArray? {
        val out = ArrayList<Float>()
        fun collect(obj: Any?) {
            when (obj) {
                is FloatArray -> for (v in obj) out.add(v)
                is Array<*> -> for (item in obj) collect(item)
                is Float -> out.add(obj)
            }
        }
        collect(raw)
        if (out.isEmpty()) return null
        return FloatArray(out.size) { out[it] }
    }

    // -------------------------------------------------------------------------
    // `sea_g2p.bin` Vietnamese Phoneme Dictionary Parser & Tokenizer
    // -------------------------------------------------------------------------

    private fun parseSeaG2pBinary(file: File): Map<String, IntArray> {
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return emptyMap()
            val map = HashMap<String, IntArray>(2048)

            // Format A: Binary count + length-prefixed UTF-8 entries (magic "G2P1" or uint32 count)
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

            // Format B: UTF-8 tab/space-separated lexicon ("từ\t12 34 56" or "từ\tph1 ph2")
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

    private fun textToPhonemeIds(sentence: String): LongArray {
        val tokens = ArrayList<Long>(sentence.length * 2)
        tokens.add(1L) // BOS
        val words = sentence.lowercase(Locale("vi", "VN")).split(Regex("\\s+"))
        for (rawWord in words) {
            if (rawWord.isEmpty()) continue
            val cleanWord = rawWord.trim { !it.isLetterOrDigit() }
            val dictIds = g2pLexicon[cleanWord]
            if (dictIds != null) {
                for (id in dictIds) tokens.add(id.toLong())
            } else {
                for (ch in rawWord) {
                    tokens.add(((ch.code % 192) + 4).toLong())
                }
            }
            tokens.add(3L) // word boundary pause token
        }
        tokens.add(2L) // EOS
        return LongArray(tokens.size) { tokens[it] }
    }

    // -------------------------------------------------------------------------
    // Acoustic DSP Feature & Pitch Ratio Extraction (Safe Fallback & Mel Frontend)
    // -------------------------------------------------------------------------

    private fun estimateSpeakerPitchRatio(samples: FloatArray, sampleRate: Int): Float {
        if (samples.size < sampleRate / 4 || sampleRate <= 0) return 1.0f
        // Autocorrelation F0 estimation over voiced mid-section frames (85 Hz .. 320 Hz)
        val minLag = (sampleRate / 320).coerceAtLeast(16)
        val maxLag = (sampleRate / 85).coerceAtMost(samples.size / 3)
        if (maxLag <= minLag) return 1.0f

        val frameSize = (sampleRate / 20).coerceIn(512, 1600)
        val step = frameSize
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
            start += step
        }

        if (voicedFrames == 0) return 1.0f
        val meanF0 = (f0Sum / voicedFrames).toFloat()
        // Neutral baseline ~175 Hz -> map smoothly into Android TTS natural pitch range [0.78, 1.24]
        val ratio = 1.0f + ((meanF0 - 175f) / 260f)
        return ratio.coerceIn(0.78f, 1.24f)
    }

    private fun computeAcousticVoiceprintEmbedding(
        samples: FloatArray,
        sampleRate: Int
    ): FloatArray {
        val dim = 256
        val embedding = FloatArray(dim)
        if (samples.isEmpty()) return embedding

        val bands = 32
        val frameSize = 512
        val hop = 256
        val bandEnergy = DoubleArray(bands)
        val bandDelta = DoubleArray(bands)
        val prevBand = DoubleArray(bands)
        var frameCount = 0

        var pos = 0
        while (pos + frameSize <= samples.size && frameCount < 160) {
            for (b in 0 until bands) {
                val freq = 100.0 + (b * b) * 7.5
                val omega = 2.0 * PI * freq / max(sampleRate, 8000)
                var re = 0.0
                var im = 0.0
                for (n in 0 until frameSize step 4) {
                    val s = samples[pos + n].toDouble()
                    val angle = omega * n
                    re += s * cos(angle)
                    im -= s * sin(angle)
                }
                val mag = ln(1.0 + sqrt(re * re + im * im))
                bandEnergy[b] += mag
                if (frameCount > 0) {
                    bandDelta[b] += abs(mag - prevBand[b])
                }
                prevBand[b] = mag
            }
            frameCount++
            pos += hop
        }

        val invFrames = 1.0 / max(frameCount, 1)
        for (i in 0 until dim) {
            val b = i % bands
            val cycle = i / bands
            val value = when (cycle % 4) {
                0 -> bandEnergy[b] * invFrames
                1 -> bandDelta[b] * invFrames
                2 -> (bandEnergy[b] - bandEnergy[(b + 1) % bands]) * invFrames
                else -> (bandEnergy[b] + bandDelta[(b + 3) % bands]) * invFrames * 0.5
            }
            embedding[i] = value.toFloat()
        }
        return l2Normalize(embedding)
    }

    private fun computeSimpleLogMelFrames(
        samples: FloatArray,
        sampleRate: Int,
        melBins: Int
    ): FloatArray {
        val hop = (sampleRate / 100).coerceAtLeast(160)
        val win = hop * 2
        val frameCount = ((samples.size - win) / hop).coerceIn(1, 400)
        val out = FloatArray(frameCount * melBins)
        for (f in 0 until frameCount) {
            val offset = f * hop
            for (m in 0 until melBins) {
                val freq = 80.0 + (m.toDouble() / melBins) * (sampleRate * 0.42)
                val omega = 2.0 * PI * freq / sampleRate
                var re = 0.0
                var im = 0.0
                val limit = min(win, samples.size - offset)
                for (n in 0 until limit step 4) {
                    val s = samples[offset + n].toDouble()
                    val angle = omega * n
                    re += s * cos(angle)
                    im -= s * sin(angle)
                }
                out[f * melBins + m] = ln(1e-5 + sqrt(re * re + im * im)).toFloat()
            }
        }
        return out
    }

    private fun normalizeOutputWaveform(raw: FloatArray): FloatArray {
        var peak = 0f
        for (v in raw) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak <= 1e-5f) return raw
        val gain = if (peak > 0.95f) 0.92f / peak else 1.0f
        if (gain == 1.0f) return raw
        return FloatArray(raw.size) { (raw[it] * gain).coerceIn(-1f, 1f) }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sq = 0.0
        for (v in vec) sq += v * v
        val norm = sqrt(sq).toFloat()
        if (norm <= 1e-7f) return vec
        return FloatArray(vec.size) { vec[it] / norm }
    }

    fun close() {
        workerExecutor.execute {
            runCatching { speakerEncoderSession?.close() }
            runCatching { acousticSession?.close() }
            runCatching { vocoderSession?.close() }
            speakerEncoderSession = null
            acousticSession = null
            vocoderSession = null
        }
        workerExecutor.shutdown()
    }

    companion object {
        private const val ASSETS_MODELS_DIR = "models"
        private const val MODEL_CACHE_DIR_NAME = "ai_models_cache"
        private const val MANIFEST_FILE_NAME = "manifest.json"
    }
}
