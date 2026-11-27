package com.example.audio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Preset voice entry parsed from official `pnnbao-ump` `voices.json` (`presets.<id>.text` & `codes`).
 */
data class VieNeuPresetVoice(
    val id: String,
    val description: String,
    val referenceText: String,
    val referenceCodes: IntArray,
    val voiceEmbedding: FloatArray? = null
) {
    val storageId: String
        get() = OnnxModelAssetHelper.presetStorageId(id)

    val displayLabel: String
        get() = if (description.isNotBlank() && !description.equals(id, ignoreCase = true)) {
            "$id ($description)"
        } else {
            id
        }
}

data class LightweightAiModelCatalog(
    val hasGgufModel: Boolean,
    val hasDecoderModel: Boolean,
    val presetVoices: List<VieNeuPresetVoice>
) {
    val modelsPresent: Boolean
        get() = hasGgufModel && hasDecoderModel

    val statusBadgeText: String
        get() = if (modelsPresent) {
            "Model: OK | Voices: ${presetVoices.size}"
        } else {
            "Model: Thiếu | Voices: ${presetVoices.size}"
        }
}

/**
 * Discovered official `pnnbao97/VieNeu-TTS` GGUF + NeuCodec ONNX files inside `context.filesDir/models/`.
 */
data class DiscoveredOnnxModels(
    val modelsRootDir: File,
    val sampleRate: Int,
    val ggufBackboneFile: File?,
    val codecEncoderFile: File?,
    val codecDecoderFile: File?,
    val espeakDictFile: File?,
    val voicesJsonFile: File?,
    val manifestJsonFile: File?,
    val allFiles: List<File>,
    val copyErrors: List<String>
) {
    val hasGgufBackbone: Boolean
        get() = ggufBackboneFile != null && ggufBackboneFile.exists()

    val hasAnyModel: Boolean
        get() = hasGgufBackbone || codecDecoderFile != null || codecEncoderFile != null

    fun describeSummary(): String {
        val parts = mutableListOf<String>()
        ggufBackboneFile?.let { parts.add("gguf=${it.name}") }
        codecEncoderFile?.let { parts.add("enc=${it.name}") }
        codecDecoderFile?.let { parts.add("dec=${it.name}") }
        espeakDictFile?.let { parts.add("g2p=${it.name}") }
        voicesJsonFile?.let { parts.add("voices=${it.name}") }
        return if (parts.isEmpty()) "Không có file model trong ${modelsRootDir.absolutePath}" else parts.joinToString(", ")
    }
}

/**
 * Single file entry inside `assets/models` or `context.filesDir/models` with exact size in bytes & MB.
 */
data class ModelFileEntry(
    val relativePath: String,
    val sizeBytes: Long
) {
    val sizeMbLabel: String
        get() = if (sizeBytes >= 0L) {
            String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
        } else {
            "N/A (nén)"
        }
}

/**
 * Full diagnostic report for the "Kiểm tra hệ thống AI" dialog.
 */
