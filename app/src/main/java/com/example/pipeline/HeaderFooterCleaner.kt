package com.example.pipeline

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File

data class HeaderFooterCleanResult(
    val standalonePageNumbersRemoved: Int,
    val repeatedHeadersFootersRemoved: Int
) {
    val totalRemoved: Int
        get() = standalonePageNumbersRemoved + repeatedHeadersFootersRemoved
}

/**
 * 100% Offline Standalone Page Number & Repeated Running Header/Footer Cleaner (Upgrade 1.4):
 * Runs BEFORE `ParagraphMerger` so page numbers and running headers/footers (especially from PDFs)
 * are never accidentally merged into adjacent story sentences.
 *
 * 1. Removes standalone page numbers/markers (`142`, `- 85 -`, `[ 85 ]`, `Trang 142`, `Page 142`, `142 / 300`).
 * 2. Counts short candidate lines (< 60 chars, ALL CAPS or book/chapter header style, non-dialogue,
 *    non-narrative sentences) across the book and removes exact duplicates repeating >= 3 times.
 */
object HeaderFooterCleaner {

    private const val MAX_HEADER_LENGTH = 60
    private const val MIN_REPEAT_THRESHOLD = 3
    private const val MAX_TRACKED_CANDIDATES = 4000

    private val multiSpaceRegex = Regex("\\s+")

    // Matches standalone page numbers / page indicators:
    // "142", "- 85 -", "– 85 –", "[ 85 ]", "(85)", "Trang 142", "Page 142", "Page 142 of 300", "142 / 300"
    private val standalonePagePatterns: List<Regex> = listOf(
        Regex("""^\d{1,5}$"""),
        Regex("""^[-–—~•·*]{1,3}\s*\d{1,5}\s*[-–—~•·*]{1,3}$"""),
        Regex("""^[\[\(\{]\s*\d{1,5}\s*[\]\)\}]$"""),
        Regex(
            """^(?:trang|page|pg\.?|tr\.?|p\.?)\s*[:.#-]?\s*\d{1,5}(?:\s*(?:/|of|trên)\s*\d{1,5})?$""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""^\d{1,5}\s*/\s*\d{1,5}$"""),
        Regex("""^[-–—]?\s*(?:trang|page)\s+\d{1,5}\s*[-–—]?$""", RegexOption.IGNORE_CASE)
    )

    private val dialogueStartChars = setOf('-', '–', '—', '"', '“', '”', '\'', '‘', '«')
    private val narrativeEndChars = setOf('.', '?', '!', '…', ',', ';')
    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun normalizeLineKey(raw: String): String {
        return multiSpaceRegex.replace(raw.trim(), " ")
    }

    /**
     * Returns true if the paragraph consists solely of a page number or page label.
     */
    fun isStandalonePageNumber(raw: String): Boolean {
        val text = normalizeLineKey(raw)
        if (text.isEmpty() || text.length > 36) return false
        return standalonePagePatterns.any { it.matches(text) }
    }

    /**
     * Returns true if a short line qualifies as a running Header/Footer candidate:
     * - Under 60 characters, at least 3 chars and at least 2 letters
     * - NOT a dialogue line (never starts with dash or quote)
     * - NOT a normal narrative sentence ending in `. ? ! … , ;` (unless ALL CAPS)
     */
    fun isHeaderFooterCandidate(raw: String): Boolean {
        val text = normalizeLineKey(raw)
        if (text.length < 3 || text.length >= MAX_HEADER_LENGTH) return false
        if (isStandalonePageNumber(text)) return false

        val first = text.first()
        if (first in dialogueStartChars) return false

        var letterCount = 0
        var upperLetterCount = 0
        for (ch in text) {
            if (Character.isLetter(ch)) {
                letterCount++
                if (Character.isUpperCase(ch)) upperLetterCount++
            }
        }
        if (letterCount < 2) return false

        val isAllCaps = upperLetterCount == letterCount
        val last = text.last()

        // Normal story sentences ending with sentence punctuation are never headers unless ALL CAPS
        if (last in narrativeEndChars && !isAllCaps) return false

        // Reject common short story exclamations / single-word dialogue reactions
        val words = text.split(' ')
        if (words.size == 1 && !isAllCaps && text.length < 6) return false

        return true
    }

