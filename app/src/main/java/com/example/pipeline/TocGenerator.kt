package com.example.pipeline

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder

/**
 * Single entry in an EPUB Table of Contents (both EPUB 3 nav.xhtml and EPUB 2 toc.ncx).
 */
data class TocEntry(
    val title: String,
    val href: String // Relative to OEBPS/, e.g. "Text/chapter_0001.xhtml"
)

/**
 * Result of smartly inspecting the source book's original TOC vs. actual chapters.
 */
data class TocInspectionResult(
    val existingEntryCount: Int,
    val detectedChapterCount: Int,
    val hasSufficientToc: Boolean,
    val shouldRegenerateByDefault: Boolean,
    val originalEntries: List<TocEntry> = emptyList()
) {
    val statusSummary: String
        get() = if (hasSufficientToc) {
            "Hiện có: $existingEntryCount mục (Đủ)"
        } else if (existingEntryCount > 0) {
            "Hiện có: $existingEntryCount mục (Thiếu)"
        } else {
            "Chưa có / Thiếu mục lục"
        }
}

/**
 * Smart Table of Contents Inspector & Generator for EPUB 2 (toc.ncx) and EPUB 3 (nav.xhtml).
 * - Counts existing TOC entries in original `nav.xhtml` / `toc.ncx` and compares with actual chapters.
 * - Classifies into "Đủ mục lục" (default regenerate = OFF) vs "Thiếu / Chưa có mục lục" (default regenerate = ON).
 * - Preserves original TOC cleanly when disabled, or regenerates from cleaned chapter headings when enabled.
 */
object TocGenerator {

