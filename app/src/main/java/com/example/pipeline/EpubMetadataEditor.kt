package com.example.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Editable EPUB metadata fields + cover image state.
 */
data class EpubMetadata(
    val title: String,
    val creator: String,
    val identifier: String,
    val language: String = "vi",
    val existingCoverBytes: ByteArray? = null,
    val newCoverImagePath: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpubMetadata) return false
        return title == other.title &&
            creator == other.creator &&
            identifier == other.identifier &&
            language == other.language &&
            newCoverImagePath == other.newCoverImagePath &&
            existingCoverBytes.contentEquals(other.existingCoverBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + creator.hashCode()
        result = 31 * result + identifier.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + (newCoverImagePath?.hashCode() ?: 0)
        result = 31 * result + (existingCoverBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Reads and writes EPUB OPF metadata (`dc:title`, `dc:creator`, `dc:identifier`, `dc:language`)
 * and handles cover image extraction, JPEG compression, and OPF manifest/metadata registration
 * (`OEBPS/images/cover.jpg`, `properties="cover-image"`, `<meta name="cover" content="cover-image"/>`).
 */
object EpubMetadataEditor {

    private const val COVER_RELATIVE_HREF = "images/cover.jpg"
    private const val MAX_COVER_DIMENSION_PX = 1600
    private const val MAX_PREVIEW_COVER_BYTES = 1_500_000

    /**
     * Reads existing metadata & cover from a selected `.epub` or generates sensible defaults for `.pdf`.
     * Memory-safe: streams ZIP entries directly without unpacking the entire book into RAM.
     */
    fun readMetadataFromSource(context: Context, source: SourceFileInfo): EpubMetadata {
        val defaultMeta = EpubMetadata(
            title = source.baseNameWithoutExt.ifBlank { "Sách chưa đặt tên" },
            creator = "Không rõ tác giả",
            identifier = "urn:uuid:${UUID.randomUUID()}",
            language = "vi"
        )
        if (source.fileType != InputFileType.EPUB) {
            return defaultMeta
        }

        return runCatching {
            val tempCheckFile = File(context.cacheDir, "meta_inspect_${System.currentTimeMillis()}.epub")
            try {
                FilePathResolver.copyInputToWorkFile(context, source, tempCheckFile)
                readMetadataFromEpubFile(tempCheckFile, defaultMeta)
            } finally {
                tempCheckFile.delete()
            }
        }.getOrDefault(defaultMeta)
    }

    /**
     * Pure-File reader (also usable directly in JVM unit tests).
     */
    fun readMetadataFromEpubFile(epubFile: File, fallback: EpubMetadata): EpubMetadata {
        var containerXml: String? = null
        var opfEntryPath: String? = null
        var opfXml: String? = null

        // Pass 1: Locate container.xml or .opf entry
        ZipInputStream(FileInputStream(epubFile).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.equals("META-INF/container.xml", ignoreCase = true)) {
                    containerXml = zis.readBytes().toString(Charsets.UTF_8)
                } else if (opfEntryPath == null && name.endsWith(".opf", ignoreCase = true)) {
                    opfEntryPath = name
                    opfXml = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Resolve canonical OPF path from container.xml if present
        val resolvedOpfPath = containerXml?.let { xml ->
            Jsoup.parse(xml, "", Parser.xmlParser())
                .selectFirst("rootfile[full-path]")
                ?.attr("full-path")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } ?: opfEntryPath

        if (resolvedOpfPath != null && resolvedOpfPath != opfEntryPath) {
            ZipInputStream(FileInputStream(epubFile).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == resolvedOpfPath) {
                        opfXml = zis.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val rawOpf = opfXml ?: return fallback
        val opfDoc = Jsoup.parse(rawOpf, "", Parser.xmlParser())

        val title = opfDoc.selectFirst("dc|title, title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: fallback.title
        val creator = opfDoc.selectFirst("dc|creator, creator")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: fallback.creator
        val identifier = opfDoc.selectFirst("dc|identifier, identifier")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: fallback.identifier
        val language = opfDoc.selectFirst("dc|language, language")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: fallback.language

        // Locate cover image href inside manifest (EPUB 3 properties="cover-image" or EPUB 2 <meta name="cover">)
        val coverHref = resolveCoverHref(opfDoc)
        val coverBytes = if (coverHref != null && resolvedOpfPath != null) {
            val opfDir = resolvedOpfPath.substringBeforeLast('/', "")
            val fullCoverPath = normalizeZipRelativePath(opfDir, coverHref)
            readZipEntryBytesCapped(epubFile, fullCoverPath, MAX_PREVIEW_COVER_BYTES)
        } else {
            null
        }

        return EpubMetadata(
            title = title,
            creator = creator,
            identifier = identifier,
            language = language,
            existingCoverBytes = coverBytes,
            newCoverImagePath = null
        )
    }

    /**
     * Decodes a user-selected cover image URI (from PickVisualMedia) with safe downsampling for 4GB RAM devices
     * and writes a crisp JPEG to [destFile].
     */
    fun prepareCoverImageFromUri(context: Context, imageUri: Uri, destFile: File): File? {
        return runCatching {
            val resolver = context.contentResolver
            // Pass 1: Measure bounds without allocating full bitmap
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > MAX_COVER_DIMENSION_PX ||
                boundsOpts.outHeight / sampleSize > MAX_COVER_DIMENSION_PX
            ) {
                sampleSize *= 2
            }

            // Pass 2: Decode downsampled bitmap
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            try {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                destFile
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    /**
     * Stages the chosen (or preserved) cover image into `OEBPS/images/cover.jpg` and writes
     * a standards-compliant `OEBPS/content.opf` containing all 4 edited metadata fields
     * plus both EPUB 3 (`properties="cover-image"`) and EPUB 2 (`<meta name="cover" content="cover-image"/>`).
     */
    fun writeMetadataAndCoverToStage(
        oebpsDir: File,
        metadata: EpubMetadata,
        chapters: List<ChapterDescriptor>,
        imageRelativePaths: List<String>
    ) {
        val hasCover = stageCoverImageFile(oebpsDir, metadata)

        val safeTitle = metadata.title.trim().ifBlank { "Sách chưa đặt tên" }
        val safeCreator = metadata.creator.trim().ifBlank { "Không rõ tác giả" }
        val safeIdentifier = metadata.identifier.trim().ifBlank { "urn:uuid:${UUID.randomUUID()}" }
        val safeLanguage = metadata.language.trim().ifBlank { "vi" }

        val opfXml = buildContentOpfXml(
            title = safeTitle,
            creator = safeCreator,
            identifier = safeIdentifier,
            language = safeLanguage,
            chapters = chapters,
            imageRelativePaths = imageRelativePaths.filterNot {
                it.equals(COVER_RELATIVE_HREF, ignoreCase = true)
            },
            hasCoverImage = hasCover
        )
        File(oebpsDir, "content.opf").writeText(opfXml, Charsets.UTF_8)
    }

    /**
     * Pure XML generator for `OEBPS/content.opf` (also directly testable in JVM unit tests).
     */
    fun buildContentOpfXml(
        title: String,
        creator: String,
        identifier: String,
        language: String,
        chapters: List<ChapterDescriptor>,
        imageRelativePaths: List<String>,
        hasCoverImage: Boolean
    ): String {
        val manifestChapters = chapters.joinToString("\n") { chap ->
            """    <item id="${chap.id}" href="Text/${chap.fileName}" media-type="application/xhtml+xml" />"""
        }
        val coverManifestLine = if (hasCoverImage) {
            """    <item id="cover-image" href="$COVER_RELATIVE_HREF" media-type="image/jpeg" properties="cover-image" />"""
        } else {
            ""
        }
        val manifestImages = imageRelativePaths.mapIndexed { idx, relPath ->
            """    <item id="img_$idx" href="$relPath" media-type="${mediaTypeForImage(relPath)}" />"""
        }.joinToString("\n")

        val spineItems = chapters.joinToString("\n") { chap ->
            """    <itemref idref="${chap.id}" />"""
        }

        val modifiedIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        val coverMetaLine = if (hasCoverImage) {
            """    <meta name="cover" content="cover-image" />"""
        } else {
            ""
        }

        val manifestExtraLines = listOf(coverManifestLine, manifestChapters, manifestImages)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        val metadataLines = buildString {
            appendLine("""    <dc:identifier id="BookId">${escapeXml(identifier)}</dc:identifier>""")
            appendLine("""    <dc:title>${escapeXml(title)}</dc:title>""")
            appendLine("""    <dc:creator>${escapeXml(creator)}</dc:creator>""")
            appendLine("""    <dc:language>${escapeXml(language)}</dc:language>""")
            appendLine("""    <meta property="dcterms:modified">$modifiedIso</meta>""")
            if (coverMetaLine.isNotBlank()) {
                appendLine(coverMetaLine)
            }
        }.trimEnd()

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId" xml:lang="${escapeXml(language)}">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
$metadataLines
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml" />
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />
    <item id="style" href="Styles/style.css" media-type="text/css" />
$manifestExtraLines
  </manifest>
  <spine toc="ncx">
$spineItems
  </spine>
</package>
"""
    }

    private fun stageCoverImageFile(oebpsDir: File, metadata: EpubMetadata): Boolean {
        val coverDest = File(oebpsDir, COVER_RELATIVE_HREF)
        val newCoverPath = metadata.newCoverImagePath
        if (!newCoverPath.isNullOrBlank()) {
            val src = File(newCoverPath)
            if (src.exists() && src.length() > 0L) {
                coverDest.parentFile?.mkdirs()
                src.copyTo(coverDest, overwrite = true)
                return true
            }
        }
        val existingBytes = metadata.existingCoverBytes
        if (existingBytes != null && existingBytes.isNotEmpty()) {
            coverDest.parentFile?.mkdirs()
            coverDest.writeBytes(existingBytes)
            return true
        }
        return coverDest.exists() && coverDest.length() > 0L
    }

    private fun resolveCoverHref(opfDoc: org.jsoup.nodes.Document): String? {
        // 1. EPUB 3 standard: <item properties="cover-image" href="..."/>
        opfDoc.select("manifest > item").firstOrNull { item ->
            item.attr("properties").split(Regex("""\s+""")).any { it.equals("cover-image", true) }
        }?.attr("href")?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. EPUB 2 standard: <meta name="cover" content="cover-id"/>
        val coverId = opfDoc.selectFirst("metadata > meta[name=cover]")?.attr("content")?.trim()
        if (!coverId.isNullOrBlank()) {
            opfDoc.selectFirst("manifest > item[id=$coverId]")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // 3. Fallback heuristic: manifest image with "cover" in id or href
        return opfDoc.select("manifest > item").firstOrNull { item ->
            val mt = item.attr("media-type").lowercase()
            mt.startsWith("image/") && (
                item.attr("id").contains("cover", ignoreCase = true) ||
                    item.attr("href").contains("cover", ignoreCase = true)
                )
        }?.attr("href")?.takeIf { it.isNotBlank() }
    }

    private fun normalizeZipRelativePath(baseDir: String, href: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        val combined = if (baseDir.isBlank()) decoded else "$baseDir/$decoded"
        val segments = ArrayDeque<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun readZipEntryBytesCapped(zipFile: File, entryPath: String, maxBytes: Int): ByteArray? {
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.equals(entryPath, ignoreCase = true)) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    var total = 0
                    var read: Int
                    while (zis.read(buf).also { read = it } != -1) {
                        total += read
                        if (total > maxBytes) return null
                        out.write(buf, 0, read)
                    }
                    return out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
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