data class AiSystemDiagnosticReport(
    val inspectedAtMillis: Long,
    val engineMode: OnnxVoiceEngineMode,
    val ortEnvironmentStatus: String,
    val assetsModelsFiles: List<ModelFileEntry>,
    val totalAssetsBytes: Long,
    val filesDirModelsPath: String,
    val filesDirModelsFiles: List<ModelFileEntry>,
    val totalFilesDirBytes: Long,
    val mappedRoles: Map<String, String>,
    val loadedSessions: Map<String, String>,
    val metadataSummary: Map<String, String> = emptyMap(),
    val sessionErrors: Map<String, String>,
    val copyErrors: List<String>
) {
    fun toPlainTextReport(): String = buildString {
        appendLine("=== KIỂM TRA HỆ THỐNG AI (VieNeu-TTS GGUF + NeuCodec ONNX) ===")
        appendLine("Chế độ Engine: $engineMode")
        appendLine("OrtEnvironment / Llama.cpp: $ortEnvironmentStatus")
        appendLine()
        appendLine("--- 1. THƯ MỤC assets/models (${assetsModelsFiles.size} file • ${formatMb(totalAssetsBytes)}) ---")
        if (assetsModelsFiles.isEmpty()) {
            appendLine("(Trống — APK chưa chứa thư mục assets/models)")
        } else {
            assetsModelsFiles.forEach { appendLine(" • ${it.relativePath}  [${it.sizeMbLabel}]") }
        }
        appendLine()
        appendLine("--- 2. THƯ MỤC filesDir/models (${filesDirModelsFiles.size} file • ${formatMb(totalFilesDirBytes)}) ---")
        appendLine("Đường dẫn: $filesDirModelsPath")
        if (filesDirModelsFiles.isEmpty()) {
            appendLine("(Trống — chưa có file nào được sao chép sang bộ nhớ nội bộ)")
        } else {
            filesDirModelsFiles.forEach { appendLine(" • ${it.relativePath}  [${it.sizeMbLabel}]") }
        }
        appendLine()
        appendLine("--- 3. ÁNH XẠ MODEL CHÍNH THỨC (pnnbao97/VieNeu-TTS) ---")
        mappedRoles.forEach { (role, value) -> appendLine(" • $role: $value") }
        appendLine()
        appendLine("--- 4. ENGINE ĐÃ NẠP (GGUF + NeuCodec ONNX) ---")
        if (loadedSessions.isEmpty()) {
            appendLine("(Chưa nạp được engine nào)")
        } else {
            loadedSessions.forEach { (role, sig) -> appendLine(" ✓ $role: $sig") }
        }
        if (metadataSummary.isNotEmpty()) {
            appendLine()
            appendLine("--- 5. METADATA (G2P eSpeak-NG / Voices / Special Tokens) ---")
            metadataSummary.forEach { (k, v) -> appendLine(" • $k: $v") }
        }
        if (copyErrors.isNotEmpty()) {
            appendLine()
            appendLine("--- LỖI SAO CHÉP ASSETS ---")
            copyErrors.forEach { appendLine(" ✗ $it") }
        }
        if (sessionErrors.isNotEmpty()) {
            appendLine()
            appendLine("--- LỖI NẠP ENGINE (NGUYÊN VĂN) ---")
            sessionErrors.forEach { (role, err) -> appendLine(" ✗ [$role]\n$err") }
        }
    }

    companion object {
        fun formatMb(bytes: Long): String =
            String.format(Locale.US, "%.2f MB", bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))
    }
}

/**
 * Result of an interactive test button inside "Kiểm tra hệ thống AI".
 */
data class AiDiagnosticTestResult(
    val title: String,
    val success: Boolean,
    val elapsedMs: Long,
    val detailLog: String
)

/**
 * Asset synchronizer, model resolver, official `voices.json` parser, and diagnostic helper
 * for `pnnbao97/VieNeu-TTS` (GGUF + NeuCodec ONNX).
 */
object OnnxModelAssetHelper {

    private const val TAG = "VieNeuModelAssets"
    const val ASSETS_MODELS_DIR = "models"
    const val FILES_MODELS_DIR = "models"
    const val PRESET_VOICE_ID_PREFIX = "preset:"

    fun presetStorageId(presetId: String): String = "$PRESET_VOICE_ID_PREFIX$presetId"

