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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedDraftSummary(
    val bookTitle: String,
    val fileName: String,
    val chapterIndex: Int,
    val totalChapters: Int,
    val paragraphIndex: Int,
    val savedAtMillis: Long
)

data class SavedDraftData(
    val sourceFile: SourceFileInfo,
    val metadata: EpubMetadata?,
    val bookTitle: String,
    val bookAuthor: String,
    val mode: ManualEditorMode,
    val chapters: List<ManualChapterContent>,
    val editableTocItems: List<EditableTocItem>,
    val manualTocEntries: List<ManualTocEntry>,
    val currentChapterIndex: Int,
    val activeParagraphIndex: Int,
    val regenerateToc: Boolean,
    val manualEditCount: Int,
    val savedAtMillis: Long,
    val savedImagesDirPath: String?
)

/**
 * 100% Offline Draft Persistence Manager (Upgrade 1.2):
 * Stores the active manual editing session (chapters, original snapshot for compare,
 * edited TOC, manual bookmarks, exact reading position, metadata, cover & staged images)
 * inside `context.filesDir/drafts/active_draft/` so the user can close the app and resume
 * editing or export a complete valid EPUB anytime later.
 */
class DraftManager(context: Context) {

    private val appContext = context.applicationContext
    private val draftDir: File
        get() = File(appContext.filesDir, "drafts/active_draft")
    private val metaFile: File
        get() = File(draftDir, "draft_session.json")
    private val summaryFile: File
        get() = File(draftDir, "draft_summary.json")
    private val imagesDir: File
        get() = File(draftDir, "images")
    private val coverFile: File
        get() = File(draftDir, "cover.jpg")

