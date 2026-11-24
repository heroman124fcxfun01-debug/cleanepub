package com.example.pipeline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Character span `[start, end)` inside a string that differs between original and current versions.
 */
data class DiffSpan(
    val start: Int,
    val end: Int
)

/**
 * Pure data result of comparing two paragraphs at word/token granularity (JVM unit-test friendly).
 */
data class WordDiffResult(
    val originalChangedSpans: List<DiffSpan>,
    val currentChangedSpans: List<DiffSpan>,
    val hasDifferences: Boolean
)

/**
 * Ready-to-render Compose [AnnotatedString] pair for Split-Screen Comparison.
 */
data class DiffAnnotatedPair(
    val originalAnnotated: AnnotatedString,
    val currentAnnotated: AnnotatedString,
    val hasDifferences: Boolean
)

/**
 * Ultra-fast 100% offline word-level diff engine for ManualEditorScreen Split-Screen Comparison.
 * - Preserves exact character offsets (including whitespace and punctuation).
 * - Trims common prefix & suffix tokens first so typical edits run in O(k) microseconds.
 * - Uses bounded LCS DP on the changed middle window, gracefully falling back to span marking
 *   for extremely large paragraphs (> 180 changed tokens) to guarantee 60fps UI responsiveness.
 */
object TextDiffHelper {

    private data class IndexedToken(
        val text: String,
        val start: Int,
        val end: Int,
        val isWhitespace: Boolean
    )

    private val defaultRemovedBg = Color(0x4AD94848)
    private val defaultRemovedText = Color(0xFF7A1414)
    private val defaultAddedBg = Color(0x52D99B26)
    private val defaultAddedText = Color(0xFF4A2500)

