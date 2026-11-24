package com.example.pipeline

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object EpubWorkspaceManager {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "svg")
    private val chapterExtensions = setOf("xhtml", "html", "htm")

    fun createSessionDir(context: Context): File {
        val root = File(context.cacheDir, "epub_clean_sessions")
        if (root.exists()) {
            // Keep cache tidy on 4GB RAM / low-storage phones: prune sessions older than 2 hours
            val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
            root.listFiles()?.forEach { old ->
                if (old.lastModified() < cutoff) {
                    old.deleteRecursively()
                }
            }
        }
        return File(root, "session_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    fun unpackAndStageEpub(
        sourceEpubFile: File,
        fallbackTitle: String,
        sessionDir: File
    ): PreparedWorkspace {
        val unpackedDir = File(sessionDir, "unpacked").apply { mkdirs() }
        val stageDir = File(sessionDir, "stage").apply { mkdirs() }
        val textDir = File(stageDir, "OEBPS/Text").apply { mkdirs() }
        val stylesDir = File(stageDir, "OEBPS/Styles").apply { mkdirs() }
        val imagesDir = File(stageDir, "OEBPS/Images").apply { mkdirs() }

        // 1. Safe Zip Slip-protected extraction to disk
        val canonicalUnpacked = unpackedDir.canonicalPath + File.separator
        ZipInputStream(FileInputStream(sourceEpubFile).buffered()).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(16 * 1024)
            while (entry != null) {
                val outFile = File(unpackedDir, entry.name)
                if (!outFile.canonicalPath.startsWith(canonicalUnpacked)) {
                    throw SecurityException("Phát hiện đường dẫn không hợp lệ trong tệp EPUB.")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 2. Discover OPF metadata and reading-order chapter list
        var bookTitle = fallbackTitle
        var bookAuthor = "Không rõ tác giả"
        val orderedChapterFiles = mutableListOf<File>()

        val containerFile = File(unpackedDir, "META-INF/container.xml")
        val opfFile: File? = if (containerFile.exists()) {
            val containerDoc = Jsoup.parse(containerFile, "UTF-8", "", Parser.xmlParser())
            val fullPath = containerDoc.selectFirst("rootfile[full-path]")?.attr("full-path")
            if (!fullPath.isNullOrBlank()) File(unpackedDir, fullPath) else null
        } else {
            unpackedDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("opf", true) }
        }

        if (opfFile != null && opfFile.exists()) {
            val opfDoc = Jsoup.parse(opfFile, "UTF-8", "", Parser.xmlParser())
            opfDoc.selectFirst("dc|title, title")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                bookTitle = it
            }
            opfDoc.selectFirst("dc|creator, creator")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                bookAuthor = it
            }

            val manifestById = mutableMapOf<String, String>()
            opfDoc.select("manifest > item").forEach { item ->
                val id = item.attr("id")
                val href = item.attr("href")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifestById[id] = href
                }
            }

            val opfBaseDir = opfFile.parentFile ?: unpackedDir
            opfDoc.select("spine > itemref").forEach { ref ->
                val href = manifestById[ref.attr("idref")] ?: return@forEach
                val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
                val candidate = File(opfBaseDir, decodedHref)
                if (candidate.exists() && candidate.isFile &&
                    candidate.extension.lowercase() in chapterExtensions
                ) {
                    orderedChapterFiles.add(candidate)
                }
            }
        }

        // Fallback scan if spine was empty or non-standard
        if (orderedChapterFiles.isEmpty()) {
            unpackedDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in chapterExtensions }
                .filterNot { it.name.equals("nav.xhtml", ignoreCase = true) || it.name.equals("toc.xhtml", ignoreCase = true) }
                .sortedBy { it.relativeTo(unpackedDir).path }
                .forEach { orderedChapterFiles.add(it) }
        }

        if (orderedChapterFiles.isEmpty()) {
            throw IllegalStateException("Không tìm thấy chương văn bản (.xhtml/.html) trong tệp EPUB.")
        }

        // 3. Preserve valid images only (explicitly exclude old CSS and embedded fonts!)
        val imageMap = mutableMapOf<String, String>() // original filename -> normalized relative path
        val imageRelativePaths = mutableListOf<String>()
        var imgCounter = 0
        unpackedDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in imageExtensions }
            .forEach { imgFile ->
                imgCounter++
                val safeName = "img_%04d.%s".format(imgCounter, imgFile.extension.lowercase())
                val destImg = File(imagesDir, safeName)
                imgFile.copyTo(destImg, overwrite = true)
                imageMap[imgFile.name] = "../Images/$safeName"
                imageRelativePaths.add("Images/$safeName")
            }

        // 4. Normalize each chapter into OEBPS/Text/chapter_XXXX.xhtml (dropping old CSS/fonts)
        val chapters = mutableListOf<ChapterDescriptor>()
        val originalFileToStagedName = HashMap<String, String>(orderedChapterFiles.size * 2)
        orderedChapterFiles.forEachIndexed { idx, srcChapter ->
            val doc = Jsoup.parse(srcChapter, "UTF-8")
            doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(Charsets.UTF_8)
                .prettyPrint(true)

            // Rewire preserved image sources
            doc.select("img[src]").forEach { img ->
                val rawSrc = img.attr("src")
                val baseImgName = rawSrc.substringAfterLast('/').substringBefore('?')
                val mapped = imageMap[baseImgName]
                if (mapped != null) {
                    img.attr("src", mapped)
                } else {
                    img.remove()
                }
            }

            val chapTitle = TextCleaningEngine.extractTitleFromDocument(doc, "Chương ${idx + 1}")
            val fileName = "chapter_%04d.xhtml".format(idx + 1)
            val stagedFile = File(textDir, fileName)
            stagedFile.writeText(buildValidXhtmlDocument(chapTitle, doc.body().html()), Charsets.UTF_8)

            runCatching { srcChapter.canonicalPath }.getOrNull()?.let { canonical ->
                originalFileToStagedName[canonical] = fileName
            }
            originalFileToStagedName.putIfAbsent(srcChapter.name.lowercase(), fileName)

            chapters.add(
                ChapterDescriptor(
                    id = "chap_${idx + 1}",
                    fileName = fileName,
                    title = chapTitle,
                    filePath = stagedFile.absolutePath
                )
            )
        }

        // 5. Smartly inspect original EPUB TOC before deleting unpackedDir
        val tocInspection = TocGenerator.inspectOriginalToc(
            unpackedDir = unpackedDir,
            opfFile = opfFile,
            fileType = InputFileType.EPUB,
            detectedChapterCount = chapters.size,
            originalFileToStagedName = originalFileToStagedName
        )

        // Free unpacked copy immediately to keep disk/cache lean
        unpackedDir.deleteRecursively()

        // Write clean unified style.css
        File(stylesDir, "style.css").writeText(TextCleaningEngine.UNIFIED_STYLE_CSS, Charsets.UTF_8)

        val tempOutputEpub = File(sessionDir, "cleaned_output.epub")
        return PreparedWorkspace(
            sessionDirPath = sessionDir.absolutePath,
            stageDirPath = stageDir.absolutePath,
            tempOutputEpubPath = tempOutputEpub.absolutePath,
            bookTitle = bookTitle,
            bookAuthor = bookAuthor,
            chapters = chapters,
            imageRelativePaths = imageRelativePaths,
            tocInspection = tocInspection
        )
    }

    fun preparePdfWorkspace(
        bookTitle: String,
        sessionDir: File,
        chapters: List<ChapterDescriptor>
    ): PreparedWorkspace {
        val stageDir = File(sessionDir, "stage").apply { mkdirs() }
        val stylesDir = File(stageDir, "OEBPS/Styles").apply { mkdirs() }
        File(stylesDir, "style.css").writeText(TextCleaningEngine.UNIFIED_STYLE_CSS, Charsets.UTF_8)
        val tempOutputEpub = File(sessionDir, "cleaned_output.epub")

        val pdfTocInspection = TocGenerator.inspectOriginalToc(
            unpackedDir = null,
            opfFile = null,
            fileType = InputFileType.PDF,
            detectedChapterCount = chapters.size,
            originalFileToStagedName = emptyMap()
        )

        return PreparedWorkspace(
            sessionDirPath = sessionDir.absolutePath,
            stageDirPath = stageDir.absolutePath,
            tempOutputEpubPath = tempOutputEpub.absolutePath,
            bookTitle = bookTitle,
            bookAuthor = "EpubCleanAI",
            chapters = chapters,
            imageRelativePaths = emptyList(),
            tocInspection = pdfTocInspection
        )
    }

    /**
     * Packages a 100% standards-compliant EPUB container:
     * 1. `mimetype` first entry, STORED (uncompressed), exact bytes "application/epub+zip"
     * 2. `META-INF/container.xml`
     * 3. `OEBPS/content.opf`, `OEBPS/toc.ncx`, `OEBPS/nav.xhtml`
     * 4. `OEBPS/Styles/style.css`, XHTML chapters in `OEBPS/Text`, and images in `OEBPS/Images`
     */
    fun packStandardEpub(
        workspace: PreparedWorkspace,
        regenerateToc: Boolean = workspace.tocInspection?.shouldRegenerateByDefault ?: true
    ): File {
        val stageDir = File(workspace.stageDirPath)
        val metaInfDir = File(stageDir, "META-INF").apply { mkdirs() }
        val oebpsDir = File(stageDir, "OEBPS").apply { mkdirs() }
        val stylesDir = File(oebpsDir, "Styles").apply { mkdirs() }

        // Ensure clean unified CSS is present
        File(stylesDir, "style.css").writeText(TextCleaningEngine.UNIFIED_STYLE_CSS, Charsets.UTF_8)

        val effectiveMeta = workspace.metadata ?: EpubMetadata(
            title = workspace.bookTitle,
            creator = workspace.bookAuthor,
            identifier = "urn:uuid:${UUID.randomUUID()}",
            language = "vi"
        )
        val bookUuid = effectiveMeta.identifier.trim().ifBlank { "urn:uuid:${UUID.randomUUID()}" }
        val bookLang = effectiveMeta.language.trim().ifBlank { "vi" }
        val bookTitle = effectiveMeta.title.trim().ifBlank { workspace.bookTitle }

        // META-INF/container.xml
        File(metaInfDir, "container.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml" />
  </rootfiles>
</container>
""",
            Charsets.UTF_8
        )

        // OEBPS/nav.xhtml & OEBPS/toc.ncx via TocGenerator (preserve original TOC or regenerate smartly)
        val tocEntries = TocGenerator.resolveEntriesForPackaging(workspace, regenerateToc)
        File(oebpsDir, "nav.xhtml").writeText(
            TocGenerator.buildNavXhtml(tocEntries, bookLang),
            Charsets.UTF_8
        )
        File(oebpsDir, "toc.ncx").writeText(
            TocGenerator.buildTocNcx(tocEntries, bookUuid, bookTitle),
            Charsets.UTF_8
        )

        // OEBPS/content.opf + OEBPS/images/cover.jpg via EpubMetadataEditor
        EpubMetadataEditor.writeMetadataAndCoverToStage(
            oebpsDir = oebpsDir,
            metadata = effectiveMeta.copy(
                title = bookTitle,
                identifier = bookUuid,
                language = bookLang
            ),
            chapters = workspace.chapters,
            imageRelativePaths = workspace.imageRelativePaths
        )

        // Write final ZIP archive with uncompressed `mimetype` strictly first
        val outEpub = File(workspace.tempOutputEpubPath)
        if (outEpub.exists()) outEpub.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outEpub))).use { zos ->
            // 1. Uncompressed mimetype first (EPUB Open Container Format requirement)
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimeBytes) }
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // 2. Remaining files compressed in deterministic order
            val allFiles = stageDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(stageDir).path }

            val buffer = ByteArray(16 * 1024)
            for (file in allFiles) {
                val entryPath = file.relativeTo(stageDir).path.replace(File.separatorChar, '/')
                if (entryPath == "mimetype") continue
                val zipEntry = ZipEntry(entryPath).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(zipEntry)
                FileInputStream(file).use { fis ->
                    var len: Int
                    while (fis.read(buffer).also { len = it } != -1) {
                        zos.write(buffer, 0, len)
                    }
                }
                zos.closeEntry()
            }
        }

        return outEpub
    }

    fun buildValidXhtmlDocument(title: String, bodyInnerHtml: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="vi" lang="vi">
<head>
  <meta charset="UTF-8" />
  <title>${escapeXml(title)}</title>
  <link rel="stylesheet" type="text/css" href="../Styles/style.css" />
</head>
<body>
$bodyInnerHtml
</body>
</html>
"""
    }

    private fun mediaTypeForImage(path: String): String {
        return when (path.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
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