    @Synchronized
    fun saveDraft(
        sourceFile: SourceFileInfo,
        workspace: PreparedWorkspace,
        metadata: EpubMetadata?,
        mode: ManualEditorMode,
        chapters: List<ManualChapterContent>,
        editableTocItems: List<EditableTocItem>,
        manualTocEntries: List<ManualTocEntry>,
        currentChapterIndex: Int,
        activeParagraphIndex: Int,
        regenerateToc: Boolean,
        manualEditCount: Int
    ): SavedDraftSummary? {
        if (chapters.isEmpty()) return null
        val now = System.currentTimeMillis()
        val effectiveTitle = metadata?.title?.trim()?.ifBlank { null }
            ?: workspace.bookTitle.ifBlank { sourceFile.baseNameWithoutExt }
        val effectiveAuthor = metadata?.creator?.trim()?.ifBlank { null }
            ?: workspace.bookAuthor

        return runCatching {
            if (!draftDir.exists()) draftDir.mkdirs()

            // Preserve staged book illustrations into persistent filesDir so cache pruning won't break EPUB export
            val stagedImagesDir = File(workspace.stageDirPath, "OEBPS/Images")
            if (stagedImagesDir.exists() && stagedImagesDir.isDirectory) {
                val stagedFiles = stagedImagesDir.listFiles()?.filter { it.isFile }.orEmpty()
                if (stagedFiles.isNotEmpty()) {
                    if (!imagesDir.exists()) imagesDir.mkdirs()
                    // Copy any image not yet persisted in draftDir/images
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

            // Preserve custom/existing cover bytes safely
            var persistedCoverPath: String? = null
            val newCoverSource = metadata?.newCoverImagePath?.let { File(it) }
            when {
                newCoverSource != null && newCoverSource.exists() -> {
                    if (newCoverSource.canonicalPath != coverFile.canonicalPath) {
                        newCoverSource.copyTo(coverFile, overwrite = true)
                    }
                    persistedCoverPath = coverFile.absolutePath
                }

                metadata?.existingCoverBytes != null && metadata.existingCoverBytes.isNotEmpty() -> {
                    coverFile.writeBytes(metadata.existingCoverBytes)
                    persistedCoverPath = coverFile.absolutePath
                }

                coverFile.exists() -> {
                    persistedCoverPath = coverFile.absolutePath
                }
            }

            val clampedChap = currentChapterIndex.coerceIn(0, chapters.lastIndex)
            val clampedPara = activeParagraphIndex.coerceAtLeast(0)

            // 1. Write lightweight summary JSON for instant MainScreen display
            val summaryJson = JSONObject().apply {
                put("bookTitle", effectiveTitle)
                put("fileName", sourceFile.displayName)
                put("chapterIndex", clampedChap)
                put("totalChapters", chapters.size)
                put("paragraphIndex", clampedPara)
                put("savedAtMillis", now)
            }
            writeAtomically(summaryFile, summaryJson.toString())

            // 2. Write full session JSON
            val fullJson = JSONObject().apply {
                put("bookTitle", effectiveTitle)
                put("bookAuthor", effectiveAuthor)
                put("mode", mode.name)
                put("currentChapterIndex", clampedChap)
                put("activeParagraphIndex", clampedPara)
                put("regenerateToc", regenerateToc)
                put("manualEditCount", manualEditCount)
                put("savedAtMillis", now)

                put(
                    "sourceFile",
                    JSONObject().apply {
                        put("displayName", sourceFile.displayName)
                        put("sourceUriString", sourceFile.sourceUriString)
                        put("resolvedFilePath", sourceFile.resolvedFilePath.orEmpty())
                        put("resolvedParentDir", sourceFile.resolvedParentDir.orEmpty())
                        put("fileType", sourceFile.fileType.name)
                        put("fileSizeBytes", sourceFile.fileSizeBytes)
                    }
                )

                if (metadata != null) {
                    put(
                        "metadata",
                        JSONObject().apply {
                            put("title", metadata.title)
                            put("creator", metadata.creator)
                            put("identifier", metadata.identifier)
                            put("language", metadata.language)
                            put("coverPath", persistedCoverPath.orEmpty())
                        }
                    )
                }

                val chaptersArr = JSONArray()
                for (chap in chapters) {
                    chaptersArr.put(
                        JSONObject().apply {
                            put("chapterIndex", chap.chapterIndex)
                            put("fileName", chap.fileName)
                            put("title", chap.title)
                            put("originalParagraphs", JSONArray(chap.originalParagraphs))
                            put("currentParagraphs", JSONArray(chap.currentParagraphs))
                        }
                    )
                }
                put("chapters", chaptersArr)

                val tocArr = JSONArray()
                for (item in editableTocItems) {
                    tocArr.put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("title", item.title)
                            put("href", item.href)
                            put("chapterIndex", item.chapterIndex)
                            put("chapterFileName", item.chapterFileName)
                            put("paragraphIndex", item.paragraphIndex)
                            put("anchorId", item.anchorId.orEmpty())
                            put("isManualBookmark", item.isManualBookmark)
                        }
                    )
                }
                put("editableTocItems", tocArr)

                val manualArr = JSONArray()
                for (bm in manualTocEntries) {
                    manualArr.put(
                        JSONObject().apply {
                            put("id", bm.id)
                            put("title", bm.title)
                            put("chapterIndex", bm.chapterIndex)
                            put("chapterFileName", bm.chapterFileName)
                            put("paragraphIndex", bm.paragraphIndex)
                            put("anchorId", bm.anchorId)
                        }
                    )
                }
                put("manualTocEntries", manualArr)
            }
            writeAtomically(metaFile, fullJson.toString())

            SavedDraftSummary(
                bookTitle = effectiveTitle,
                fileName = sourceFile.displayName,
                chapterIndex = clampedChap,
                totalChapters = chapters.size,
                paragraphIndex = clampedPara,
                savedAtMillis = now
            )
        }.getOrNull()
    }

    @Synchronized
    fun loadDraftSummary(): SavedDraftSummary? {
        if (!summaryFile.exists() || !metaFile.exists()) return null
        return runCatching {
            val obj = JSONObject(summaryFile.readText(Charsets.UTF_8))
            val title = obj.optString("bookTitle").trim()
            val fileName = obj.optString("fileName").trim()
            if (title.isEmpty() && fileName.isEmpty()) return null
            SavedDraftSummary(
                bookTitle = title.ifBlank { fileName },
                fileName = fileName.ifBlank { title },
                chapterIndex = obj.optInt("chapterIndex", 0).coerceAtLeast(0),
                totalChapters = obj.optInt("totalChapters", 1).coerceAtLeast(1),
                paragraphIndex = obj.optInt("paragraphIndex", 0).coerceAtLeast(0),
                savedAtMillis = obj.optLong("savedAtMillis", summaryFile.lastModified())
            )
        }.getOrNull()
    }

