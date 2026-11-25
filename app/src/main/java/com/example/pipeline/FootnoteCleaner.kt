package com.example.pipeline

import org.jsoup.nodes.Document
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

data class FootnoteCleanResult(
    val cleanedText: String,
    val removedCount: Int
)

data class DocumentFootnoteCleanResult(
    val removedCount: Int,
    val modifiedParagraphs: Int
)

/**
 * 100% Offline Inline Footnote Marker Cleaner (Upgrade 1.5):
 * Runs BEFORE punctuation normalization and paragraph merging (`ParagraphMerger`) to strip
 * stray footnote markers glued directly after words or punctuation:
 * - Numeric footnotes up to 3 digits: `[1]`, `[12]`, `[123]`
 * - Single-letter footnotes: `[a]`, `[b]`
 * - Asterisk footnotes: `(*)`, `(**)`, `(***)`, `[*]`, `[**]`, `[***]`
 *
 * Strictly protects:
 * - Standalone numbers (`142`, `(5)`) and standalone markers (`[1] Giới thiệu`)
 * - 4-digit years (`[2025]`, `[2026]`, `[1998]`)
 * - Long descriptive notes inside square brackets (`[Ghi chú của tác giả]`)
 */
object FootnoteCleaner {

    // Matches one or more glued footnote markers immediately after a Unicode letter or
    // closing/sentence punctuation mark (never after whitespace or start-of-string).
    private val gluedFootnoteClusterRegex = Regex(
        """(?<=[\p{L}.,;:?!…”"’\)])((?:\[\d{1,3}\]|\[[A-Za-z]\]|\[\*{1,3}\]|\(\*{1,3}\))+)"""
    )

    // Individual footnote token pattern for counting & <sup> footnote cleanup.
    private val singleFootnoteTokenRegex = Regex(
        """\[\d{1,3}\]|\[[A-Za-z]\]|\[\*{1,3}\]|\(\*{1,3}\)"""
    )

    private val pureFootnoteExactRegex = Regex(
        """^(?:\[\d{1,3}\]|\[[A-Za-z]\]|\[\*{1,3}\]|\(\*{1,3}\))+$"""
    )

    private val targetBlockSelector = "p, li, blockquote, h1, h2, h3"

    /**
     * Pure Kotlin helper: removes inline footnote markers glued to words or punctuation
     * while protecting 4-digit years, standalone numbers, and descriptive bracketed notes.
     */
    fun cleanText(raw: String): FootnoteCleanResult {
        if (raw.length < 3 || (!raw.contains('[') && !raw.contains("(*"))) {
            return FootnoteCleanResult(raw, 0)
        }

        var removed = 0
        val cleaned = gluedFootnoteClusterRegex.replace(raw) { match ->
            val cluster = match.groupValues[1]
            removed += singleFootnoteTokenRegex.findAll(cluster).count().coerceAtLeast(1)
            ""
        }

        return FootnoteCleanResult(
            cleanedText = cleaned,
            removedCount = removed
        )
    }

    /**
     * Cleans glued inline footnote markers across all readable text blocks in a chapter `Document`
     * without disturbing images, links, or legitimate numbers/years/author notes.
     */
    fun cleanDocument(doc: Document): DocumentFootnoteCleanResult {
        val body = doc.body() ?: return DocumentFootnoteCleanResult(0, 0)
        var totalRemoved = 0
        var modifiedParagraphs = 0

        for (block in body.select(targetBlockSelector)) {
            if (block.text().isBlank()) continue
            var blockChanged = false

            // 1. Remove <sup> elements containing exclusively footnote tokens like <sup>[14]</sup> or <sup>(*)</sup>
            //    (math exponents like m<sup>2</sup> do not match pureFootnoteExactRegex and stay untouched).
            for (sup in block.select("sup").toList()) {
                val supText = sup.text().trim()
                if (supText.isNotEmpty() && pureFootnoteExactRegex.matches(supText)) {
                    val tokenCount = singleFootnoteTokenRegex.findAll(supText).count().coerceAtLeast(1)
                    sup.remove()
                    totalRemoved += tokenCount
                    blockChanged = true
                }
            }

            // 2. Clean text nodes inside the block in-place (preserving nested tags & images).
            NodeTraversor.traverse(
                object : NodeVisitor {
                    override fun head(node: Node, depth: Int) {
                        if (node is TextNode) {
                            val whole = node.wholeText
                            if (whole.isEmpty()) return
                            val res = cleanText(whole)
                            if (res.removedCount > 0 && res.cleanedText != whole) {
                                node.text(res.cleanedText)
                                totalRemoved += res.removedCount
                                blockChanged = true
                            }
                        }
                    }

                    override fun tail(node: Node, depth: Int) = Unit
                },
                block
            )

            if (blockChanged) {
                modifiedParagraphs++
            }
        }

        return DocumentFootnoteCleanResult(
            removedCount = totalRemoved,
            modifiedParagraphs = modifiedParagraphs
        )
    }
}
