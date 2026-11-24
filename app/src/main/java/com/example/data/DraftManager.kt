package com.example.data

import android.content.Context
import com.example.pipeline.EditableTocItem
import com.example.pipeline.EpubMetadata
import com.example.pipeline.InputFileType
import com.example.pipeline.ManualChapterContent
import com.example.pipeline.ManualEditorMode
import com.example.pipeline.ManualTocEntry
import com.example.pipeline.PreparedWorkspace
import com.example.pipeline.SourceFileInfo
import java.io.File

/**
 * Full per-file editing snapshot stored offline in `filesDir/edit_states/{fileId}.json`.
 * Saved on BOTH "Lưu nháp" and "Lưu" (complete EPUB export) so "Sửa tiếp" can reopen
 * any Draft or Saved file at the exact chapter & paragraph with original comparison intact.
 */
data class FileEditStateSnapshot(
    val fileId: String,
    val sourceFile: SourceFileInfo,
    val metadata: EpubMetadata?,
    val bookTitle: String,
    val bookAuthor: String,
    val mode: ManualEditorMode,
    val chapters: List<ManualChapterContent>,
    val tocList: List<EditableTocItem>,
    val manualTocEntries: List<ManualTocEntry>,
    val lastChapterIndex: Int,
    val lastParagraphIndex: Int,
    val savedFileUri: String? = null,
    val savedAbsolutePath: String? = null,
    val regenerateToc: Boolean = false,
    val manualEditCount: Int = 0,
    val savedAtMillis: Long = System.currentTimeMillis(),
    val savedImagesDirPath: String? = null
)

/**
 * 100% Offline Per-File Edit State & Draft Persistence Manager:
 * - Stores each book's editing snapshot at `context.filesDir/edit_states/{fileId}.json`
 *   containing `currentParagraphs`, `originalParagraphs`, `tocList`, `lastChapterIndex`,
 *   `lastParagraphIndex`, `savedFileUri`, metadata, and source info.
 * - Preserves staged book illustrations & cover under `filesDir/edit_states/assets_{fileId}/`
 *   so resuming & re-exporting always produces a complete valid EPUB.
 * - Uses pure-Kotlin RFC-8259 JSON serialization/deserialization (works identically on Android
 *   runtime and JVM unit tests with zero external dependencies).
 */
class DraftManager(context: Context) {

    private val appContext = context.applicationContext
    private val editStatesDir: File
        get() = File(appContext.filesDir, EDIT_STATES_DIR_NAME)

    private fun stateFileFor(fileId: String): File =
        File(editStatesDir, editStateFileName(fileId))

    private fun assetsDirFor(fileId: String): File =
        File(editStatesDir, "assets_${sanitizeFileId(fileId)}")