    private val chapterHeadingRegex = Regex(
        """^(?:chương|phần|hồi|quyển|tập|mục|bài|chapter|part|book|lời\s+mở\s+đầu|lời\s+nói\s+đầu|mở\s+đầu|kết\s+luận|phụ\s+lục)\b.*""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Inspects original TOC inside an unpacked EPUB directory (or returns missing TOC for PDF).
     *
     * @param unpackedDir Unpacked original EPUB folder (null for PDF).
     * @param opfFile Discovered content.opf file (null if absent/PDF).
     * @param fileType Source file type (EPUB or PDF).
     * @param detectedChapterCount Number of actual text chapters extracted.
     * @param originalFileToStagedName Map of canonical path (or lowercase filename) of source XHTML -> staged "chapter_XXXX.xhtml".
     */
    fun inspectOriginalToc(
        unpackedDir: File?,
        opfFile: File?,
        fileType: InputFileType,
        detectedChapterCount: Int,
        originalFileToStagedName: Map<String, String>
    ): TocInspectionResult {
        if (fileType == InputFileType.PDF || unpackedDir == null) {
            return TocInspectionResult(
                existingEntryCount = 0,
                detectedChapterCount = detectedChapterCount,
                hasSufficientToc = false,
                shouldRegenerateByDefault = true,
                originalEntries = emptyList()
            )
        }

        val parsedEntries = parseExistingTocEntries(unpackedDir, opfFile, originalFileToStagedName)
        val count = parsedEntries.size

        // Sufficient TOC rule:
        // Has >= 2 entries AND matches chapter structure (covers at least 40% of chapters, or >= 2 for short books)
        val minExpectedEntries = if (detectedChapterCount <= 3) {
            2
        } else {
            (detectedChapterCount * 0.40).toInt().coerceAtLeast(2)
        }
        val isSufficient = count >= 2 && count >= minExpectedEntries

        return TocInspectionResult(
            existingEntryCount = count,
            detectedChapterCount = detectedChapterCount,
            hasSufficientToc = isSufficient,
            shouldRegenerateByDefault = !isSufficient,
            originalEntries = parsedEntries
        )
    }

    /**
     * Decides final TOC entries for packaging:
     * - If [customTocEntries] is provided (e.g. edited/renamed/deleted inside ManualEditorScreen TOC),
     *   writes that exact list into both `nav.xhtml` and `toc.ncx`.
     * - Otherwise, if [regenerateToc] is false and original TOC has >= 2 mapped entries -> preserves original TOC.
     * - Otherwise -> scans staged chapter XHTML headings to generate a clean new TOC.
     * - Merges any [manualEntries] added from ManualEditorScreen right after their parent chapter
     *   in deterministic reading order with `#anchorId` fragment hrefs.
     */
    fun resolveEntriesForPackaging(
        workspace: PreparedWorkspace,
        regenerateToc: Boolean,
        manualEntries: List<ManualTocEntry> = emptyList(),
        customTocEntries: List<TocEntry>? = null
    ): List<TocEntry> {
        if (customTocEntries != null) {
            return customTocEntries.filter { it.title.isNotBlank() && it.href.isNotBlank() }
        }
        val original = workspace.tocInspection?.originalEntries.orEmpty()
        val baseEntries = if (!regenerateToc && original.size >= 2) {
            original
        } else {
            generateEntriesFromChapters(workspace.chapters)
        }
        return mergeManualEntries(baseEntries, manualEntries)
    }

    /**
     * Builds the initial in-memory editable TOC list for ManualEditorScreen so users can
     * jump to entries, add manual bookmarks, rename any entry, or delete any entry.
     */
    fun buildEditableTocItems(
        workspace: PreparedWorkspace,
        regenerateToc: Boolean,
        manualEntries: List<ManualTocEntry> = emptyList()
    ): List<EditableTocItem> {
        val baseEntries = resolveEntriesForPackaging(
            workspace = workspace,
            regenerateToc = regenerateToc,
            manualEntries = manualEntries
        )
        val chapterIndexByFile = workspace.chapters
            .mapIndexed { idx, chap -> chap.fileName to idx }
            .toMap()
        val manualByHref = manualEntries.associateBy { "Text/${it.chapterFileName}#${it.anchorId}" }

        return baseEntries.mapIndexed { idx, entry ->
            val manual = manualByHref[entry.href]
            val fileName = entry.href.substringAfterLast('/').substringBefore('#')
            val chapterIndex = manual?.chapterIndex
                ?: chapterIndexByFile[fileName]
                ?: idx.coerceIn(0, (workspace.chapters.lastIndex).coerceAtLeast(0))
            EditableTocItem(
                id = manual?.id ?: "toc_item_${idx}_${fileName}",
                title = entry.title,
                href = entry.href,
                chapterIndex = chapterIndex,
                chapterFileName = fileName.ifBlank {
                    workspace.chapters.getOrNull(chapterIndex)?.fileName.orEmpty()
                },
                paragraphIndex = manual?.paragraphIndex ?: 0,
                anchorId = manual?.anchorId,
                isManualBookmark = manual != null
            )
        }
    }

    /**
     * Renames a TOC entry in-memory (works for both standard chapter entries and manual bookmarks).
     */
    fun renameEntry(
        entries: List<EditableTocItem>,
        entryId: String,
        newTitle: String
    ): List<EditableTocItem> {
        val cleaned = newTitle.trim().take(120)
        if (cleaned.isEmpty()) return entries
        return entries.map { item ->
            if (item.id == entryId) item.copy(title = cleaned) else item
        }
    }

    /**
     * Deletes a TOC entry from the editable TOC list so it will be excluded from `nav.xhtml` & `toc.ncx`.
     */
    fun deleteEntry(
        entries: List<EditableTocItem>,
        entryId: String
    ): List<EditableTocItem> {
        return entries.filterNot { it.id == entryId }
    }

    /**
     * Inserts a new manual TOC bookmark into the editable TOC list right after its parent chapter
     * (and after any earlier manual bookmarks in the same chapter).
     */
    fun insertManualEntry(
        entries: List<EditableTocItem>,
        manualEntry: ManualTocEntry
    ): List<EditableTocItem> {
        val cleanTitle = manualEntry.title.trim().take(120)
        if (cleanTitle.isEmpty()) return entries

        val newItem = EditableTocItem(
            id = manualEntry.id,
            title = cleanTitle,
            href = "Text/${manualEntry.chapterFileName}#${manualEntry.anchorId}",
            chapterIndex = manualEntry.chapterIndex,
            chapterFileName = manualEntry.chapterFileName,
            paragraphIndex = manualEntry.paragraphIndex,
            anchorId = manualEntry.anchorId,
            isManualBookmark = true
        )

        val mutable = entries.toMutableList()
        var insertAt = mutable.indexOfLast {
            it.chapterIndex == manualEntry.chapterIndex &&
                (!it.isManualBookmark || it.paragraphIndex <= manualEntry.paragraphIndex)
        }
        if (insertAt < 0) {
            insertAt = mutable.indexOfLast { it.chapterIndex < manualEntry.chapterIndex }
        }
        if (insertAt >= 0) {
            mutable.add(insertAt + 1, newItem)
        } else {
            mutable.add(newItem)
        }
        return mutable
    }

    /**
     * Upgrade 1.3: Updates the editable TOC list immediately after splitting chapter `splitChapterIndex`
     * at `splitParagraphIndex` into a brand-new chapter (`newChapterFileName`, `newChapterTitle`)
     * placed right after `splitChapterIndex`.
     */
    fun updateTocAfterChapterSplit(
        entries: List<EditableTocItem>,
        splitChapterIndex: Int,
        newChapterFileName: String,
        newChapterTitle: String,
        splitParagraphIndex: Int
    ): List<EditableTocItem> {
        val remapped = entries.map { item ->
            when {
                item.chapterIndex > splitChapterIndex -> {
                    item.copy(chapterIndex = item.chapterIndex + 1)
                }

                item.chapterIndex == splitChapterIndex &&
                    item.isManualBookmark &&
                    item.paragraphIndex >= splitParagraphIndex -> {
                    val newParaIdx = (item.paragraphIndex - splitParagraphIndex).coerceAtLeast(0)
                    val newHref = if (!item.anchorId.isNullOrBlank()) {
                        "Text/$newChapterFileName#${item.anchorId}"
                    } else {
                        "Text/$newChapterFileName"
                    }
                    item.copy(
                        chapterIndex = splitChapterIndex + 1,
                        chapterFileName = newChapterFileName,
                        paragraphIndex = newParaIdx,
                        href = newHref
                    )
                }

                else -> item
            }
        }.toMutableList()

        val newChapterItem = EditableTocItem(
            id = "toc_split_${System.currentTimeMillis()}_$newChapterFileName",
            title = newChapterTitle.trim().take(120).ifBlank { "Chương ${splitChapterIndex + 2}" },
            href = "Text/$newChapterFileName",
            chapterIndex = splitChapterIndex + 1,
            chapterFileName = newChapterFileName,
            paragraphIndex = 0,
            anchorId = null,
            isManualBookmark = false
        )

        val insertAfter = remapped.indexOfLast { it.chapterIndex == splitChapterIndex }
        if (insertAfter >= 0) {
            remapped.add(insertAfter + 1, newChapterItem)
        } else {
            val beforeNext = remapped.indexOfFirst { it.chapterIndex > splitChapterIndex }
            if (beforeNext >= 0) {
                remapped.add(beforeNext, newChapterItem)
            } else {
                remapped.add(newChapterItem)
            }
        }
        return remapped
    }

    /**
     * Upgrade 1.3: Updates the editable TOC list immediately after merging chapter `sourceChapterIndex`
     * into the chapter directly below it (`targetChapterIndex = sourceChapterIndex + 1`).
     * The merged chapter keeps `targetChapterFileName` at index `sourceChapterIndex`, removes the
     * redundant source chapter TOC entry, shifts manual bookmarks cleanly, and decrements indices above.
     */
    fun updateTocAfterChapterMerge(
        entries: List<EditableTocItem>,
        sourceChapterIndex: Int,
        targetChapterIndex: Int,
        targetChapterFileName: String,
        sourceParagraphCount: Int
    ): List<EditableTocItem> {
        val result = ArrayList<EditableTocItem>(entries.size)
        for (item in entries) {
            when {
                // Drop the source chapter's top-level TOC entry since it was merged into the chapter below
                item.chapterIndex == sourceChapterIndex && !item.isManualBookmark -> {
                    continue
                }

                // Manual bookmarks from source chapter now sit at the top of the merged chapter
                item.chapterIndex == sourceChapterIndex && item.isManualBookmark -> {
                    val newHref = if (!item.anchorId.isNullOrBlank()) {
                        "Text/$targetChapterFileName#${item.anchorId}"
                    } else {
                        "Text/$targetChapterFileName"
                    }
                    result.add(
                        item.copy(
                            chapterIndex = sourceChapterIndex,
                            chapterFileName = targetChapterFileName,
                            href = newHref
                        )
                    )
                }

                // Target chapter (below) becomes the merged chapter at index `sourceChapterIndex`
                item.chapterIndex == targetChapterIndex -> {
                    val shiftedPara = if (item.isManualBookmark) {
                        item.paragraphIndex + sourceParagraphCount
                    } else {
                        0
                    }
                    result.add(
                        item.copy(
                            chapterIndex = sourceChapterIndex,
                            chapterFileName = targetChapterFileName,
                            paragraphIndex = shiftedPara
                        )
                    )
                }

                // Chapters after target shift up by 1
                item.chapterIndex > targetChapterIndex -> {
                    result.add(item.copy(chapterIndex = item.chapterIndex - 1))
                }

                else -> result.add(item)
            }
        }
        return result
    }

    /**
     * Converts editable TOC items back into EPUB [TocEntry] records for `nav.xhtml` & `toc.ncx`.
     */
    fun toTocEntries(editableItems: List<EditableTocItem>): List<TocEntry> {
        return editableItems
            .filter { it.title.isNotBlank() && it.href.isNotBlank() }
            .map { TocEntry(title = it.title.trim(), href = it.href) }
    }

    /**
     * Merges manual bookmark TOC entries into base TOC entries without duplicates, placing each
     * manual bookmark directly after its parent chapter entry (sorted by paragraphIndex).
     */
    fun mergeManualEntries(
        baseEntries: List<TocEntry>,
        manualEntries: List<ManualTocEntry>
    ): List<TocEntry> {
        if (manualEntries.isEmpty()) return baseEntries

        val sortedManuals = manualEntries
            .filter { it.title.isNotBlank() && it.chapterFileName.isNotBlank() && it.anchorId.isNotBlank() }
            .sortedWith(compareBy<ManualTocEntry> { it.chapterIndex }.thenBy { it.paragraphIndex })

        if (sortedManuals.isEmpty()) return baseEntries

        val manualsByChapterFile = sortedManuals.groupBy { it.chapterFileName }
        val consumedChapters = HashSet<String>()
        val merged = mutableListOf<TocEntry>()
        val seenHrefs = HashSet<String>()

        for (entry in baseEntries) {
            if (seenHrefs.add(entry.href)) {
                merged.add(entry)
            }
            val chapterFileName = entry.href.substringAfterLast('/').substringBefore('#')
            if (chapterFileName.isNotBlank() && consumedChapters.add(chapterFileName)) {
                val chapterBookmarks = manualsByChapterFile[chapterFileName].orEmpty()
                for (bm in chapterBookmarks) {
                    val bmHref = "Text/${bm.chapterFileName}#${bm.anchorId}"
                    if (seenHrefs.add(bmHref)) {
                        merged.add(TocEntry(title = bm.title.trim(), href = bmHref))
                    }
                }
            }
        }

        // Append any manual bookmarks whose chapter wasn't listed in baseEntries
        for (bm in sortedManuals) {
            if (bm.chapterFileName !in consumedChapters) {
                val bmHref = "Text/${bm.chapterFileName}#${bm.anchorId}"
                if (seenHrefs.add(bmHref)) {
                    merged.add(TocEntry(title = bm.title.trim(), href = bmHref))
                }
            }
        }

        return merged
    }

    /**
     * Scans each staged chapter file to derive clean TOC titles from <h1>/<h2>/<h3>
     * or chapter-title first paragraphs.
     */
    fun generateEntriesFromChapters(chapters: List<ChapterDescriptor>): List<TocEntry> {
        return chapters.mapIndexed { idx, chap ->
            val file = File(chap.filePath)
            val resolvedTitle = if (file.exists()) {
                extractBestChapterTitle(file, chap.title.ifBlank { "Chương ${idx + 1}" })
            } else {
                chap.title.ifBlank { "Chương ${idx + 1}" }
            }
            TocEntry(
                title = resolvedTitle,
                href = "Text/${chap.fileName}"
            )
        }
    }

    fun buildNavXhtml(entries: List<TocEntry>, bookLang: String): String {
        val navItems = entries.joinToString("\n") { item ->
            """      <li><a href="${escapeXml(item.href)}">${escapeXml(item.title)}</a></li>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${escapeXml(bookLang)}" lang="${escapeXml(bookLang)}">
<head>
  <meta charset="UTF-8" />
  <title>Mục lục</title>
  <link rel="stylesheet" type="text/css" href="Styles/style.css" />
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Mục lục</h1>
    <ol>
$navItems
    </ol>
  </nav>
</body>
</html>
"""
    }

    fun buildTocNcx(entries: List<TocEntry>, bookUuid: String, bookTitle: String): String {
        val navPoints = entries.mapIndexed { idx, item ->
            """    <navPoint id="navPoint-${idx + 1}" playOrder="${idx + 1}">
      <navLabel><text>${escapeXml(item.title)}</text></navLabel>
      <content src="${escapeXml(item.href)}" />
    </navPoint>"""
        }.joinToString("\n")

        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${escapeXml(bookUuid)}" />
    <meta name="dtb:depth" content="1" />
    <meta name="dtb:totalPageCount" content="0" />
    <meta name="dtb:maxPageNumber" content="0" />
  </head>
  <docTitle><text>${escapeXml(bookTitle)}</text></docTitle>
  <navMap>
$navPoints
  </navMap>
</ncx>
"""
    }

