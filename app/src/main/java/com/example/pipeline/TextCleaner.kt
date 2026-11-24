package com.example.pipeline

import java.text.Normalizer

data class TextCleanResult(
    val cleanedText: String,
    val spaceCollapseCount: Int,
    val slidingSpaceFixes: Int,
    val fakeSplitFixes: Int,
    val reasons: List<String>
) {
    val totalWordFixes: Int
        get() = slidingSpaceFixes + fakeSplitFixes
}

/**
 * 100% Offline Vietnamese Spacing & Syllable Re-segmentation Cleaner.
 *
 * - Step A: Collapse all repeated spaces, &nbsp;, tabs, zero-width spaces, and normalize punctuation spacing.
 * - Step B/C: Delegates full-sentence sliding-window Unigram+Bigram Viterbi DP re-segmentation
 *   to [VietnameseSyllableFixer].
 */
class TextCleaner(
    private val dict: OfflineVietnameseDict = OfflineVietnameseDict
) {

    private val syllableFixer = VietnameseSyllableFixer(dict)

    private val specialWhitespaceRegex = Regex("""[\u00A0\u200B\u200C\u200D\uFEFF\u2000-\u200A\u202F\u205F\u3000\t\r\n]+""")
    private val multiSpaceRegex = Regex(""" {2,}""")
    private val spaceBeforePunctRegex = Regex("""\s+([,.;:!?)\]»”])""")
    private val spaceAfterOpenBracketRegex = Regex("""([(\[«“])\s+""")
    private val missingSpaceAfterPunctRegex = Regex("""([,;:!?])([\p{L}])""")
    private val missingSpaceAfterPeriodRegex = Regex("""(?<!\.)\.(\p{L}{2,})""")

    /**
     * Step A: Normalize Unicode NFC and collapse all consecutive/special whitespace into strictly 1 space.
     */
    fun collapseSpaces(raw: String): Pair<String, Int> {
        if (raw.isEmpty()) return "" to 0
        val nfc = if (Normalizer.isNormalized(raw, Normalizer.Form.NFC)) {
            raw
        } else {
            Normalizer.normalize(raw, Normalizer.Form.NFC)
        }

        var count = 0
        if (nfc.firstOrNull()?.isWhitespace() == true || nfc.lastOrNull()?.isWhitespace() == true) {
            count++
        }
        count += specialWhitespaceRegex.findAll(nfc).count()
        count += multiSpaceRegex.findAll(nfc).count()
        count += spaceBeforePunctRegex.findAll(nfc).count()

        var text = specialWhitespaceRegex.replace(nfc, " ")
        text = multiSpaceRegex.replace(text, " ").trim()
        text = spaceBeforePunctRegex.replace(text, "$1")
        text = spaceAfterOpenBracketRegex.replace(text, "$1")
        text = missingSpaceAfterPunctRegex.replace(text, "$1 $2")
        text = missingSpaceAfterPeriodRegex.replace(text, ". $1")
        text = multiSpaceRegex.replace(text, " ").trim()

        val fixes = if (text != nfc) count.coerceAtLeast(1) else 0
        return text to fixes
    }

    /**
     * Full offline paragraph cleaner: Step A (whitespace collapse) -> Viterbi DP [VietnameseSyllableFixer].
     */
    fun cleanParagraph(
        rawText: String,
        protectedProperNouns: Collection<String> = emptyList()
    ): TextCleanResult {
        val (collapsed, spaceCount) = collapseSpaces(rawText)
        if (collapsed.isEmpty() || !dict.isLoaded) {
            return TextCleanResult(
                cleanedText = collapsed,
                spaceCollapseCount = spaceCount,
                slidingSpaceFixes = 0,
                fakeSplitFixes = 0,
                reasons = emptyList()
            )
        }

        val dpResult = syllableFixer.fixText(collapsed, protectedProperNouns)
        return TextCleanResult(
            cleanedText = dpResult.cleanedText,
            spaceCollapseCount = spaceCount,
            slidingSpaceFixes = dpResult.slidingSpaceFixes,
            fakeSplitFixes = dpResult.mergeFixes,
            reasons = dpResult.reasons
        )
    }
}
