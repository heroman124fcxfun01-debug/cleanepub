package com.example.pipeline

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class DialogueFormatResult(
    val splitCount: Int,
    val normalizedDashCount: Int
) {
    val totalChanges: Int
        get() = splitCount + normalizedDashCount
}

/**
 * 100% Offline Stuck Dialogue Splitter & Leading Dash Standardizer (Upgrade 1.4):
 * 1. Splits multiple dialogue turns stuck inside a single `<p>` paragraph into separate `<p>` tags
 *    whenever sentence-ending punctuation (`.`, `?`, `!`, `…`, `”`, `"`) is followed by whitespace,
 *    a dialogue dash (`-`, `–`, `—`), and an uppercase-starting dialogue turn.
 * 2. Standardizes messy leading dialogue dashes (`- `, `-- `, `_ `, `• `, `– `, `—`) at the start
 *    of `<p>` paragraphs into a unified book-grade em-dash prefix (`— `).
 */
object DialogueFormatter {

    private const val STANDARD_DIALOGUE_PREFIX = "— "

    // Split point: after sentence-ending punctuation (. ? ! … ” "), whitespace, then a dialogue dash (- – —)
    // followed by optional spaces + optional opening quote + uppercase letter.
    private val stuckDialogueSplitRegex = Regex(
        """(?<=[.?!…”"])\s+(?=[-–—]\s*(?:["“«'‘]\s*)?\p{Lu})"""
    )

    // Messy leading dialogue prefixes:
    // - "- Text", "-- Text", "--- Text", "_ Text", "• Text", "– Text"
    // - "—Text" (missing space) or "—   Text" (extra spaces)
    // - "-Text" / "-“Text”" (dash glued directly to uppercase letter or opening quote; never touches "-5")
    private val messyLeadingDashWithSpaceRegex = Regex(
        """^(?:-{1,3}|_|•|–|—)\s+(.+)$"""
    )
    private val gluedLeadingDashRegex = Regex(
        """^(?:-{1,2}|–|—)((?:["“«'‘]\s*)?\p{Lu}.+)$"""
    )

    private val decorativeLineRegex = Regex("""^[-–—_=*~•·.\s]+$""")
    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /**
     * Pure helper: splits stuck dialogue turns inside a single paragraph string.
     * Returns a list of 1+ trimmed segments (length > 1 means splits occurred).
     */
    fun splitStuckDialogue(rawParagraph: String): List<String> {
        val text = rawParagraph.trim()
        if (text.length < 10) return if (text.isEmpty()) emptyList() else listOf(text)

        val parts = stuckDialogueSplitRegex.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (parts.size <= 1) return listOf(text)

        // Safety check: ensure no split creates a tiny spurious fragment (< 3 chars, e.g. "A.")
        val merged = mutableListOf<String>()
        for (part in parts) {
            if (merged.isNotEmpty() && merged.last().length < 4) {
                merged[merged.lastIndex] = "${merged.last()} $part"
            } else {
                merged.add(part)
            }
        }
        return merged
    }

    /**
     * Pure helper: normalizes messy leading dialogue dashes (`- `, `-- `, `_ `, `• `, `– `, `—`)
     * into the unified standard `— ` prefix.
     * Returns `(formattedText, wasChanged)`.
     */
    fun normalizeLeadingDialogueDash(rawParagraph: String): Pair<String, Boolean> {
        val text = rawParagraph.trim()
        if (text.isEmpty() || decorativeLineRegex.matches(text)) return text to false

        // Already exact standard "— X" with single space
        if (text.startsWith(STANDARD_DIALOGUE_PREFIX) &&
            text.length > STANDARD_DIALOGUE_PREFIX.length &&
            !text[STANDARD_DIALOGUE_PREFIX.length].isWhitespace()
        ) {
            return text to false
        }

        val spacedMatch = messyLeadingDashWithSpaceRegex.matchEntire(text)
        if (spacedMatch != null) {
            val rest = spacedMatch.groupValues[1].trimStart()
            if (rest.isNotEmpty() && !rest.first().isDigit()) {
                val formatted = "$STANDARD_DIALOGUE_PREFIX$rest"
                return formatted to (formatted != text)
            }
        }

        val gluedMatch = gluedLeadingDashRegex.matchEntire(text)
        if (gluedMatch != null) {
            val rest = gluedMatch.groupValues[1].trimStart()
            if (rest.isNotEmpty()) {
                val formatted = "$STANDARD_DIALOGUE_PREFIX$rest"
                return formatted to (formatted != text)
            }
        }

        return text to false
    }

    /**
     * Formats all eligible `<p>` elements inside a chapter `Document`:
     * 1. Splits stuck dialogue turns into separate `<p>` sibling elements.
     * 2. Standardizes leading dialogue dashes to `— `.
     * Preserves headings (`h1`–`h6`) and any paragraphs containing images/media.
     */
    fun formatDocument(doc: Document): DialogueFormatResult {
        val body = doc.body() ?: return DialogueFormatResult(0, 0)
        val originalParagraphs = body.select("p").toList()
        var splitCount = 0
        var normalizedDashCount = 0

        for (p in originalParagraphs) {
            if (!isFormattableParagraph(p)) continue
            val rawText = p.text().trim()
            if (rawText.isEmpty()) continue

            val splitParts = splitStuckDialogue(rawText)
            if (splitParts.size > 1) {
                splitCount += (splitParts.size - 1)

                val (firstNormalized, firstChanged) = normalizeLeadingDialogueDash(splitParts[0])
                if (firstChanged) normalizedDashCount++
                p.text(firstNormalized)

                var anchor: Element = p
                for (i in 1 until splitParts.size) {
                    val (segNormalized, segChanged) = normalizeLeadingDialogueDash(splitParts[i])
                    if (segChanged) normalizedDashCount++
                    val newP = Element("p").text(segNormalized)
                    anchor.after(newP)
                    anchor = newP
                }
            } else {
                val (normalized, changed) = normalizeLeadingDialogueDash(rawText)
                if (changed) {
                    p.text(normalized)
                    normalizedDashCount++
                }
            }
        }

        return DialogueFormatResult(
            splitCount = splitCount,
            normalizedDashCount = normalizedDashCount
        )
    }

    private fun isFormattableParagraph(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        if (tag != "p" || tag in headingTags) return false
        if (el.select("img, svg, table, video, audio").isNotEmpty()) return false
        return el.text().isNotBlank()
    }
}