    @Synchronized
    fun saveEditState(
        fileId: String,
        sourceFile: SourceFileInfo,
        workspace: PreparedWorkspace,
        metadata: EpubMetadata?,
        mode: ManualEditorMode,
        chapters: List<ManualChapterContent>,
        tocList: List<EditableTocItem>,
        manualTocEntries: List<ManualTocEntry>,
        lastChapterIndex: Int,
        lastParagraphIndex: Int,
        savedFileUri: String? = null,
        savedAbsolutePath: String? = null,
        regenerateToc: Boolean = false,
        manualEditCount: Int = 0
    ): File? {
        if (chapters.isEmpty() || fileId.isBlank()) return null
        val now = System.currentTimeMillis()
        val effectiveTitle = metadata?.title?.trim()?.ifBlank { null }
            ?: workspace.bookTitle.ifBlank { sourceFile.baseNameWithoutExt }
        val effectiveAuthor = metadata?.creator?.trim()?.ifBlank { null }
            ?: workspace.bookAuthor

        return runCatching {
            if (!editStatesDir.exists()) editStatesDir.mkdirs()

            val assetRoot = assetsDirFor(fileId)
            val imagesDir = File(assetRoot, "images")
            val coverFile = File(assetRoot, "cover.jpg")

            // Preserve staged book illustrations into persistent filesDir
            val stagedImagesDir = File(workspace.stageDirPath, "OEBPS/Images")
            if (stagedImagesDir.exists() && stagedImagesDir.isDirectory) {
                val stagedFiles = stagedImagesDir.listFiles()?.filter { it.isFile }.orEmpty()
                if (stagedFiles.isNotEmpty()) {
                    if (!imagesDir.exists()) imagesDir.mkdirs()
                    val stagedNames = stagedFiles.map { it.name }.toSet()
                    imagesDir.listFiles()?.forEach { old ->
                        if (old.name !in stagedNames) old.delete()
                    }
                    for (img in stagedFiles) {
                        val dest = File(imagesDir, img.name)
                        if (!dest.exists() || dest.length() != img.length()) {
                            img.copyTo(dest, overwrite = true)
                        }
                    }
                }
            }

            // Preserve custom or existing cover image safely
            var persistedCoverPath: String? = null
            val newCoverSource = metadata?.newCoverImagePath?.let { File(it) }
            when {
                newCoverSource != null && newCoverSource.exists() -> {
                    if (!assetRoot.exists()) assetRoot.mkdirs()
                    if (newCoverSource.canonicalPath != coverFile.canonicalPath) {
                        newCoverSource.copyTo(coverFile, overwrite = true)
                    }
                    persistedCoverPath = coverFile.absolutePath
                }

                metadata?.existingCoverBytes != null && metadata.existingCoverBytes.isNotEmpty() -> {
                    if (!assetRoot.exists()) assetRoot.mkdirs()
                    coverFile.writeBytes(metadata.existingCoverBytes)
                    persistedCoverPath = coverFile.absolutePath
                }

                coverFile.exists() -> {
                    persistedCoverPath = coverFile.absolutePath
                }
            }

            val persistedMetadata = metadata?.copy(
                newCoverImagePath = persistedCoverPath,
                existingCoverBytes = null
            )

            val clampedChap = lastChapterIndex.coerceIn(0, chapters.lastIndex)
            val clampedPara = lastParagraphIndex.coerceIn(
                0,
                (chapters[clampedChap].currentParagraphs.lastIndex).coerceAtLeast(0)
            )

            val snapshot = FileEditStateSnapshot(
                fileId = fileId,
                sourceFile = sourceFile,
                metadata = persistedMetadata,
                bookTitle = effectiveTitle,
                bookAuthor = effectiveAuthor,
                mode = mode,
                chapters = chapters,
                tocList = tocList,
                manualTocEntries = manualTocEntries,
                lastChapterIndex = clampedChap,
                lastParagraphIndex = clampedPara,
                savedFileUri = savedFileUri?.takeIf { it.isNotBlank() },
                savedAbsolutePath = savedAbsolutePath?.takeIf { it.isNotBlank() },
                regenerateToc = regenerateToc,
                manualEditCount = manualEditCount,
                savedAtMillis = now,
                savedImagesDirPath = imagesDir.takeIf { it.exists() && it.isDirectory }?.absolutePath
            )

            val targetFile = stateFileFor(fileId)
            writeEditStateFile(targetFile, snapshot)
            targetFile
        }.getOrNull()
    }

    @Synchronized
    fun loadEditState(fileId: String): FileEditStateSnapshot? {
        if (fileId.isBlank()) return null
        val targetFile = stateFileFor(fileId)
        if (!targetFile.exists()) return null
        val loaded = readEditStateFile(targetFile) ?: return null
        val imagesDir = File(assetsDirFor(fileId), "images")
        return loaded.copy(
            savedImagesDirPath = imagesDir.takeIf { it.exists() && it.isDirectory }?.absolutePath
        )
    }

    @Synchronized
    fun hasEditState(fileId: String): Boolean {
        return fileId.isNotBlank() && stateFileFor(fileId).exists()
    }