    fun extractPresetId(storageId: String?): String? =
        storageId?.takeIf { it.startsWith(PRESET_VOICE_ID_PREFIX) }
            ?.removePrefix(PRESET_VOICE_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /**
     * Lightweight, fast IO-only inspection of `assets/models/` and `filesDir/models/` that reads
     * `voices.json` and checks whether the `.gguf` backbone and NeuCodec `.onnx` decoder are present,
     * WITHOUT unpacking the ~200MB `.gguf` file or loading any native libraries at app startup.
     */
    fun inspectLightweightAiCatalog(context: Context): LightweightAiModelCatalog {
        val appCtx = context.applicationContext
        val (assetEntries, _) = inspectAssetsModelsFiles(appCtx)
        val (_, filesEntries, _) = inspectFilesDirModelsFiles(appCtx)
        val allNames = (assetEntries.map { it.relativePath } + filesEntries.map { it.relativePath })
            .map { it.lowercase(Locale.ROOT) }

        val hasGguf = allNames.any { it.endsWith(".gguf") }
        val hasDecoder = allNames.any { n ->
            n.endsWith(".onnx") && (n.contains("dec") || n.contains("neucodec") || n.endsWith("/model.onnx") || n == "model.onnx")
        }

        val filesVoices = File(File(appCtx.filesDir, FILES_MODELS_DIR), "voices.json")
        val presets = when {
            filesVoices.exists() && filesVoices.length() > 16L -> parseOfficialVoicesJson(filesVoices)
            else -> {
                val assetVoiceRel = assetEntries.firstOrNull { entry ->
                    val n = entry.relativePath.lowercase(Locale.ROOT)
                    n == "voices.json" || n.endsWith("/voices.json") || (n.contains("voice") && n.endsWith(".json"))
                }?.relativePath
                if (assetVoiceRel != null) {
                    runCatching {
                        appCtx.assets.open("$ASSETS_MODELS_DIR/$assetVoiceRel").bufferedReader(Charsets.UTF_8).use {
                            parseOfficialVoicesJsonString(it.readText())
                        }
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        }

        return LightweightAiModelCatalog(
            hasGgufModel = hasGguf,
            hasDecoderModel = hasDecoder,
            presetVoices = presets
        )
    }

    /**
     * Sample Vietnamese sentence used when running full AI diagnostics.
     */
    const val AI_VOICE_TEST_SENTENCE =
        "Nó vẫn hiển thị rất nhiều, vì thế tôi đã bay ra ngoài hà nội để cảm nhận sắc xuân ở đây thật là tươi đẹp."

    /**
     * Short Vietnamese preview sentence used right after tapping "Tạo" so preview synthesis
     * finishes fast with minimal KV-cache & RAM overhead on mobile devices.
     */
    const val AI_VOICE_QUICK_PREVIEW_SENTENCE =
        "Xin chào, đây là giọng đọc thử nghiệm tiếng Việt."

    /**
     * Ready-to-read Vietnamese reference script offered to users recording their own voice sample.
     */
    const val SUGGESTED_RECORDING_SCRIPT =
        "Xin chào, đây là giọng đọc mẫu của tôi để ứng dụng nhân bản và đọc sách nói tiếng Việt thật tự nhiên."

    fun formatFullThrowable(t: Throwable): String = buildString {
        append(t.toString())
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 5) {
            append("\n  Cause[$depth]: ")
            append(cause.toString())
            val msg = cause.message
            if (!msg.isNullOrBlank() && !cause.toString().contains(msg)) {
                append(" | message=").append(msg)
            }
            cause = cause.cause
            depth++
        }
    }

    fun inspectAssetsModelsFiles(context: Context): Pair<List<ModelFileEntry>, Long> {
        val appCtx = context.applicationContext
        val entries = mutableListOf<ModelFileEntry>()
        fun walk(relPath: String) {
            val children = runCatching { appCtx.assets.list(relPath)?.toList().orEmpty() }
                .getOrDefault(emptyList())
            for (child in children) {
                if (child.isBlank()) continue
                val childPath = "$relPath/$child"
                val sub = runCatching { appCtx.assets.list(childPath) }.getOrNull()
                if (sub != null && sub.isNotEmpty()) {
                    walk(childPath)
                } else {
                    val size = runCatching {
                        appCtx.assets.openFd(childPath).use { it.length }
                    }.getOrElse {
                        runCatching {
                            appCtx.assets.open(childPath).use { it.available().toLong() }
                        }.getOrElse { -1L }
                    }
                    val displayRel = childPath.removePrefix("$ASSETS_MODELS_DIR/")
                    entries.add(ModelFileEntry(relativePath = displayRel, sizeBytes = size))
                }
            }
        }
        walk(ASSETS_MODELS_DIR)
        entries.sortBy { it.relativePath }
        val total = entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return entries to total
    }

    fun inspectFilesDirModelsFiles(context: Context): Triple<String, List<ModelFileEntry>, Long> {
        val destRoot = File(context.applicationContext.filesDir, FILES_MODELS_DIR)
        if (!destRoot.exists()) {
            return Triple(destRoot.absolutePath, emptyList(), 0L)
        }
        val entries = destRoot.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { file ->
                ModelFileEntry(
                    relativePath = file.relativeTo(destRoot).path.replace('\\', '/'),
                    sizeBytes = file.length()
                )
            }
            .sortedBy { it.relativePath }
            .toList()
        val total = entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return Triple(destRoot.absolutePath, entries, total)
    }

    /**
     * Recursively copies all files from `assets/models/` into `context.filesDir/models/` using
     * streaming 64KB buffers (zero large heap allocations) and removes stale legacy files that are
     * no longer present in `assets/models/` (cleaning up old 21-file ONNX bundles automatically).
     */
    fun syncAssetsModelsToFilesDir(context: Context): DiscoveredOnnxModels {
        val appCtx = context.applicationContext
        val destRoot = File(appCtx.filesDir, FILES_MODELS_DIR)
        val copyErrors = mutableListOf<String>()
        val syncedRelPaths = HashSet<String>()

        val topEntries = runCatching {
            appCtx.assets.list(ASSETS_MODELS_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        if (topEntries.isNotEmpty()) {
            destRoot.mkdirs()
            copyAssetDirectoryRecursive(
                context = appCtx,
                assetRelPath = ASSETS_MODELS_DIR,
                destDir = destRoot,
                destRoot = destRoot,
                syncedRelPaths = syncedRelPaths,
                errors = copyErrors
            )

            // Clean stale legacy files from previous APK installs (e.g., old 21-file web ONNX bundle)
            if (syncedRelPaths.isNotEmpty()) {
                destRoot.walkBottomUp().forEach { f ->
                    if (f == destRoot) return@forEach
                    if (f.isFile) {
                        val rel = f.relativeTo(destRoot).path.replace('\\', '/')
                        if (rel !in syncedRelPaths) {
                            runCatching { f.delete() }
                        }
                    } else if (f.isDirectory && (f.list()?.isEmpty() == true)) {
                        runCatching { f.delete() }
                    }
                }
            }
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
        destRoot: File,
        syncedRelPaths: MutableSet<String>,
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
                copyAssetDirectoryRecursive(context, childAssetPath, destTarget, destRoot, syncedRelPaths, errors)
            } else {
                val rel = destTarget.relativeTo(destRoot).path.replace('\\', '/')
                syncedRelPaths.add(rel)
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

        if (destFile.exists() && destFile.length() > 0L) {
            if (expectedLength > 0L && destFile.length() == expectedLength) {
                return
            }
            if (expectedLength < 0L && destFile.length() > 16L) {
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
            Log.i(TAG, "Copied $assetPath -> ${destFile.absolutePath} (${destFile.length()} bytes)")
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
        val voicesFile = allFiles.firstOrNull {
            it.name.equals("voices.json", ignoreCase = true) ||
                (it.name.contains("voice", ignoreCase = true) && it.name.endsWith(".json", ignoreCase = true))
        }
        val espeakDictFile = allFiles.firstOrNull {
            it.name.equals("vi_espeak_ipa.tsv", ignoreCase = true) ||
                (it.name.contains("espeak", ignoreCase = true) && it.name.endsWith(".tsv", ignoreCase = true))
        }

        val ggufFile = allFiles
            .filter { it.name.endsWith(".gguf", ignoreCase = true) }
            .maxByOrNull { it.length() }

        val onnxFiles = allFiles.filter { it.name.endsWith(".onnx", ignoreCase = true) }

        val codecEncoder = onnxFiles.firstOrNull { f ->
            val n = f.name.lowercase(Locale.ROOT)
            (n.contains("enc") || n.contains("distill")) && !n.contains("dec")
        }

        val codecDecoder = onnxFiles.firstOrNull { f ->
            val n = f.name.lowercase(Locale.ROOT)
            f != codecEncoder && (n.contains("dec") || n.contains("neucodec") || n == "model.onnx")
        } ?: onnxFiles.firstOrNull { it != codecEncoder }

        return DiscoveredOnnxModels(
            modelsRootDir = rootDir,
            sampleRate = NeuCodecOnnxEngine.DECODER_SAMPLE_RATE,
            ggufBackboneFile = ggufFile,
            codecEncoderFile = codecEncoder,
            codecDecoderFile = codecDecoder,
            espeakDictFile = espeakDictFile,
            voicesJsonFile = voicesFile,
            manifestJsonFile = manifestFile,
            allFiles = allFiles,
            copyErrors = copyErrors
        )
    }

    /**
     * Parses official `pnnbao-ump` `voices.json` (`presets` object with `text` + `codes` array).
     */
    fun parseOfficialVoicesJson(voicesFile: File?): List<VieNeuPresetVoice> {
        if (voicesFile == null || !voicesFile.exists()) return emptyList()
        return runCatching {
            parseOfficialVoicesJsonString(voicesFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun parseOfficialVoicesJsonString(rawJson: String): List<VieNeuPresetVoice> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawJson)
            val defaultVoiceId = root.optString("default_voice").trim()
            val presetsObj = root.optJSONObject("presets")
                ?: root.optJSONObject("voices")
                ?: root

            val list = ArrayList<VieNeuPresetVoice>()
            val keys = presetsObj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = presetsObj.optJSONObject(id) ?: continue
                val text = obj.optString("text")
                    .ifBlank { obj.optString("ref_text") }
                    .ifBlank { obj.optString("transcript") }
                    .trim()
                val desc = obj.optString("description")
                    .ifBlank { obj.optString("name") }
                    .ifBlank { id }
                    .trim()
                val codesArr = obj.optJSONArray("codes")
                    ?: obj.optJSONArray("ref_codes")
                    ?: obj.optJSONArray("referenceCodes")
                    ?: continue

                val codes = flattenJsonIntArray(codesArr)
                val embArr = obj.optJSONArray("voice_embedding")
                    ?: obj.optJSONArray("voice")
                    ?: obj.optJSONArray("speaker_embedding")
                    ?: obj.optJSONArray("embedding")
                val voiceEmb = embArr?.let { flattenJsonFloatArray(it) }?.takeIf { it.isNotEmpty() }
                if (codes.size >= 8) {
                    list.add(
                        VieNeuPresetVoice(
                            id = id,
                            description = desc,
                            referenceText = text,
                            referenceCodes = codes,
                            voiceEmbedding = voiceEmb
                        )
                    )
                }
            }
            if (defaultVoiceId.isNotBlank()) {
                list.sortByDescending { it.id.equals(defaultVoiceId, ignoreCase = true) }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun flattenJsonIntArray(arr: JSONArray): IntArray {
        val out = ArrayList<Int>(arr.length())
        fun walk(a: JSONArray) {
            for (i in 0 until a.length()) {
                when (val elem = a.opt(i)) {
                    is Number -> out.add(elem.toInt())
                    is JSONArray -> walk(elem)
                }
            }
        }
        walk(arr)
        return out.toIntArray()
    }

    private fun flattenJsonFloatArray(arr: JSONArray): FloatArray {
        val out = ArrayList<Float>(arr.length())
        fun walk(a: JSONArray) {
            for (i in 0 until a.length()) {
                when (val elem = a.opt(i)) {
                    is Number -> out.add(elem.toFloat())
                    is JSONArray -> walk(elem)
                }
            }
        }
        walk(arr)
        return out.toFloatArray()
    }

    /**
     * Estimates coarse pitch ratio of a reference voice sample for metadata display.
     */
    fun estimateSpeakerPitchRatio(samples: FloatArray, sampleRate: Int): Float {
        if (samples.size < sampleRate / 4 || sampleRate <= 0) return 1.0f
        val minLag = (sampleRate / 340).coerceAtLeast(20)
        val maxLag = (sampleRate / 75).coerceAtMost(samples.size / 3)
        if (maxLag <= minLag) return 1.0f
        val winSize = (sampleRate / 20).coerceAtMost(samples.size / 2)
        val start = ((samples.size - winSize) / 2).coerceAtLeast(0)

        var bestLag = (minLag + maxLag) / 2
        var bestCorr = -1.0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            var e1 = 0.0
            var e2 = 0.0
            var i = 0
            while (i < winSize && start + i + lag < samples.size) {
                val a = samples[start + i].toDouble()
                val b = samples[start + i + lag].toDouble()
                corr += a * b
                e1 += a * a
                e2 += b * b
                i += 2
            }
            val denom = sqrt(e1 * e2)
            if (denom > 1e-6) {
                val norm = corr / denom
                if (norm > bestCorr) {
                    bestCorr = norm
                    bestLag = lag
                }
            }
        }
        val f0 = sampleRate.toFloat() / bestLag.toFloat()
        return (f0 / 165f).coerceIn(0.75f, 1.28f)
    }

    /**
     * Compact 32-dim acoustic summary vector stored alongside NeuCodec reference codes for profile metadata.
     */
    fun computeAcousticFingerprint(samples: FloatArray): FloatArray {
        val out = FloatArray(32)
        if (samples.isEmpty()) return out
        val seg = (samples.size / out.size).coerceAtLeast(1)
        var normSq = 0.0
        for (b in out.indices) {
            val from = b * seg
            val to = ((b + 1) * seg).coerceAtMost(samples.size)
            var sumAbs = 0.0
            for (i in from until to) sumAbs += abs(samples[i].toDouble())
            val v = (sumAbs / (to - from).coerceAtLeast(1)).toFloat()
            out[b] = v
            normSq += v * v
        }
        val inv = if (normSq > 1e-9) (1.0 / sqrt(normSq)).toFloat() else 1f
        for (i in out.indices) out[i] *= inv
        return out
    }
}