    /**
     * Computes exact differing character spans between [original] and [current].
     */
    fun computeWordDiffRanges(original: String, current: String): WordDiffResult {
        if (original == current) {
            return WordDiffResult(emptyList(), emptyList(), hasDifferences = false)
        }
        if (original.isEmpty()) {
            return WordDiffResult(
                originalChangedSpans = emptyList(),
                currentChangedSpans = listOf(DiffSpan(0, current.length)),
                hasDifferences = true
            )
        }
        if (current.isEmpty()) {
            return WordDiffResult(
                originalChangedSpans = listOf(DiffSpan(0, original.length)),
                currentChangedSpans = emptyList(),
                hasDifferences = true
            )
        }

        val origTokens = tokenizeWithOffsets(original)
        val currTokens = tokenizeWithOffsets(current)

        val origNonWs = origTokens.filter { !it.isWhitespace }
        val currNonWs = currTokens.filter { !it.isWhitespace }

        // 1. Trim identical prefix
        var prefix = 0
        val minLen = minOf(origNonWs.size, currNonWs.size)
        while (prefix < minLen && origNonWs[prefix].text == currNonWs[prefix].text) {
            prefix++
        }

        // 2. Trim identical suffix
        var origEnd = origNonWs.size
        var currEnd = currNonWs.size
        while (origEnd > prefix && currEnd > prefix &&
            origNonWs[origEnd - 1].text == currNonWs[currEnd - 1].text
        ) {
            origEnd--
            currEnd--
        }

        val origSlice = origNonWs.subList(prefix, origEnd)
        val currSlice = currNonWs.subList(prefix, currEnd)

        val origChanged = mutableListOf<DiffSpan>()
        val currChanged = mutableListOf<DiffSpan>()

        val m = origSlice.size
        val n = currSlice.size

        when {
            m == 0 && n == 0 -> {
                // Only whitespace differences between identical word tokens: highlight whole differing whitespace boundary
                val firstDiff = findFirstCharDiff(original, current)
                if (firstDiff < original.length) {
                    origChanged.add(DiffSpan(firstDiff, (firstDiff + 1).coerceAtMost(original.length)))
                }
                if (firstDiff < current.length) {
                    currChanged.add(DiffSpan(firstDiff, (firstDiff + 1).coerceAtMost(current.length)))
                }
            }

            m == 0 -> {
                for (tok in currSlice) currChanged.add(DiffSpan(tok.start, tok.end))
            }

            n == 0 -> {
                for (tok in origSlice) origChanged.add(DiffSpan(tok.start, tok.end))
            }

            m * n <= 32_400 -> {
                // Fast LCS DP on changed middle window (up to ~180x180 changed tokens)
                val cols = n + 1
                val dp = IntArray((m + 1) * cols)
                for (i in m - 1 downTo 0) {
                    val rowOffset = i * cols
                    val nextRowOffset = (i + 1) * cols
                    val a = origSlice[i].text
                    for (j in n - 1 downTo 0) {
                        dp[rowOffset + j] = if (a == currSlice[j].text) {
                            dp[nextRowOffset + j + 1] + 1
                        } else {
                            maxOf(dp[nextRowOffset + j], dp[rowOffset + j + 1])
                        }
                    }
                }

                var i = 0
                var j = 0
                while (i < m && j < n) {
                    if (origSlice[i].text == currSlice[j].text) {
                        i++
                        j++
                    } else if (dp[(i + 1) * cols + j] >= dp[i * cols + (j + 1)]) {
                        origChanged.add(DiffSpan(origSlice[i].start, origSlice[i].end))
                        i++
                    } else {
                        currChanged.add(DiffSpan(currSlice[j].start, currSlice[j].end))
                        j++
                    }
                }
                while (i < m) {
                    origChanged.add(DiffSpan(origSlice[i].start, origSlice[i].end))
                    i++
                }
                while (j < n) {
                    currChanged.add(DiffSpan(currSlice[j].start, currSlice[j].end))
                    j++
                }
            }

            else -> {
                // Linear fallback for massive rewritten paragraphs
                origChanged.add(DiffSpan(origSlice.first().start, origSlice.last().end))
                currChanged.add(DiffSpan(currSlice.first().start, currSlice.last().end))
            }
        }

        val mergedOrig = mergeAdjacentSpans(original, origChanged)
        val mergedCurr = mergeAdjacentSpans(current, currChanged)
        return WordDiffResult(
            originalChangedSpans = mergedOrig,
            currentChangedSpans = mergedCurr,
            hasDifferences = mergedOrig.isNotEmpty() || mergedCurr.isNotEmpty()
        )
    }

    /**
     * Builds highlighted [AnnotatedString]s for both [original] and [current] paragraphs.
     */
    fun buildDiffAnnotatedPair(
        original: String,
        current: String,
        removedBackground: Color = defaultRemovedBg,
        removedTextColor: Color = defaultRemovedText,
        addedBackground: Color = defaultAddedBg,
        addedTextColor: Color = defaultAddedText
    ): DiffAnnotatedPair {
        val diff = computeWordDiffRanges(original, current)
        if (!diff.hasDifferences) {
            return DiffAnnotatedPair(
                originalAnnotated = AnnotatedString(original),
                currentAnnotated = AnnotatedString(current),
                hasDifferences = false
            )
        }

        val origAnnotated = applySpans(original, diff.originalChangedSpans, removedBackground, removedTextColor)
        val currAnnotated = applySpans(current, diff.currentChangedSpans, addedBackground, addedTextColor)
        return DiffAnnotatedPair(
            originalAnnotated = origAnnotated,
            currentAnnotated = currAnnotated,
            hasDifferences = true
        )
    }

