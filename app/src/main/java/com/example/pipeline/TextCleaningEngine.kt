package com.example.pipeline

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class Step1Result(val junkRemovedCount: Int)
data class Step3Result(val whitespaceFixesCount: Int)

object TextCleaningEngine {

    // Step 2: Single clean unified CSS (strictly no italic, crisp Vietnamese sans-serif, bold headings)
    const val UNIFIED_STYLE_CSS: String = """@charset "UTF-8";

html, body {
  font-family: -apple-system, "SF Pro Text", "Be Vietnam Pro", "Noto Sans", sans-serif;
  font-style: normal !important;
  font-weight: 400;
  color: #1C1C1E;
  background-color: transparent;
  line-height: 1.65;
  margin: 0;
  padding: 0 3%;
}

*, *::before, *::after {
  font-style: normal !important;
  text-decoration: none !important;
}

h1, h2, h3, h4, h5, h6 {
  font-family: -apple-system, "SF Pro Display", "Be Vietnam Pro", "Noto Sans", sans-serif;
  font-weight: 700;
  font-style: normal !important;
  line-height: 1.35;
  margin-top: 1.5em;
  margin-bottom: 0.75em;
  text-align: left;
  page-break-after: avoid;
}

h1 { font-size: 1.45em; }
h2 { font-size: 1.28em; }
h3 { font-size: 1.14em; }

p {
  font-weight: 400;
  font-style: normal !important;
  margin-top: 0;
  margin-bottom: 0.65em;
  text-indent: 1.25em;
  text-align: justify;
}

img {
  max-width: 100%;
  height: auto;
  display: block;
  margin: 1em auto;
}
"""

    private val urlRegex = Regex(
        pattern = """(?i)(?:https?://[^\s<>"]+|www\.[^\s<>"]+|[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.(?:com|vn|net|org|info|xyz|top|site|online|vip|me|io|cc|tv|edu|gov|blog|club|pro)(?:\.[a-z]{2})?(?:/[^\s<>"]*)?)"""
    )

    private val standaloneJunkPatterns: List<Regex> = listOf(
        Regex("""(?iu)^\s*(?:sưu tầm từ|nguồn truyện|nguồn sách|nguồn\s*:|thư viện sách|thư viện ebook)"""),
        Regex("""(?iu)(?:chúc các bạn đọc truyện vui vẻ|chúc bạn đọc sách vui vẻ|chúc các bạn đọc sách vui vẻ)"""),
        Regex("""(?iu)^\s*(?:đọc truyện tại|đọc truyện online|đọc sách miễn phí|tải ebook miễn phí|ebook được thực hiện bởi|thực hiện ebook|tạo ebook bởi|chia sẻ bởi|convert by|biên tập bởi website)"""),
        Regex("""(?iu)^\s*(?:mời bạn đón đọc tại|theo dõi fanpage|truy cập website|bản quyền thuộc về website|hãy ủng hộ tác giả tại)"""),
        Regex("""(?iu)^\s*(?:website|fanpage|group|email|liên hệ)\s*:""")
    )

    private val knownPiracyDomainsRegex = Regex(
        """(?i)\b(?:sachvui|truyenfull|webtruyen|dtv-ebook|tve-4u|nhasachmienphi|isach|tangthuvien|wattpad|truyenyy|metruyenchu)(?:\.[a-z]{2,6})?\b"""
    )

    private val whitespaceSpecialRegex = Regex("""[\u00A0\u200B\u200C\u200D\uFEFF\u2000-\u200A\u202F\u205F\u3000\t\r\n]+""")
    private val multiSpaceRegex = Regex(""" {2,}""")
    private val spaceBeforePunctRegex = Regex("""\s+([,.;:!?)\]»”])""")
    private val spaceAfterOpenBracketRegex = Regex("""([(\[«“])\s+""")
    private val missingSpaceAfterPunctRegex = Regex("""([,;:!?])([\p{L}])""")
    private val missingSpaceAfterPeriodRegex = Regex("""(?<!\.)\.(\p{L}{2,})""")

    /**
     * Step 1: Strip URLs, bare domains, source signatures, and ebook library advertisements.
     */
    fun cleanJunkAndLinks(doc: Document): Step1Result {
        var removedCount = 0

        // Remove anchor links pointing to external URLs (keep inner text only if it's normal prose, else drop)
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val isExternal = href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("https://", ignoreCase = true) ||
                href.startsWith("www.", ignoreCase = true) ||
                href.startsWith("mailto:", ignoreCase = true)

            if (isExternal) {
                val anchorText = anchor.text().trim()
                if (anchorText.isEmpty() || urlRegex.containsMatchIn(anchorText) || isJunkSignature(anchorText)) {
                    anchor.remove()
                    removedCount++
                } else {
                    anchor.unwrap()
                    removedCount++
                }
            }
        }

        // Scan block elements for junk signatures or embedded URLs
        val blocks = doc.body().select("p, div, li, blockquote, span, h4, h5, h6")
        for (el in blocks) {
            if (el.select("img").isNotEmpty()) continue
            // Only process leaf-like text containers to avoid double-counting wrapper divs
            if (el.tagName() == "div" && el.select("p, h1, h2, h3, div").size > 1) continue

            val rawText = el.text().trim()
            if (rawText.isEmpty()) continue

            if (isJunkSignature(rawText)) {
                el.remove()
                removedCount++
                continue
            }

            val urlMatches = urlRegex.findAll(rawText).count()
            var stripped = if (urlMatches > 0) urlRegex.replace(rawText, " ") else rawText
            val domainMatches = knownPiracyDomainsRegex.findAll(stripped).count()
            if (domainMatches > 0) {
                stripped = knownPiracyDomainsRegex.replace(stripped, " ")
            }
            val totalInlineMatches = urlMatches + domainMatches

            if (totalInlineMatches > 0) {
                stripped = stripped.replace(multiSpaceRegex, " ").trim()
                removedCount += totalInlineMatches
                if (stripped.isEmpty() || stripped.length <= 3) {
                    el.remove()
                } else {
                    el.text(stripped)
                }
            }
        }

