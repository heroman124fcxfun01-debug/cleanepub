package com.example.pipeline

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class ParagraphMergeResult(
    val mergedCount: Int
)

/**
 * 100% Offline Smart Paragraph Merger (Upgrade 1.2):
 * Automatically rejoins consecutive `<p>` tags that were erroneously split mid-sentence
 * (very common in PDF-converted ebooks and OCR/scanned EPUBs).
 *
 * A pair `(p[i], p[i+1])` within the same chapter is merged into one `<p>` separated by
 * a single space ONLY when ALL conditions hold simultaneously:
 * 1. Both elements are direct sibling `<p>` tags (never headings `h1`–`h3`, never containing `<img>`).
 * 2. `p[i]` does NOT end with sentence-ending punctuation (`.`, `?`, `!`, `:`, `…`, `”`, `"`).
 * 3. `p[i+1]` starts with a lowercase letter OR comma/semicolon (`,`, `;`),
 *    and is NOT a dialogue line starting with a dash (`-`, `–`, `—`).
 */
object ParagraphMerger {

    private val sentenceEndChars = setOf('.', '?', '!', ':', '…', '”', '"')
    private val dialogueDashChars = setOf('-', '–', '—')
    private val continuationPunctChars = setOf(',', ';')
    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun mergeBrokenParagraphs(doc: Document): ParagraphMergeResult {
        val body = doc.body() ?: return ParagraphMergeResult(0)
        var mergedCount = 0

        // Snapshot all <p> elements; track removed nodes to handle chains of 3+ broken lines cleanly
        val paragraphs = body.select("p").toList()
        val removed = HashSet<Element>()

        for (current in paragraphs) {
            if (current in removed) continue
            if (!isMergeableParagraphElement(current)) continue

            while (true) {
                val next = current.nextElementSibling() ?: break
                if (next in removed) break
                if (!isMergeableParagraphElement(next)) break

                val firstText = current.text().trim()
                val secondText = next.text().trim()
                if (!shouldMerge(firstText, secondText)) break

                // Join with exactly 1 space (or directly before comma/semicolon for clean punctuation)
                val joined = if (secondText.first() in continuationPunctChars) {
                    "$firstText$secondText"
                } else {
                    "$firstText $secondText"
                }

                current.text(joined)
                next.remove()
                removed.add(next)
                mergedCount++
            }
        }

        return ParagraphMergeResult(mergedCount = mergedCount)
    }

    /**
     * Pure text rule check (also unit-testable directly without DOM).
     */
    fun shouldMerge(firstRaw: String, secondRaw: String): Boolean {
        val first = firstRaw.trim()
        val second = secondRaw.trim()
        if (first.isEmpty() || second.isEmpty()) return false

        // 1. First paragraph must NOT end with sentence-ending punctuation
        val lastChar = first.last()
        if (lastChar in sentenceEndChars) return false

        // 2. Second paragraph must NOT start with a dialogue dash (-, –, —)
        val firstCharOfNext = second.first()
        if (firstCharOfNext in dialogueDashChars) return false

        // 3. Second paragraph MUST start with a lowercase letter OR comma/semicolon
        return Character.isLowerCase(firstCharOfNext) || firstCharOfNext in continuationPunctChars
    }

    private fun isMergeableParagraphElement(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        if (tag != "p" || tag in headingTags) return false
        if (el.select("img, svg, table, video, audio").isNotEmpty()) return false
        return el.text().isNotBlank()
    }
}