    private fun parseExistingTocEntries(
        unpackedDir: File,
        opfFile: File?,
        originalFileToStagedName: Map<String, String>
    ): List<TocEntry> {
        val opfBaseDir = opfFile?.parentFile ?: unpackedDir

        // 1. Try EPUB 3 nav.xhtml first (via OPF manifest properties="nav" or file scan)
        val navFile = findNavFile(unpackedDir, opfFile, opfBaseDir)
        if (navFile != null && navFile.exists()) {
            val navEntries = parseNavXhtmlFile(navFile, originalFileToStagedName)
            if (navEntries.isNotEmpty()) return navEntries
        }

        // 2. Try EPUB 2 toc.ncx (via OPF manifest or file scan)
        val ncxFile = findNcxFile(unpackedDir, opfFile, opfBaseDir)
        if (ncxFile != null && ncxFile.exists()) {
            val ncxEntries = parseTocNcxFile(ncxFile, originalFileToStagedName)
            if (ncxEntries.isNotEmpty()) return ncxEntries
        }

        return emptyList()
    }

    private fun findNavFile(unpackedDir: File, opfFile: File?, opfBaseDir: File): File? {
        if (opfFile != null && opfFile.exists()) {
            val opfDoc = Jsoup.parse(opfFile, "UTF-8", "", Parser.xmlParser())
            val navHref = opfDoc.select("manifest > item")
                .firstOrNull { it.attr("properties").split(Regex("""\s+""")).contains("nav") }
                ?.attr("href")
            if (!navHref.isNullOrBlank()) {
                val candidate = File(opfBaseDir, decodeHref(navHref))
                if (candidate.exists()) return candidate
            }
        }
        return unpackedDir.walkTopDown().firstOrNull {
            it.isFile && (it.name.equals("nav.xhtml", ignoreCase = true) || it.name.equals("toc.xhtml", ignoreCase = true))
        }
    }