    @Synchronized
    fun loadFullDraft(): SavedDraftData? {
        if (!metaFile.exists()) return null
        return runCatching {
            val root = JSONObject(metaFile.readText(Charsets.UTF_8))
            val srcObj = root.getJSONObject("sourceFile")
            val sourceFile = SourceFileInfo(
                displayName = srcObj.optString("displayName").ifBlank { "ebook_draft.epub" },
                sourceUriString = srcObj.optString("sourceUriString"),
                resolvedFilePath = srcObj.optString("resolvedFilePath").takeIf { it.isNotBlank() },
                resolvedParentDir = srcObj.optString("resolvedParentDir").takeIf { it.isNotBlank() },
                fileType = runCatching {
                    InputFileType.valueOf(srcObj.optString("fileType", InputFileType.EPUB.name))
                }.getOrDefault(InputFileType.EPUB),
                fileSizeBytes = srcObj.optLong("fileSizeBytes", 0L)
            )

            val metaObj = root.optJSONObject("metadata")
            val metadata = if (metaObj != null) {
                val cPath = metaObj.optString("coverPath").takeIf { it.isNotBlank() && File(it).exists() }
                EpubMetadata(
                    title = metaObj.optString("title"),
                    creator = metaObj.optString("creator"),
                    identifier = metaObj.optString("identifier"),
                    language = metaObj.optString("language").ifBlank { "vi" },
                    newCoverImagePath = cPath
                )
            } else {
                null
            }

            val chaptersArr = root.getJSONArray("chapters")
            val chapters = ArrayList<ManualChapterContent>(chaptersArr.length())
            for (i in 0 until chaptersArr.length()) {
                val cObj = chaptersArr.getJSONObject(i)
                chapters.add(
                    ManualChapterContent(
                        chapterIndex = cObj.optInt("chapterIndex", i),
                        fileName = cObj.optString("fileName").ifBlank { "chapter_%04d.xhtml".format(i + 1) },
                        title = cObj.optString("title").ifBlank { "Chương ${i + 1}" },
                        filePath = "",
                        originalParagraphs = jsonArrayToStringList(cObj.optJSONArray("originalParagraphs")),
                        currentParagraphs = jsonArrayToStringList(cObj.optJSONArray("currentParagraphs"))
                    )
                )
            }
            if (chapters.isEmpty()) return null

            val tocArr = root.optJSONArray("editableTocItems")
            val editableToc = ArrayList<EditableTocItem>()
            if (tocArr != null) {
                for (i in 0 until tocArr.length()) {
                    val tObj = tocArr.getJSONObject(i)
                    editableToc.add(
                        EditableTocItem(
                            id = tObj.optString("id"),
                            title = tObj.optString("title"),
                            href = tObj.optString("href"),
                            chapterIndex = tObj.optInt("chapterIndex", 0),
                            chapterFileName = tObj.optString("chapterFileName"),
                            paragraphIndex = tObj.optInt("paragraphIndex", 0),
                            anchorId = tObj.optString("anchorId").takeIf { it.isNotBlank() },
                            isManualBookmark = tObj.optBoolean("isManualBookmark", false)
                        )
                    )
                }
            }

            val manualArr = root.optJSONArray("manualTocEntries")
            val manualEntries = ArrayList<ManualTocEntry>()
            if (manualArr != null) {
                for (i in 0 until manualArr.length()) {
                    val mObj = manualArr.getJSONObject(i)
                    manualEntries.add(
                        ManualTocEntry(
                            id = mObj.optString("id"),
                            title = mObj.optString("title"),
                            chapterIndex = mObj.optInt("chapterIndex", 0),
                            chapterFileName = mObj.optString("chapterFileName"),
                            paragraphIndex = mObj.optInt("paragraphIndex", 0),
                            anchorId = mObj.optString("anchorId")
                        )
                    )
                }
            }

            SavedDraftData(
                sourceFile = sourceFile,
                metadata = metadata,
                bookTitle = root.optString("bookTitle").ifBlank { sourceFile.baseNameWithoutExt },
                bookAuthor = root.optString("bookAuthor").ifBlank { "Không rõ tác giả" },
                mode = runCatching {
                    ManualEditorMode.valueOf(root.optString("mode", ManualEditorMode.KEEP_CLEANED.name))
                }.getOrDefault(ManualEditorMode.KEEP_CLEANED),
                chapters = chapters,
                editableTocItems = editableToc,
                manualTocEntries = manualEntries,
                currentChapterIndex = root.optInt("currentChapterIndex", 0).coerceIn(0, chapters.lastIndex),
                activeParagraphIndex = root.optInt("activeParagraphIndex", 0).coerceAtLeast(0),
                regenerateToc = root.optBoolean("regenerateToc", false),
                manualEditCount = root.optInt("manualEditCount", 0),
                savedAtMillis = root.optLong("savedAtMillis", System.currentTimeMillis()),
                savedImagesDirPath = imagesDir.takeIf { it.exists() && it.isDirectory }?.absolutePath
            )
        }.getOrNull()
    }

    @Synchronized
    fun clearDraft() {
        runCatching {
            if (draftDir.exists()) {
                draftDir.deleteRecursively()
            }
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun jsonArrayToStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            list.add(array.optString(i).orEmpty())
        }
        return list
    }
}