    /**
     * Pure helper: collects exact normalized short header/footer lines repeating >= `minOccurrences` times.
     */
    fun detectRepeatedHeadersFromLines(
        lines: Iterable<String>,
        minOccurrences: Int = MIN_REPEAT_THRESHOLD
    ): Set<String> {
        val counts = HashMap<String, Int>(256)
        for (raw in lines) {
            if (!isHeaderFooterCandidate(raw)) continue
            val key = normalizeLineKey(raw)
            val current = counts[key]
            if (current != null) {
                counts[key] = current + 1
            } else if (counts.size < MAX_TRACKED_CANDIDATES) {
                counts[key] = 1
            }
        }
        return counts.filterValues { it >= minOccurrences }.keys
    }

    /**
     * Memory-light streaming scan across all chapters in a workspace (4GB RAM friendly):
     * parses one chapter at a time and tracks only short candidate strings (< 60 chars).
     */
    fun detectRepeatedHeadersAcrossChapters(
        chapters: List<ChapterDescriptor>,
        minOccurrences: Int = MIN_REPEAT_THRESHOLD
    ): Set<String> {
        if (chapters.isEmpty()) return emptySet()
        val counts = HashMap<String, Int>(256)

        for (chapter in chapters) {
            val file = File(chapter.filePath)
            if (!file.exists()) continue
            val doc = Jsoup.parse(file, "UTF-8")
            val body = doc.body() ?: continue

            for (el in body.select("p")) {
                if (!isCleanableParagraph(el)) continue
                val text = el.text()
                if (!isHeaderFooterCandidate(text)) continue
                val key = normalizeLineKey(text)
                val existing = counts[key]
                if (existing != null) {
                    counts[key] = existing + 1
                } else if (counts.size < MAX_TRACKED_CANDIDATES) {
                    counts[key] = 1
                }
            }
        }

        return counts.filterValues { it >= minOccurrences }.keys
    }

    /**
     * Cleans standalone page numbers and repeated running headers/footers inside a single chapter Document.
     * Never deletes `<h1>`–`<h3>` chapter headings or `<p>` blocks containing illustrations (`<img>`/`<svg>`).
     */
    fun cleanDocument(
        doc: Document,
        crossChapterRepeatedHeaders: Set<String> = emptySet(),
        detectWithinDocument: Boolean = true,
        minOccurrences: Int = MIN_REPEAT_THRESHOLD
    ): HeaderFooterCleanResult {
        val body = doc.body() ?: return HeaderFooterCleanResult(0, 0)
        val paragraphs = body.select("p").toList()
        if (paragraphs.isEmpty()) return HeaderFooterCleanResult(0, 0)

        val effectiveRepeated = if (detectWithinDocument) {
            val localRepeated = detectRepeatedHeadersFromLines(
                lines = paragraphs.filter { isCleanableParagraph(it) }.map { it.text() },
                minOccurrences = minOccurrences
            )
            if (crossChapterRepeatedHeaders.isEmpty()) localRepeated else (crossChapterRepeatedHeaders + localRepeated)
        } else {
            crossChapterRepeatedHeaders
        }

        var pageNumsRemoved = 0
        var headersRemoved = 0

        for (p in paragraphs) {
            if (!isCleanableParagraph(p)) continue
            val text = p.text()
            if (isStandalonePageNumber(text)) {
                p.remove()
                pageNumsRemoved++
                continue
            }
            if (effectiveRepeated.isNotEmpty()) {
                val key = normalizeLineKey(text)
                if (key in effectiveRepeated) {
                    p.remove()
                    headersRemoved++
                }
            }
        }

        return HeaderFooterCleanResult(
            standalonePageNumbersRemoved = pageNumsRemoved,
            repeatedHeadersFootersRemoved = headersRemoved
        )
    }

    private fun isCleanableParagraph(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        if (tag != "p" || tag in headingTags) return false
        if (el.select("img, svg, table, video, audio").isNotEmpty()) return false
        return el.text().isNotBlank()
    }
}