    private fun findNcxFile(unpackedDir: File, opfFile: File?, opfBaseDir: File): File? {
        if (opfFile != null && opfFile.exists()) {
            val opfDoc = Jsoup.parse(opfFile, "UTF-8", "", Parser.xmlParser())
            val ncxHref = opfDoc.select("manifest > item")
                .firstOrNull { it.attr("media-type").equals("application/x-dtbncx+xml", ignoreCase = true) }
                ?.attr("href")
            if (!ncxHref.isNullOrBlank()) {
                val candidate = File(opfBaseDir, decodeHref(ncxHref))
                if (candidate.exists()) return candidate
            }
        }
        return unpackedDir.walkTopDown().firstOrNull {
            it.isFile && it.extension.equals("ncx", ignoreCase = true)
        }
    }

    private fun parseNavXhtmlFile(
        navFile: File,
        originalFileToStagedName: Map<String, String>
    ): List<TocEntry> {
        val doc = runCatching { Jsoup.parse(navFile, "UTF-8") }.getOrNull() ?: return emptyList()
        val tocNav = doc.selectFirst("nav[epub|type=toc], nav[type=toc], nav#toc, nav") ?: return emptyList()
        val baseDir = navFile.parentFile
        val entries = mutableListOf<TocEntry>()

        for (anchor in tocNav.select("a[href]")) {
            val label = anchor.text().trim()
            val rawHref = anchor.attr("href").trim()
            if (label.isEmpty() || rawHref.isEmpty()) continue
            val mappedHref = mapOriginalHrefToStaged(baseDir, rawHref, originalFileToStagedName)
            if (mappedHref != null) {
                entries.add(TocEntry(title = label, href = mappedHref))
            }
        }
        return entries
    }

