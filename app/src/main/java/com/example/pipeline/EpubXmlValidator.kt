package com.example.pipeline

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class XmlValidationResult(
    val validatedXhtml: String,
    val repairedIssuesCount: Int,
    val isWellFormedXml: Boolean
)

/**
 * 100% Offline Strict XHTML Syntax Validator & Auto-Repair Engine (Upgrade 1.4):
 * Runs mandatorily on EVERY `.xhtml` file right before EPUB packaging (both automatic scan and
 * manual editing exports) to eliminate `XML Parsing Error` across Moon Reader, Google Play Books,
 * Kindle, Kobo, and Apple Books.
 *
 * - Re-parses and re-serializes every chapter via Jsoup using strict XML syntax + XHTML escape mode:
 *   `doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).charset("UTF-8")`
 * - Auto-closes void tags (`<br/>`, `<hr/>`, `<img/>`, `<meta/>`, `<link/>`).
 * - Escapes stray raw `&` -> `&amp;`, `<` -> `&lt;`, strips invalid XML attribute names & broken empty tags.
 * - Verifies final output through JDK's strict offline XML parser (`DocumentBuilderFactory`) with
 *   a guaranteed safe XML fallback if any malformed artifact survives.
 */
object EpubXmlValidator {

    private val validXmlAttrNameRegex = Regex("""^[A-Za-z_:][A-Za-z0-9._:-]*$""")
    private val removableEmptyTags = setOf("span", "i", "b", "em", "strong", "u", "font", "a", "p", "div")

    /**
     * Validates and repairs an XHTML document string into guaranteed well-formed EPUB3 XHTML.
     */
    fun validateAndRepairXhtml(
        rawXhtml: String,
        fallbackTitle: String = "Chương",
        language: String = "vi",
        includeStylesheet: Boolean = true
    ): XmlValidationResult {
        var repairedIssues = 0

        // 1. Strip illegal XML 1.0 control characters before parsing
        val controlSafe = UnicodeSanitizer.stripIllegalXmlChars(rawXhtml)
        if (controlSafe != rawXhtml) repairedIssues++

        // 2. Parse permissively with Jsoup HTML parser (auto-repairs unclosed tags & stray '<', '&')
        val doc = Jsoup.parse(controlSafe)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8")
            .prettyPrint(false)

        val body = doc.body()

        // 3. Remove empty malformed inline/block wrapper tags (preserving media & TOC #id anchors)
        val elementsBottomUp = body.select(removableEmptyTags.joinToString(",")).reversed()
        for (el in elementsBottomUp) {
            if (el.hasAttr("id") && el.attr("id").isNotBlank()) continue
            val hasMedia = el.select("img, svg, br, hr, table, video, audio, nav, ol, ul, li").isNotEmpty()
            if (!hasMedia && el.text().isBlank()) {
                el.remove()
                repairedIssues++
            }
        }

        // 4. Sanitize every element's attributes (remove illegal XML attribute names, ensure img alt)
        for (el in body.allElements) {
            val badAttrs = el.attributes().asList()
                .map { it.key }
                .filter { !validXmlAttrNameRegex.matches(it) }
            for (badKey in badAttrs) {
                el.removeAttr(badKey)
                repairedIssues++
            }
            if (el.tagName().equals("img", ignoreCase = true)) {
                val src = el.attr("src").trim()
                if (src.isEmpty()) {
                    el.remove()
                    repairedIssues++
                } else if (!el.hasAttr("alt")) {
                    el.attr("alt", "")
                }
            }
        }

        // 5. Extract effective title & serialize body under strict XML + XHTML entity settings
        val resolvedTitle = doc.title().trim().ifBlank { fallbackTitle }
        val safeLang = language.trim().ifBlank { "vi" }
        val serializedBody = body.html()

        val candidateXhtml = buildStrictXhtmlEnvelope(
            title = resolvedTitle,
            language = safeLang,
            bodyInnerXml = serializedBody,
            includeStylesheet = includeStylesheet
        )

        // 6. Verify strict XML well-formedness offline using DocumentBuilderFactory
        if (isWellFormedXml(candidateXhtml)) {
            if (candidateXhtml != rawXhtml) repairedIssues = repairedIssues.coerceAtLeast(1)
            return XmlValidationResult(
                validatedXhtml = candidateXhtml,
                repairedIssuesCount = repairedIssues,
                isWellFormedXml = true
            )
        }

        // 7. Guaranteed safe fallback: rebuild clean XML paragraphs & valid <img> tags if exotic corruption survived
        val fallbackBody = buildSafeFallbackBodyXml(doc)
        val fallbackXhtml = buildStrictXhtmlEnvelope(
            title = resolvedTitle,
            language = safeLang,
            bodyInnerXml = fallbackBody,
            includeStylesheet = includeStylesheet
        )
        return XmlValidationResult(
            validatedXhtml = fallbackXhtml,
            repairedIssuesCount = repairedIssues + 1,
            isWellFormedXml = isWellFormedXml(fallbackXhtml)
        )
    }

