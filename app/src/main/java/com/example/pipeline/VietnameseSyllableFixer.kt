package com.example.pipeline

import java.text.Normalizer

data class SyllableFixResult(
    val cleanedText: String,
    val fixCount: Int,
    val slidingSpaceFixes: Int = 0,
    val mergeFixes: Int = 0,
    val reasons: List<String> = emptyList()
)

/**
 * 100% Offline Vietnamese Spacing & Syllable Re-Segmentation Engine.
 *
 * Uses 4-Tier Unigram + Bigram Frequency Scoring (`HashMap<String, Int>`) combined with
 * Sliding-Window Viterbi Dynamic Programming (windows of 1, 2, and 3 consecutive words):
 *
 * - Tier 1 (Common real syllables): +1000
 * - Tier 2 (Known Bigram / Compound in `vi_words.txt` + curated prose bigrams): +2500 bonus
 * - Tier 3 (Rare but real valid syllables): +50
 * - Tier 4 (Ghost / theoretical / OCR artifact syllables like "cóm", "ột", "ều", "ểm", "ững", "ýt", "ới", "ngà"): -5000
 *
 * Replacement Rule:
 * - Only replaces the original window when `candidateScore - currentScore >= 1500`
 *   (strict semantic superiority, preventing false edits on valid Vietnamese prose).
 * - Preserves original leading capitalization / ALL CAPS and respects punctuation & proper nouns.
 * - Optimized with zero-regex hot loops and O(1) clean-window fast-path (< 50ms / chapter on mobile CPUs).
 */