    private fun parseTocNcxFile(
        ncxFile: File,
        originalFileToStagedName: Map<String, String>
    ): List<TocEntry> {
        val doc = runCatching { Jsoup.parse(ncxFile, "UTF-8", "", Parser.xmlParser()) }.getOrNull()
            ?: return emptyList()
        val baseDir = ncxFile.parentFile
        val entries = mutableListOf<TocEntry>()

        for (point in doc.select("navPoint")) {
            val label = point.selectFirst("> navLabel > text, navLabel text")?.text()?.trim().orEmpty()
            val src = point.selectFirst("> content[src], content[src]")?.attr("src")?.trim().orEmpty()
            if (label.isEmpty() || src.isEmpty()) continue
            val mappedHref = mapOriginalHrefToStaged(baseDir, src, originalFileToStagedName)
            if (mappedHref != null) {
                entries.add(TocEntry(title = label, href = mappedHref))
            }
        }
        return entries
    }

    private fun mapOriginalHrefToStaged(
        baseDir: File?,
        rawHref: String,
        originalFileToStagedName: Map<String, String>
    ): String? {
        val pathPart = decodeHref(rawHref.substringBefore('#'))
        if (pathPart.isBlank()) return null

        val stagedByCanonical = baseDir?.let { dir ->
            runCatching { File(dir, pathPart).canonicalPath }.getOrNull()
        }?.let { originalFileToStagedName[it] }

        val stagedFileName = stagedByCanonical
            ?: originalFileToStagedName[pathPart.substringAfterLast('/').lowercase()]
            ?: return null

        return "Text/$stagedFileName"
    }

    private fun extractBestChapterTitle(file: File, fallback: String): String {
        val doc = runCatching { Jsoup.parse(file, "UTF-8") }.getOrNull() ?: return fallback
        val heading = doc.selectFirst("h1, h2, h3")?.text()?.trim()
        if (!heading.isNullOrBlank() && heading.length in 2..120) {
            return heading
        }
        val firstMatchingPara = doc.select("p")
            .take(3)
            .map { it.text().trim() }
            .firstOrNull { it.length in 3..100 && chapterHeadingRegex.matches(it) }
        if (!firstMatchingPara.isNullOrBlank()) {
            return firstMatchingPara
        }
        return fallback
    }

    private fun decodeHref(href: String): String {
        return runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