        return Step1Result(junkRemovedCount = removedCount)
    }

    private fun isJunkSignature(text: String): Boolean {
        if (text.length > 260) return false
        if (standaloneJunkPatterns.any { it.containsMatchIn(text) }) return true
        // If text is short and contains a known ebook site watermark with almost no narrative text left
        if (knownPiracyDomainsRegex.containsMatchIn(text)) {
            val remainder = knownPiracyDomainsRegex.replace(urlRegex.replace(text, ""), "").trim()
            if (remainder.length < 18) return true
        }
        return false
    }

    /**
     * Step 2: Remove all old CSS/styles, strip italics (<i>, <em>, font-style: italic), underlines,
     * strange font colors, and bind the single clean unified stylesheet.
     */
    fun enforceUnifiedTypography(doc: Document, cssRelativeHref: String = "../Styles/style.css") {
        // Remove all embedded <style>, <script>, and external stylesheet links
        doc.select("style, script, link[rel=stylesheet]").remove()

        // Ensure head exists and links our single clean stylesheet
        val head = doc.head()
        head.select("meta[charset]").remove()
        head.prependElement("meta").attr("charset", "UTF-8")
        head.appendElement("link")
            .attr("rel", "stylesheet")
            .attr("type", "text/css")
            .attr("href", cssRelativeHref)

        // Strictly remove italic, underline, font, and strike tags while preserving text
        val forbiddenInlineTags = listOf("i", "em", "u", "font", "s", "strike", "tt", "big", "small")
        for (tag in forbiddenInlineTags) {
            doc.body().select(tag).forEach { el ->
                el.unwrap()
            }
        }

        // Convert bare <div> text blocks into proper <p> paragraphs for uniform styling
        doc.body().select("div").forEach { div ->
            val hasBlockChildren = div.children().any {
                it.tagName() in setOf("p", "div", "h1", "h2", "h3", "h4", "ul", "ol", "table", "section", "article")
            }
            if (!hasBlockChildren && div.select("img").isEmpty() && div.text().isNotBlank()) {
                div.tagName("p")
            }
        }

        // Strip inline style, class, color, face, size attributes from all text elements
        doc.body().allElements.forEach { el ->
            if (el.tagName() != "img" && el.tagName() != "svg" && el.tagName() != "image") {
                el.removeAttr("style")
                el.removeAttr("class")
                el.removeAttr("color")
                el.removeAttr("face")
                el.removeAttr("size")
                el.removeAttr("bgcolor")
                el.removeAttr("align")
            }
        }

        // Ensure bold tags inside headings aren't redundant, keep headings strictly h1..h3
        doc.body().select("h4, h5, h6").forEach { it.tagName("h3") }
    }

    /**
     * Step 3: Absolute whitespace normalization.
     * Trims start/end of every paragraph, collapses consecutive spaces/&nbsp;/tabs to strictly 1 space,
     * normalizes punctuation spacing, and drops empty paragraphs.
     */
    fun normalizeWhitespace(doc: Document): Step3Result {
        var fixCount = 0
        val elements = doc.body().select("p, h1, h2, h3, li, blockquote")

        for (el in elements) {
            if (el.select("img").isNotEmpty()) continue

            val original = el.text()
            val normalized = normalizeParagraphText(original)

            if (normalized.isEmpty()) {
                el.remove()
                fixCount++
            } else if (normalized != original) {
                el.text(normalized)
                fixCount += estimateWhitespaceEdits(original, normalized)
            }
        }

        return Step3Result(whitespaceFixesCount = fixCount)
    }

    fun normalizeParagraphText(raw: String): String {
        if (raw.isEmpty()) return ""
        var text = whitespaceSpecialRegex.replace(raw, " ")
        text = multiSpaceRegex.replace(text, " ")
        text = text.trim()
        text = spaceBeforePunctRegex.replace(text, "$1")
        text = spaceAfterOpenBracketRegex.replace(text, "$1")
        text = missingSpaceAfterPunctRegex.replace(text, "$1 $2")
        text = missingSpaceAfterPeriodRegex.replace(text, ". $1")
        text = multiSpaceRegex.replace(text, " ").trim()
        return text
    }

    private fun estimateWhitespaceEdits(before: String, after: String): Int {
        var count = 0
        if (before.firstOrNull()?.isWhitespace() == true || before.lastOrNull()?.isWhitespace() == true) {
            count++
        }
        count += whitespaceSpecialRegex.findAll(before).count()
        count += multiSpaceRegex.findAll(before).count()
        count += spaceBeforePunctRegex.findAll(before).count()
        return count.coerceAtLeast(1)
    }

    fun extractTitleFromDocument(doc: Document, fallback: String): String {
        val heading = doc.body().selectFirst("h1, h2, h3")?.text()?.trim()
        if (!heading.isNullOrBlank()) return heading.take(80)
        val docTitle = doc.title().trim()
        if (docTitle.isNotBlank()) return docTitle.take(80)
        return fallback
    }

    fun extractEditableParagraphs(doc: Document): List<Element> {
        return doc.body().select("p, h1, h2, h3").filter { el ->
            el.select("img").isEmpty() && el.text().trim().length >= 6
        }
    }
}
