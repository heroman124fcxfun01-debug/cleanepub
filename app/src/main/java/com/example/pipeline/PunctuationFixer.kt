package com.example.pipeline

data class PunctuationFixResult(
    val cleanedText: String,
    val fixCount: Int
)

/**
 * 100% Offline Vietnamese & English Punctuation Normalizer.
 * Runs after whitespace & spelling fixes:
 * 1. Removes spaces before punctuation (. , ; : ? ! ) ] ”)
 * 2. Ensures exactly 1 space after punctuation when followed by a letter (protecting decimals like 3.14 & abbreviations like TP.HCM)
 * 3. Removes spaces immediately after opening brackets/quotes ( [ “ and immediately before closing ) ] ”
 * 4. Collapses duplicated punctuation (,, -> ,, .. -> . while preserving ..., ?? -> ?, !! -> !)
 * 5. Auto-capitalizes the first letter after terminal punctuation (. ? !) if lowercased by OCR
 */
object PunctuationFixer {

    private const val ELLIPSIS_PLACEHOLDER = "\uE000"

    // Common dotted abbreviations that must NOT get a space inserted after their internal dot
    // or trigger sentence-start capitalization right after them.
    private val protectedAbbreviationRegex = Regex(
        """\b(?:TP\.HCM|PGS\.TS|GS\.TS|TS\.BS|ThS\.|PGS\.|GS\.|TS\.|BS\.|KS\.|CN\.|St\.|Mr\.|Mrs\.|Ms\.|Dr\.|Prof\.|Jr\.|Sr\.|vs\.|etc\.|e\.g\.|i\.e\.|v\.v\.|U\.S\.A|U\.K\.|T\.P\.HCM)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    // 1. Remove spaces before closing/terminal punctuation: . , ; : ? ! ) ] ”
    private val spaceBeforePunctRegex = Regex("""[ \t]+([.,;:?!)\]\u201D])""")

    // 2. Remove spaces right after opening brackets/quotes: ( [ “
    private val spaceAfterOpenBracketRegex = Regex("""([(\[\u201C])[ \t]+""")

    // 3. Duplicated punctuation collapse (ellipsis "..." is shielded first)
    private val multiCommaRegex = Regex(""",{2,}""")
    private val doubleDotRegex = Regex("""(?<!\.)\.\.(?!\.)""")
    private val multiQuestionRegex = Regex("""\?{2,}""")
    private val multiExclamationRegex = Regex("""!{2,}""")
    private val multiSemicolonRegex = Regex(""";{2,}""")

    // 4. Missing space after punctuation followed directly by a Unicode letter (Vietnamese or English)
    private val missingSpaceAfterCommaEtcRegex = Regex("""([,;:?!])(\p{L})""")
    private val missingSpaceAfterDotRegex = Regex("""(?<!\p{Lu})(\.)(\p{L})""")

    // 5. Auto-capitalize lowercase letter right after sentence-ending punctuation (. ? !) + whitespace
    // Optionally allows an opening quote/bracket between the space and the first letter.
    private val lowercaseAfterSentenceEndRegex = Regex(
        """([.?!])(\s+[\u201C"(\[]?)(\p{Ll})"""
    )

    // Also normalize multiple spaces introduced during cleanup
    private val multiSpaceRegex = Regex("""[ \t]{2,}""")

    fun fixParagraph(rawText: String): PunctuationFixResult {
        if (rawText.isBlank()) {
            return PunctuationFixResult(cleanedText = rawText, fixCount = 0)
        }

        var working = rawText
        var fixes = 0

        // Shield protected abbreviations (TP.HCM, PGS.TS, Dr., v.v., ...) so internal dots stay untouched
        val savedTokens = ArrayList<String>()
        working = protectedAbbreviationRegex.replace(working) { match ->
            val tokenIndex = savedTokens.size
            savedTokens.add(match.value)
            "\uE100${tokenIndex}\uE101"
        }

        // Shield standard ellipses ("..." or Unicode "…") before collapsing ".." -> "."
        working = working.replace("...", ELLIPSIS_PLACEHOLDER)

        // Step A: Collapse duplicated punctuation (,, -> ,, .. -> ., ?? -> ?, !! -> !, ;; -> ;)
        working = applyTracked(working, multiCommaRegex, ",") { fixes += it }
        working = applyTracked(working, doubleDotRegex, ".") { fixes += it }
        working = applyTracked(working, multiQuestionRegex, "?") { fixes += it }
        working = applyTracked(working, multiExclamationRegex, "!") { fixes += it }
        working = applyTracked(working, multiSemicolonRegex, ";") { fixes += it }

        // Restore ellipsis placeholder
        working = working.replace(ELLIPSIS_PLACEHOLDER, "...")

        // Step B: Remove spaces before . , ; : ? ! ) ] ”
        working = applyTracked(working, spaceBeforePunctRegex, "$1") { fixes += it }

        // Step C: Remove spaces right after ( [ “
        working = applyTracked(working, spaceAfterOpenBracketRegex, "$1") { fixes += it }

        // Step D: Ensure 1 space after , ; : ? ! when followed by a letter
        working = applyTracked(working, missingSpaceAfterCommaEtcRegex, "$1 $2") { fixes += it }

        // Step E: Ensure 1 space after '.' when followed by a letter,
        // skipping decimals (3.14 doesn't match \p{L}), ellipses (...), and single-letter initials (A.B)
        val dotFixed = StringBuilder(working.length + 8)
        var lastIdx = 0
        for (match in missingSpaceAfterDotRegex.findAll(working)) {
            val start = match.range.first
            val isPartOfEllipsis =
                (start > 0 && working[start - 1] == '.') ||
                    (start + 1 < working.length && working[start + 1] == '.')
            // Skip acronym patterns like "U.S" or "TP.HCM" where right char is uppercase and preceded by uppercase
            val prevChar = if (start > 0) working[start - 1] else ' '
            val nextChar = match.groupValues[2][0]
            val isAcronymDot = prevChar.isUpperCase() && nextChar.isUpperCase()
            // Skip domain-like fragments (e.g., "example.com") if lowercase before and common TLD after
            if (!isPartOfEllipsis && !isAcronymDot) {
                dotFixed.append(working, lastIdx, start)
                dotFixed.append(". ")
                dotFixed.append(match.groupValues[2])
                lastIdx = match.range.last + 1
                fixes++
            }
        }
        if (lastIdx > 0) {
            dotFixed.append(working, lastIdx, working.length)
            working = dotFixed.toString()
        }

        // Step F: Auto-capitalize lowercase first letter after . ? ! (excluding ellipsis "..." when mid-sentence)
        val capFixed = StringBuilder(working.length)
        var capLast = 0
        for (match in lowercaseAfterSentenceEndRegex.findAll(working)) {
            val punctPos = match.range.first
            val punctChar = match.groupValues[1][0]
            // Don't force-capitalize mid-sentence continuation after "..."
            val isEllipsis = punctChar == '.' && punctPos >= 2 &&
                working[punctPos - 1] == '.' && working[punctPos - 2] == '.'
            if (!isEllipsis) {
                val lowerStr = match.groupValues[3]
                val upperStr = lowerStr.uppercase()
                if (upperStr != lowerStr) {
                    capFixed.append(working, capLast, punctPos)
                    capFixed.append(match.groupValues[1])
                    capFixed.append(match.groupValues[2])
                    capFixed.append(upperStr)
                    capLast = match.range.last + 1
                    fixes++
                }
            }
        }
        if (capLast > 0) {
            capFixed.append(working, capLast, working.length)
            working = capFixed.toString()
        }

        // Collapse any accidental double spaces
        working = multiSpaceRegex.replace(working, " ").trim()

        // Restore protected abbreviations
        if (savedTokens.isNotEmpty()) {
            for (idx in savedTokens.indices) {
                working = working.replace("\uE100${idx}\uE101", savedTokens[idx])
            }
        }

        return PunctuationFixResult(
            cleanedText = working,
            fixCount = if (working != rawText) fixes.coerceAtLeast(1) else 0
        )
    }

    private inline fun applyTracked(
        input: String,
        regex: Regex,
        replacement: String,
        onCount: (Int) -> Unit
    ): String {
        val count = regex.findAll(input).count()
        if (count == 0) return input
        onCount(count)
        return regex.replace(input, replacement)
    }
}