    @Synchronized
    fun deleteEditState(fileId: String) {
        if (fileId.isBlank()) return
        runCatching {
            val f = stateFileFor(fileId)
            if (f.exists()) f.delete()
            val assets = assetsDirFor(fileId)
            if (assets.exists()) assets.deleteRecursively()
        }
    }

    @Synchronized
    fun clearAllEditStates() {
        runCatching {
            if (editStatesDir.exists()) editStatesDir.deleteRecursively()
            val legacyDraftsDir = File(appContext.filesDir, "drafts")
            if (legacyDraftsDir.exists()) legacyDraftsDir.deleteRecursively()
        }
    }

    companion object {
        const val EDIT_STATES_DIR_NAME = "edit_states"

        fun sanitizeFileId(fileId: String): String {
            val cleaned = fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            return cleaned.ifBlank { "default_state" }
        }

        fun editStateFileName(fileId: String): String = "${sanitizeFileId(fileId)}.json"

        /**
         * Pure Kotlin atomic writer for `filesDir/edit_states/{fileId}.json`.
         */
        fun writeEditStateFile(target: File, snapshot: FileEditStateSnapshot) {
            target.parentFile?.mkdirs()
            val json = serializeEditStateToJson(snapshot)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        }

        /**
         * Pure Kotlin reader for `filesDir/edit_states/{fileId}.json`.
         */
        fun readEditStateFile(target: File): FileEditStateSnapshot? {
            if (!target.exists()) return null
            return runCatching {
                parseEditStateFromJson(target.readText(Charsets.UTF_8))
            }.getOrNull()
        }

        fun serializeEditStateToJson(snapshot: FileEditStateSnapshot): String {
            val sb = StringBuilder(4096)
            sb.append('{')
            appendField(sb, "fileId", snapshot.fileId, first = true)
            appendField(sb, "bookTitle", snapshot.bookTitle)
            appendField(sb, "bookAuthor", snapshot.bookAuthor)
            appendField(sb, "mode", snapshot.mode.name)
            appendNumberField(sb, "lastChapterIndex", snapshot.lastChapterIndex.toLong())
            appendNumberField(sb, "lastParagraphIndex", snapshot.lastParagraphIndex.toLong())
            appendField(sb, "savedFileUri", snapshot.savedFileUri.orEmpty())
            appendField(sb, "savedAbsolutePath", snapshot.savedAbsolutePath.orEmpty())
            appendBoolField(sb, "regenerateToc", snapshot.regenerateToc)
            appendNumberField(sb, "manualEditCount", snapshot.manualEditCount.toLong())
            appendNumberField(sb, "savedAtMillis", snapshot.savedAtMillis)

            // sourceFile object
            sb.append(",\"sourceFile\":{")
            appendField(sb, "displayName", snapshot.sourceFile.displayName, first = true)
            appendField(sb, "sourceUriString", snapshot.sourceFile.sourceUriString)
            appendField(sb, "resolvedFilePath", snapshot.sourceFile.resolvedFilePath.orEmpty())
            appendField(sb, "resolvedParentDir", snapshot.sourceFile.resolvedParentDir.orEmpty())
            appendField(sb, "fileType", snapshot.sourceFile.fileType.name)
            appendNumberField(sb, "fileSizeBytes", snapshot.sourceFile.fileSizeBytes)
            sb.append('}')

            // metadata object (optional)
            val meta = snapshot.metadata
            if (meta != null) {
                sb.append(",\"metadata\":{")
                appendField(sb, "title", meta.title, first = true)
                appendField(sb, "creator", meta.creator)
                appendField(sb, "identifier", meta.identifier)
                appendField(sb, "language", meta.language)
                appendField(sb, "coverPath", meta.newCoverImagePath.orEmpty())
                sb.append('}')
            }

            // chapters array (with currentParagraphs & originalParagraphs)
            sb.append(",\"chapters\":[")
            snapshot.chapters.forEachIndexed { idx, chap ->
                if (idx > 0) sb.append(',')
                sb.append('{')
                appendNumberField(sb, "chapterIndex", chap.chapterIndex.toLong(), first = true)
                appendField(sb, "fileName", chap.fileName)
                appendField(sb, "title", chap.title)
                sb.append(",\"originalParagraphs\":")
                appendStringArray(sb, chap.originalParagraphs)
                sb.append(",\"currentParagraphs\":")
                appendStringArray(sb, chap.currentParagraphs)
                sb.append('}')
            }
            sb.append(']')

            // tocList array
            sb.append(",\"tocList\":[")
            snapshot.tocList.forEachIndexed { idx, item ->
                if (idx > 0) sb.append(',')
                sb.append('{')
                appendField(sb, "id", item.id, first = true)
                appendField(sb, "title", item.title)
                appendField(sb, "href", item.href)
                appendNumberField(sb, "chapterIndex", item.chapterIndex.toLong())
                appendField(sb, "chapterFileName", item.chapterFileName)
                appendNumberField(sb, "paragraphIndex", item.paragraphIndex.toLong())
                appendField(sb, "anchorId", item.anchorId.orEmpty())
                appendBoolField(sb, "isManualBookmark", item.isManualBookmark)
                sb.append('}')
            }
            sb.append(']')

            // manualTocEntries array
            sb.append(",\"manualTocEntries\":[")
            snapshot.manualTocEntries.forEachIndexed { idx, bm ->
                if (idx > 0) sb.append(',')
                sb.append('{')
                appendField(sb, "id", bm.id, first = true)
                appendField(sb, "title", bm.title)
                appendNumberField(sb, "chapterIndex", bm.chapterIndex.toLong())
                appendField(sb, "chapterFileName", bm.chapterFileName)
                appendNumberField(sb, "paragraphIndex", bm.paragraphIndex.toLong())
                appendField(sb, "anchorId", bm.anchorId)
                sb.append('}')
            }
            sb.append(']')

            sb.append('}')
            return sb.toString()
        }

        @Suppress("UNCHECKED_CAST")
        fun parseEditStateFromJson(rawJson: String): FileEditStateSnapshot? {
            val root = MiniJsonParser(rawJson).parseValue() as? Map<String, Any?> ?: return null

            val chaptersRaw = root["chapters"] as? List<*> ?: return null
            val chapters = ArrayList<ManualChapterContent>(chaptersRaw.size)
            for (i in chaptersRaw.indices) {
                val cMap = chaptersRaw[i] as? Map<String, Any?> ?: continue
                val origParas = (cMap["originalParagraphs"] as? List<*>)?.map { it?.toString().orEmpty() }.orEmpty()
                val currParas = (cMap["currentParagraphs"] as? List<*>)?.map { it?.toString().orEmpty() }.orEmpty()
                chapters.add(
                    ManualChapterContent(
                        chapterIndex = cMap.intVal("chapterIndex", i),
                        fileName = cMap.strVal("fileName").ifBlank { "chapter_%04d.xhtml".format(i + 1) },
                        title = cMap.strVal("title").ifBlank { "Chương ${i + 1}" },
                        filePath = "",
                        originalParagraphs = origParas,
                        currentParagraphs = currParas
                    )
                )
            }
            if (chapters.isEmpty()) return null

            val srcMap = root["sourceFile"] as? Map<String, Any?> ?: emptyMap()
            val sourceFile = SourceFileInfo(
                displayName = srcMap.strVal("displayName").ifBlank { "ebook.epub" },
                sourceUriString = srcMap.strVal("sourceUriString"),
                resolvedFilePath = srcMap.strVal("resolvedFilePath").takeIf { it.isNotBlank() },
                resolvedParentDir = srcMap.strVal("resolvedParentDir").takeIf { it.isNotBlank() },
                fileType = runCatching {
                    InputFileType.valueOf(srcMap.strVal("fileType").ifBlank { InputFileType.EPUB.name })
                }.getOrDefault(InputFileType.EPUB),
                fileSizeBytes = srcMap.longVal("fileSizeBytes", 0L)
            )

            val metaMap = root["metadata"] as? Map<String, Any?>
            val metadata = if (metaMap != null) {
                val cPath = metaMap.strVal("coverPath").takeIf { it.isNotBlank() && File(it).exists() }
                EpubMetadata(
                    title = metaMap.strVal("title"),
                    creator = metaMap.strVal("creator"),
                    identifier = metaMap.strVal("identifier"),
                    language = metaMap.strVal("language").ifBlank { "vi" },
                    newCoverImagePath = cPath
                )
            } else {
                null
            }

            val tocRaw = (root["tocList"] ?: root["editableTocItems"]) as? List<*>
            val tocList = ArrayList<EditableTocItem>()
            if (tocRaw != null) {
                for (item in tocRaw) {
                    val tMap = item as? Map<String, Any?> ?: continue
                    tocList.add(
                        EditableTocItem(
                            id = tMap.strVal("id"),
                            title = tMap.strVal("title"),
                            href = tMap.strVal("href"),
                            chapterIndex = tMap.intVal("chapterIndex", 0),
                            chapterFileName = tMap.strVal("chapterFileName"),
                            paragraphIndex = tMap.intVal("paragraphIndex", 0),
                            anchorId = tMap.strVal("anchorId").takeIf { it.isNotBlank() },
                            isManualBookmark = tMap.boolVal("isManualBookmark", false)
                        )
                    )
                }
            }

            val manualRaw = root["manualTocEntries"] as? List<*>
            val manualEntries = ArrayList<ManualTocEntry>()
            if (manualRaw != null) {
                for (item in manualRaw) {
                    val mMap = item as? Map<String, Any?> ?: continue
                    manualEntries.add(
                        ManualTocEntry(
                            id = mMap.strVal("id"),
                            title = mMap.strVal("title"),
                            chapterIndex = mMap.intVal("chapterIndex", 0),
                            chapterFileName = mMap.strVal("chapterFileName"),
                            paragraphIndex = mMap.intVal("paragraphIndex", 0),
                            anchorId = mMap.strVal("anchorId")
                        )
                    )
                }
            }

            val chapIdx = root.intVal("lastChapterIndex", root.intVal("currentChapterIndex", 0))
                .coerceIn(0, chapters.lastIndex)
            val paraIdx = root.intVal("lastParagraphIndex", root.intVal("activeParagraphIndex", 0))
                .coerceIn(0, (chapters[chapIdx].currentParagraphs.lastIndex).coerceAtLeast(0))

            return FileEditStateSnapshot(
                fileId = root.strVal("fileId"),
                sourceFile = sourceFile,
                metadata = metadata,
                bookTitle = root.strVal("bookTitle").ifBlank { sourceFile.baseNameWithoutExt },
                bookAuthor = root.strVal("bookAuthor").ifBlank { "Không rõ tác giả" },
                mode = runCatching {
                    ManualEditorMode.valueOf(root.strVal("mode").ifBlank { ManualEditorMode.KEEP_CLEANED.name })
                }.getOrDefault(ManualEditorMode.KEEP_CLEANED),
                chapters = chapters,
                tocList = tocList,
                manualTocEntries = manualEntries,
                lastChapterIndex = chapIdx,
                lastParagraphIndex = paraIdx,
                savedFileUri = root.strVal("savedFileUri").takeIf { it.isNotBlank() },
                savedAbsolutePath = root.strVal("savedAbsolutePath").takeIf { it.isNotBlank() },
                regenerateToc = root.boolVal("regenerateToc", false),
                manualEditCount = root.intVal("manualEditCount", 0),
                savedAtMillis = root.longVal("savedAtMillis", System.currentTimeMillis())
            )
        }

        private fun appendField(sb: StringBuilder, key: String, value: String, first: Boolean = false) {
            if (!first) sb.append(',')
            sb.append('"').append(key).append("\":\"")
            appendEscaped(sb, value)
            sb.append('"')
        }

        private fun appendNumberField(sb: StringBuilder, key: String, value: Long, first: Boolean = false) {
            if (!first) sb.append(',')
            sb.append('"').append(key).append("\":").append(value)
        }

        private fun appendBoolField(sb: StringBuilder, key: String, value: Boolean, first: Boolean = false) {
            if (!first) sb.append(',')
            sb.append('"').append(key).append("\":").append(value)
        }

        private fun appendStringArray(sb: StringBuilder, list: List<String>) {
            sb.append('[')
            list.forEachIndexed { idx, s ->
                if (idx > 0) sb.append(',')
                sb.append('"')
                appendEscaped(sb, s)
                sb.append('"')
            }
            sb.append(']')
        }

        private fun appendEscaped(sb: StringBuilder, input: String) {
            for (ch in input) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
                }
            }
        }

        private fun Map<String, Any?>.strVal(key: String): String = this[key]?.toString().orEmpty()
        private fun Map<String, Any?>.intVal(key: String, default: Int): Int =
            (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default
        private fun Map<String, Any?>.longVal(key: String, default: Long): Long =
            (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: default
        private fun Map<String, Any?>.boolVal(key: String, default: Boolean): Boolean =
            (this[key] as? Boolean) ?: this[key]?.toString()?.toBooleanStrictOrNull() ?: default

        /**
         * Compact recursive-descent pure-Kotlin JSON parser (RFC-8259 compliant) so edit-state
         * serialization works identically on Android runtime and JVM unit tests.
         */
        private class MiniJsonParser(private val src: String) {
            private var pos = 0
            private val len = src.length

            private fun skipWs() {
                while (pos < len && src[pos].isWhitespace()) pos++
            }

            fun parseValue(): Any? {
                skipWs()
                if (pos >= len) return null
                return when (src[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    else -> parseNumber()
                }
            }

            private fun parseObject(): Map<String, Any?> {
                pos++ // '{'
                val map = LinkedHashMap<String, Any?>()
                skipWs()
                if (pos < len && src[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < len) {
                    skipWs()
                    if (pos >= len || src[pos] != '"') break
                    val key = parseString()
                    skipWs()
                    if (pos >= len || src[pos] != ':') break
                    pos++ // ':'
                    val value = parseValue()
                    map[key] = value
                    skipWs()
                    if (pos < len && src[pos] == ',') {
                        pos++
                        continue
                    }
                    if (pos < len && src[pos] == '}') {
                        pos++
                        break
                    }
                    break
                }
                return map
            }

            private fun parseArray(): List<Any?> {
                pos++ // '['
                val list = ArrayList<Any?>()
                skipWs()
                if (pos < len && src[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < len) {
                    val value = parseValue()
                    list.add(value)
                    skipWs()
                    if (pos < len && src[pos] == ',') {
                        pos++
                        continue
                    }
                    if (pos < len && src[pos] == ']') {
                        pos++
                        break
                    }
                    break
                }
                return list
            }

            private fun parseString(): String {
                pos++ // '"'
                val sb = StringBuilder()
                while (pos < len) {
                    val ch = src[pos++]
                    if (ch == '"') return sb.toString()
                    if (ch == '\\' && pos < len) {
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= len) {
                                    src.substring(pos, pos + 4).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }

            private fun parseBoolean(): Boolean {
                return if (src.startsWith("true", pos)) {
                    pos += 4
                    true
                } else {
                    pos += 5
                    false
                }
            }

            private fun parseNull(): Any? {
                pos += 4
                return null
            }

            private fun parseNumber(): Number {
                val start = pos
                while (pos < len && src[pos] in "-+0123456789.eE") pos++
                val token = src.substring(start, pos)
                return token.toLongOrNull() ?: token.toDoubleOrNull() ?: 0L
            }
        }
    }
}