    /**
     * Builds a single highlighted [AnnotatedString] for [current] compared against [original],
     * optionally overlaying active Search match highlights on top.
     */
    fun buildCurrentHighlightedText(
        original: String,
        current: String,
        searchMatches: List<Pair<Int, ManualSearchMatch>> = emptyList(),
        activeGlobalMatchIndex: Int = -1,
        diffBg: Color = defaultAddedBg,
        diffTextColor: Color = defaultAddedText,
        searchBg: Color = Color(0xFFF3CA6E),
        activeSearchBg: Color = Color(0xFFE59736),
        inkColor: Color = Color(0xFF3B2F2F)
    ): AnnotatedString {
        val diff = computeWordDiffRanges(original, current)
        return buildAnnotatedString {
            append(current)
            for (span in diff.currentChangedSpans) {
                val s = span.start.coerceIn(0, current.length)
                val e = span.end.coerceIn(s, current.length)
                if (s < e) {
                    addStyle(
                        style = SpanStyle(
                            background = diffBg,
                            color = diffTextColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = s,
                        end = e
                    )
                }
            }
            for ((globalIdx, m) in searchMatches) {
                val s = m.startOffset.coerceIn(0, current.length)
                val e = m.endOffset.coerceIn(s, current.length)
                if (s < e) {
                    addStyle(
                        style = SpanStyle(
                            background = if (globalIdx == activeGlobalMatchIndex) activeSearchBg else searchBg,
                            color = inkColor,
                            fontWeight = if (globalIdx == activeGlobalMatchIndex) FontWeight.Bold else null
                        ),
                        start = s,
                        end = e
                    )
                }
            }
        }
    }

    /**
     * Real-time [VisualTransformation] for [androidx.compose.foundation.text.BasicTextField]
     * so inline edits in Compare Mode keep live diff highlights without altering cursor offsets.
     */
    fun createLiveDiffVisualTransformation(
        originalText: String,
        addedBackground: Color = defaultAddedBg,
        addedTextColor: Color = defaultAddedText
    ): VisualTransformation {
        return VisualTransformation { annotated ->
            val current = annotated.text
            val diff = computeWordDiffRanges(originalText, current)
            if (!diff.hasDifferences) {
                TransformedText(annotated, OffsetMapping.Identity)
            } else {
                val styled = applySpans(current, diff.currentChangedSpans, addedBackground, addedTextColor)
                TransformedText(styled, OffsetMapping.Identity)
            }
        }
    }

    private fun applySpans(
        text: String,
        spans: List<DiffSpan>,
        bgColor: Color,
        fgColor: Color
    ): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            for (span in spans) {
                val s = span.start.coerceIn(0, text.length)
                val e = span.end.coerceIn(s, text.length)
                if (s < e) {
                    addStyle(
                        style = SpanStyle(
                            background = bgColor,
                            color = fgColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = s,
                        end = e
                    )
                }
            }
        }
    }

    private fun tokenizeWithOffsets(text: String): List<IndexedToken> {
        val tokens = ArrayList<IndexedToken>(text.length / 4 + 4)
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            val start = i
            when {
                ch.isWhitespace() -> {
                    while (i < len && text[i].isWhitespace()) i++
                    tokens.add(IndexedToken(text.substring(start, i), start, i, isWhitespace = true))
                }

                Character.isLetterOrDigit(ch) -> {
                    while (i < len && (Character.isLetterOrDigit(text[i]) || text[i] == '\'' || text[i] == '’')) i++
                    tokens.add(IndexedToken(text.substring(start, i), start, i, isWhitespace = false))
                }

                else -> {
                    i++
                    tokens.add(IndexedToken(text.substring(start, i), start, i, isWhitespace = false))
                }
            }
        }
        return tokens
    }

    private fun mergeAdjacentSpans(text: String, spans: List<DiffSpan>): List<DiffSpan> {
        if (spans.size <= 1) return spans
        val merged = ArrayList<DiffSpan>(spans.size)
        var cur = spans[0]
        for (idx in 1 until spans.size) {
            val next = spans[idx]
            val gapIsWhitespaceOnly = next.start >= cur.end &&
                (next.start == cur.end || text.substring(cur.end, next.start).all { it.isWhitespace() })
            if (gapIsWhitespaceOnly) {
                cur = DiffSpan(cur.start, next.end)
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    private fun findFirstCharDiff(a: String, b: String): Int {
        val min = minOf(a.length, b.length)
        var i = 0
        while (i < min && a[i] == b[i]) i++
        return i
    }
}