    /**
     * Validates and repairs an `.xhtml` file on disk in-place.
     */
    fun validateAndRepairFile(
        file: File,
        fallbackTitle: String = "Chương",
        language: String = "vi",
        includeStylesheet: Boolean = true
    ): XmlValidationResult {
        val raw = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        val result = validateAndRepairXhtml(
            rawXhtml = raw,
            fallbackTitle = fallbackTitle,
            language = language,
            includeStylesheet = includeStylesheet
        )
        file.writeText(result.validatedXhtml, Charsets.UTF_8)
        return result
    }

    /**
     * Runs mandatory XHTML validation on every chapter `.xhtml` inside the staged EPUB directory
     * right before ZIP compression. Returns total repaired/validated file issue count.
     */
    fun validateWorkspaceXhtmlFiles(
        stageDir: File,
        chapters: List<ChapterDescriptor>,
        language: String = "vi"
    ): Int {
        var totalRepairs = 0
        val titleByPath = chapters.associate { File(it.filePath).name to it.title }
        val textDir = File(stageDir, "OEBPS/Text")

        if (textDir.exists() && textDir.isDirectory) {
            textDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("xhtml", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { xhtmlFile ->
                    val title = titleByPath[xhtmlFile.name] ?: xhtmlFile.nameWithoutExtension
                    val res = validateAndRepairFile(
                        file = xhtmlFile,
                        fallbackTitle = title,
                        language = language,
                        includeStylesheet = true
                    )
                    totalRepairs += res.repairedIssuesCount
                }
        }

        return totalRepairs
    }

    /**
     * Offline strict XML well-formedness check (external DTD/entity loading disabled).
     */
    fun isWellFormedXml(xml: String): Boolean {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            val builder = factory.newDocumentBuilder()
            // Suppress default System.err noise on malformed probes
            builder.setErrorHandler(null)
            builder.parse(InputSource(StringReader(xml)))
            true
        }.getOrDefault(false)
    }

    private fun buildStrictXhtmlEnvelope(
        title: String,
        language: String,
        bodyInnerXml: String,
        includeStylesheet: Boolean
    ): String {
        val cssLine = if (includeStylesheet) {
            "\n  <link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/style.css\" />"
        } else {
            ""
        }
        return buildString(bodyInnerXml.length + 320) {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<!DOCTYPE html>\n")
            append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"")
            append(escapeXmlText(language))
            append("\" lang=\"")
            append(escapeXmlText(language))
            append("\">\n<head>\n")
            append("  <meta charset=\"UTF-8\" />\n")
            append("  <title>")
            append(escapeXmlText(title))
            append("</title>")
            append(cssLine)
            append("\n</head>\n<body>\n")
            append(bodyInnerXml)
            append("\n</body>\n</html>\n")
        }
    }

    private fun buildSafeFallbackBodyXml(doc: Document): String {
        val sb = StringBuilder()
        val blocks = doc.body().select("h1, h2, h3, p, li, blockquote")
        if (blocks.isNotEmpty()) {
            for (el in blocks) {
                val tag = el.tagName().lowercase().let { if (it in setOf("h1", "h2", "h3", "p")) it else "p" }
                val idAttr = el.attr("id").trim().takeIf { it.isNotEmpty() && validXmlAttrNameRegex.matches(it) }
                val text = el.text().trim()
                if (text.isNotEmpty()) {
                    sb.append('<').append(tag)
                    if (idAttr != null) sb.append(" id=\"").append(escapeXmlText(idAttr)).append('"')
                    sb.append('>').append(escapeXmlText(text)).append("</").append(tag).append(">\n")
                }
            }
        } else {
            val plain = doc.body().text().trim()
            if (plain.isNotEmpty()) {
                sb.append("<p>").append(escapeXmlText(plain)).append("</p>\n")
            }
        }
        for (img in doc.body().select("img[src]")) {
            val src = img.attr("src").trim()
            if (src.isNotEmpty()) {
                val alt = img.attr("alt").trim()
                sb.append("<p><img src=\"")
                    .append(escapeXmlText(src))
                    .append("\" alt=\"")
                    .append(escapeXmlText(alt))
                    .append("\" /></p>\n")
            }
        }
        return sb.toString()
    }

    private fun escapeXmlText(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