class VietnameseSyllableFixer(
    private val dict: OfflineVietnameseDict = OfflineVietnameseDict
) {

    companion object {
        const val MIN_SCORE_GAIN: Int = 1500
        private const val MAX_SYLLABLE_LEN: Int = 8
        private const val MAX_WINDOW_CHARS: Int = 24
        private const val MAX_OUTPUT_SEGMENTS: Int = 3
        private const val NEG_INF: Int = Int.MIN_VALUE / 4
    }

    private data class WordToken(
        val leadingPunct: String,
        val core: String,
        val trailingPunct: String
    ) {
        val lowerCore: String = core.lowercase()
        fun render(): String = "$leadingPunct$core$trailingPunct"
    }

    private data class ViterbiCandidate(
        val segments: List<String>,
        val score: Int
    )

    private data class WindowDecision(
        val windowSize: Int,
        val segments: List<String>,
        val gain: Int
    )

    /**
     * Convenience entry point returning only the repaired string.
     */
    fun fix(rawText: String, protectedProperNouns: Collection<String> = emptyList()): String {
        return fixText(rawText, protectedProperNouns).cleanedText
    }

    /**
     * Repairs broken/slid Vietnamese spaces across an entire paragraph or chapter text.
     */
    fun fixText(
        rawText: String,
        protectedProperNouns: Collection<String> = emptyList()
    ): SyllableFixResult {
        if (rawText.isBlank() || !dict.isLoaded) {
            return SyllableFixResult(rawText, 0, 0, 0, emptyList())
        }

        val nfc = if (Normalizer.isNormalized(rawText, Normalizer.Form.NFC)) {
            rawText
        } else {
            Normalizer.normalize(rawText, Normalizer.Form.NFC)
        }

        val protectedWords = buildProtectedWordSet(protectedProperNouns)
        val tokens = tokenize(nfc).toMutableList()
        if (tokens.isEmpty()) {
            return SyllableFixResult(nfc, 0, 0, 0, emptyList())
        }

        var slidingFixCount = 0
        var mergeFixCount = 0

        var i = 0
        while (i < tokens.size) {
            val bestDecision = evaluateSlidingWindowsAt(tokens, i, protectedWords)
            if (bestDecision != null) {
                val endIdx = i + bestDecision.windowSize - 1
                val originalSlice = tokens.subList(i, endIdx + 1).toList()
                val replacement = buildReplacementTokens(originalSlice, bestDecision.segments)

                val actuallyChanged = originalSlice.size != replacement.size ||
                    originalSlice.indices.any { k -> originalSlice[k].lowerCore != replacement[k].lowerCore }

                if (actuallyChanged) {
                    if (replacement.size < originalSlice.size) {
                        mergeFixCount++
                    } else {
                        slidingFixCount++
                    }

                    for (k in endIdx downTo i) {
                        tokens.removeAt(k)
                    }
                    tokens.addAll(i, replacement)
                    i += replacement.size
                    continue
                }
            }
            i++
        }

        val totalFixes = slidingFixCount + mergeFixCount
        val reasons = buildList {
            if (slidingFixCount > 0) add("Sửa lỗi trượt khoảng trắng (Viterbi DP)")
            if (mergeFixCount > 0) add("Ghép âm tiết bị tách (Viterbi DP)")
        }

        val output = if (totalFixes > 0) {
            tokens.joinToString(" ") { it.render() }
        } else {
            nfc
        }

        return SyllableFixResult(
            cleanedText = output,
            fixCount = totalFixes,
            slidingSpaceFixes = slidingFixCount,
            mergeFixes = mergeFixCount,
            reasons = reasons
        )
    }

    /**
     * Evaluates sliding windows starting at [start]:
     * - Size 1: `[start]` (ONLY when `w[start]` is an invalid fused token like "ýtới" of length >= 3)
     * - Size 2: `(w[start], w[start + 1])`
     * - Size 3: `(w[start], w[start + 1], w[start + 2])`
     *
     * Picks the candidate with maximum positive gain >= [MIN_SCORE_GAIN] (1500),
     * deferring if the best multi-word edit leaves `w[start]` completely untouched.
     */
    private fun evaluateSlidingWindowsAt(
        tokens: List<WordToken>,
        start: Int,
        protectedWords: Set<String>
    ): WindowDecision? {
        val first = tokens[start]
        if (!isEligibleVietnameseCore(first.core, first.lowerCore, protectedWords)) {
            return null
        }

        val firstScore = dict.getSyllableScoreNormalized(first.lowerCore)
        var best: WindowDecision? = null

        // Determine maximum valid contiguous Vietnamese window starting at `start` (up to 3 words)
        val maxWindowSize = computeMaxContiguousWindow(tokens, start, protectedWords)

        // Check window size 1 ONLY for fused invalid tokens (e.g., "ýtới" -> "ý tới", "tâmlí" -> "tâm lí")
        if (firstScore == OfflineVietnameseDict.SCORE_INVALID_GHOST && first.lowerCore.length >= 3) {
            val decision1 = evaluateSingleWindow(tokens, start, 1)
            if (decision1 != null) {
                best = decision1
            }
        }

        // Check window sizes 2 and 3
        for (size in 2..maxWindowSize) {
            val decision = evaluateSingleWindow(tokens, start, size) ?: continue
            if (best == null ||
                decision.gain > best.gain ||
                (decision.gain == best.gain && decision.windowSize < best.windowSize)
            ) {
                best = decision
            }
        }

        if (best == null || best.gain < MIN_SCORE_GAIN) {
            return null
        }

        // Minimal-edit deferral: if a multi-word window leaves w[start] completely unchanged,
        // let the sliding pointer advance to start+1 so the tightest window handles the error.
        if (best.windowSize >= 2 && best.segments.firstOrNull() == first.lowerCore) {
            return null
        }

        return best
    }

    private fun evaluateSingleWindow(
        tokens: List<WordToken>,
        start: Int,
        windowSize: Int
    ): WindowDecision? {
        val end = start + windowSize - 1

        // Build concatenated raw lowercase string without spaces
        var totalChars = 0
        for (k in start..end) {
            totalChars += tokens[k].lowerCore.length
        }
        if (totalChars < 2 || totalChars > MAX_WINDOW_CHARS) return null

        val raw = buildString(totalChars) {
            for (k in start..end) {
                append(tokens[k].lowerCore)
            }
        }

        // Step 1: Compute currentScore (Unigram + Internal Bigrams + Left/Right Context Bigrams)
        val leftContext = if (start > 0 &&
            tokens[start - 1].trailingPunct.isEmpty() &&
            tokens[start].leadingPunct.isEmpty() &&
            isPureVietnameseLetters(tokens[start - 1].core)
        ) {
            tokens[start - 1].lowerCore
        } else null

        val rightContext = if (end + 1 < tokens.size &&
            tokens[end].trailingPunct.isEmpty() &&
            tokens[end + 1].leadingPunct.isEmpty() &&
            isPureVietnameseLetters(tokens[end + 1].core)
        ) {
            tokens[end + 1].lowerCore
        } else null

        var allCommon = true
        var allInternalBigrams = true
        var currentScore = 0
        for (k in start..end) {
            val w = tokens[k].lowerCore
            val uScore = dict.getSyllableScoreNormalized(w)
            if (uScore < OfflineVietnameseDict.SCORE_COMMON) {
                allCommon = false
            }
            currentScore += uScore

            if (k == start && leftContext != null) {
                currentScore += dict.getBigramBonusNormalized(leftContext, w)
            }
            if (k > start) {
                val bg = dict.getBigramBonusNormalized(tokens[k - 1].lowerCore, w)
                if (bg == 0) allInternalBigrams = false
                currentScore += bg
            }
            if (k == end && rightContext != null) {
                currentScore += dict.getBigramBonusNormalized(w, rightContext)
            }
        }

        // Fast-path CPU optimization (< 50ms/chapter):
        // 1. If ALL words are Tier-1 common AND every internal pair is already a known Bigram, skip DP.
        // 2. If ALL words are Tier-1 common AND no alternative first-syllable cut exists in `raw`, skip DP.
        if (allCommon && windowSize >= 2 && (allInternalBigrams || !hasAlternativeFirstCut(raw, tokens[start].lowerCore.length))) {
            return null
        }

        // Step 2: Run Viterbi DP to find optimal segmentation of `raw` into 1..3 valid syllables
        val bestCandidate = viterbiResegment(
            raw = raw,
            leftContext = leftContext,
            rightContext = rightContext
        ) ?: return null

        // Skip if identical to original token sequence
        if (bestCandidate.segments.size == windowSize) {
            var identical = true
            for (k in 0 until windowSize) {
                if (bestCandidate.segments[k] != tokens[start + k].lowerCore) {
                    identical = false
                    break
                }
            }
            if (identical) return null
        }

        // Guard: Never merge 2 valid Tier-1 common words into a single non-Tier-1 rare word
        if (allCommon && windowSize == 2 && bestCandidate.segments.size == 1) {
            if (dict.getSyllableScoreNormalized(bestCandidate.segments[0]) < OfflineVietnameseDict.SCORE_COMMON) {
                return null
            }
        }

        // Step 3: Net Gain check (must beat original by >= 1500)
        val gain = bestCandidate.score - currentScore
        if (gain < MIN_SCORE_GAIN) return null

        return WindowDecision(
            windowSize = windowSize,
            segments = bestCandidate.segments,
            gain = gain
        )
    }

    /**
     * Exact Viterbi Dynamic Programming over character indices `0..n` partitioning [raw] into
     * 1, 2, or 3 real Vietnamese syllables (`score >= SCORE_RARE_VALID`), maximizing:
     *   sum(unigramScore) + sum(internalBigramBonus) + leftContextBigram + rightContextBigram
     */
    private fun viterbiResegment(
        raw: String,
        leftContext: String?,
        rightContext: String?
    ): ViterbiCandidate? {
        val n = raw.length
        if (n == 0) return null

        // dp[pos][segCount] maps `lastSyllable` -> best ViterbiCandidate reaching `pos` in `segCount` words
        val dp = Array(n + 1) { Array<HashMap<String, ViterbiCandidate>?>(MAX_OUTPUT_SEGMENTS + 1) { null } }
        dp[0][0] = HashMap<String, ViterbiCandidate>(2).apply {
            put("", ViterbiCandidate(emptyList(), 0))
        }

        for (i in 0 until n) {
            for (segCount in 0 until MAX_OUTPUT_SEGMENTS) {
                val states = dp[i][segCount] ?: continue
                val nextSegCount = segCount + 1
                val maxEnd = minOf(n, i + MAX_SYLLABLE_LEN)

                for (j in (i + 1)..maxEnd) {
                    val seg = raw.substring(i, j)
                    val unigramScore = dict.getSyllableScoreNormalized(seg)
                    // Only allow real Vietnamese syllables (Tier 1 or Tier 3) in candidate partitions
                    if (unigramScore <= OfflineVietnameseDict.SCORE_INVALID_GHOST) continue

                    // Prune impossible remaining lengths for remaining segment budget
                    val remainingChars = n - j
                    val remainingSegs = MAX_OUTPUT_SEGMENTS - nextSegCount
                    if (remainingChars > remainingSegs * MAX_SYLLABLE_LEN) continue
                    if (remainingChars > 0 && remainingSegs == 0) continue

                    val rightBonus = if (j == n && rightContext != null) {
                        dict.getBigramBonusNormalized(seg, rightContext)
                    } else 0

                    for ((prevSeg, prevCand) in states) {
                        var candScore = prevCand.score + unigramScore + rightBonus
                        if (segCount == 0) {
                            if (leftContext != null) {
                                candScore += dict.getBigramBonusNormalized(leftContext, seg)
                            }
                        } else {
                            candScore += dict.getBigramBonusNormalized(prevSeg, seg)
                        }

                        var targetMap = dp[j][nextSegCount]
                        if (targetMap == null) {
                            targetMap = HashMap(4)
                            dp[j][nextSegCount] = targetMap
                        }
                        val existing = targetMap[seg]
                        if (existing == null || candScore > existing.score) {
                            targetMap[seg] = ViterbiCandidate(
                                segments = prevCand.segments + seg,
                                score = candScore
                            )
                        }
                    }
                }
            }
        }

        // Pick highest-scoring complete partition of length 1..3
        var best: ViterbiCandidate? = null
        var bestScore = NEG_INF
        for (segCount in 1..MAX_OUTPUT_SEGMENTS) {
            val finalStates = dp[n][segCount] ?: continue
            for (cand in finalStates.values) {
                if (cand.score > bestScore ||
                    (cand.score == bestScore && best != null && cand.segments.size < best.segments.size)
                ) {
                    bestScore = cand.score
                    best = cand
                }
            }
        }
        return best
    }

    /**
     * Checks in O(1) whether `raw` has ANY valid first-syllable prefix of length != [originalFirstLen].
     * Allows clean Tier-1 windows to bypass DP map allocations in nanoseconds.
     */
    private fun hasAlternativeFirstCut(raw: String, originalFirstLen: Int): Boolean {
        val maxCut = minOf(raw.length, MAX_SYLLABLE_LEN)
        for (cut in 1..maxCut) {
            if (cut == originalFirstLen) continue
            if (dict.isRealSyllableNormalized(raw.substring(0, cut))) {
                return true
            }
        }
        return false
    }

    /**
     * Computes max contiguous window size (1..3) starting at [start] that:
     * - Does not cross punctuation boundaries
     * - Contains only eligible Vietnamese letter cores
     * - Does not absorb a capitalized non-first token (protects "khu A", "Chương I", proper nouns)
     *   unless the tokens are ALL CAPS (e.g., "CÓM ỘT").
     */
    private fun computeMaxContiguousWindow(
        tokens: List<WordToken>,
        start: Int,
        protectedWords: Set<String>
    ): Int {
        val firstAllCaps = isAllCaps(tokens[start].core)
        var size = 1
        while (size < 3 && start + size < tokens.size) {
            val prev = tokens[start + size - 1]
            val next = tokens[start + size]

            // Never cross punctuation boundaries
            if (prev.trailingPunct.isNotEmpty() || next.leadingPunct.isNotEmpty()) break

            // Never absorb a capitalized non-first token unless part of an ALL CAPS run
            val nextStartsUpper = next.core.firstOrNull()?.isUpperCase() == true
            if (nextStartsUpper && !(firstAllCaps && isAllCaps(next.core))) break

            if (!isEligibleVietnameseCore(next.core, next.lowerCore, protectedWords)) break
            size++
        }
        return size
    }

    private fun buildReplacementTokens(
        originalSlice: List<WordToken>,
        segments: List<String>
    ): List<WordToken> {
        val firstOrig = originalSlice.first()
        val lastOrig = originalSlice.last()
        val allCaps = originalSlice.all { isAllCaps(it.core) }
        val firstCapitalized = firstOrig.core.firstOrNull()?.isUpperCase() == true

        return segments.mapIndexed { idx, seg ->
            val cased = when {
                allCaps -> seg.uppercase()
                idx == 0 && firstCapitalized -> seg.replaceFirstChar { it.uppercaseChar() }
                else -> seg
            }
            WordToken(
                leadingPunct = if (idx == 0) firstOrig.leadingPunct else "",
                core = cased,
                trailingPunct = if (idx == segments.lastIndex) lastOrig.trailingPunct else ""
            )
        }
    }

    private fun isAllCaps(core: String): Boolean {
        var hasLetter = false
        for (ch in core) {
            if (ch.isLetter()) {
                hasLetter = true
                if (!ch.isUpperCase()) return false
            }
        }
        return hasLetter
    }

    private fun isEligibleVietnameseCore(
        core: String,
        lowerCore: String,
        protectedWords: Set<String>
    ): Boolean {
        if (core.isEmpty()) return false
        if (!isPureVietnameseLetters(core)) return false
        if (lowerCore in protectedWords && dict.isRealSyllableNormalized(lowerCore)) return false
        return true
    }

    /**
     * Zero-regex check: verifies every char is a Vietnamese alphabet letter
     * (excludes digits and non-Vietnamese Latin letters f, j, w, z).
     */
    private fun isPureVietnameseLetters(word: String): Boolean {
        if (word.isEmpty()) return false
        for (i in word.indices) {
            val ch = word[i]
            if (!ch.isLetter()) return false
            when (ch.lowercaseChar()) {
                'f', 'j', 'w', 'z' -> return false
            }
            // Reject non-Latin scripts (CJK, Cyrillic, etc.)
            if (ch.code > 0x1EF9) return false
        }
        return true
    }

    private fun buildProtectedWordSet(properNouns: Collection<String>): Set<String> {
        if (properNouns.isEmpty()) return emptySet()
        val set = HashSet<String>(properNouns.size * 2)
        for (noun in properNouns) {
            val parts = noun.trim().split(' ')
            for (part in parts) {
                val p = part.trim()
                if (p.length >= 2) {
                    set.add(p.lowercase())
                }
            }
        }
        return set
    }

    private fun tokenize(text: String): List<WordToken> {
        val result = ArrayList<WordToken>(text.length / 5 + 4)
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i] == ' ') i++
            if (i >= n) break
            var j = i
            while (j < n && text[j] != ' ') j++

            val raw = text.substring(i, j)
            var start = 0
            var end = raw.length
            while (start < end && !raw[start].isLetterOrDigit()) start++
            while (end > start && !raw[end - 1].isLetterOrDigit()) end--

            result.add(
                WordToken(
                    leadingPunct = raw.substring(0, start),
                    core = raw.substring(start, end),
                    trailingPunct = raw.substring(end)
                )
            )
            i = j
        }
        return result
    }
}
