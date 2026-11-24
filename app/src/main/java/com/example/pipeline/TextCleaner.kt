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
 * Implements the exact 3-stage offline pipeline backed by [OfflineVietnameseDict]:
 * - Step A: Collapse all repeated spaces, &nbsp;, tabs, zero-width spaces, and normalize punctuation spacing.
 * - Step B: Sliding-space & broken-syllable repair via Dynamic Programming re-segmentation
 *           (e.g., "chú ýt ới" -> "chú ý tới", "t ôi" -> "tôi", "n ga y ma i" -> "ngay mai").
 * - Step C: Fake-word split repair using HIGH_SUSPICION_SINGLES + vi_syllables + vi_words exception guard
 *           (e.g., "Củ a tâm lí" -> "Của tâm lí", while protecting "ông ấy", "trời ơi", "chú ý").
 */
class TextCleaner(
    private val dict: OfflineVietnameseDict = OfflineVietnameseDict
) {

    private data class WordToken(
        val leadingPunct: String,
        val core: String,
        val trailingPunct: String
    ) {
        fun render(): String = "$leadingPunct$core$trailingPunct"
    }

    private data class DpResult(
        val segments: List<String>,
        val score: Int
    )

    private data class WindowCandidate(
        val start: Int,
        val endInclusive: Int,
        val segments: List<String>,
        val score: Int
    )

    private val specialWhitespaceRegex = Regex("""[\u00A0\u200B\u200C\u200D\uFEFF\u2000-\u200A\u202F\u205F\u3000\t\r\n]+""")
    private val multiSpaceRegex = Regex(""" {2,}""")
    private val spaceBeforePunctRegex = Regex("""\s+([,.;:!?)\]»”])""")
    private val spaceAfterOpenBracketRegex = Regex("""([(\[«“])\s+""")
    private val missingSpaceAfterPunctRegex = Regex("""([,;:!?])([\p{L}])""")
    private val missingSpaceAfterPeriodRegex = Regex("""(?<!\.)\.(\p{L}{2,})""")

    // Pure Vietnamese letter sequence (excludes digits and foreign letters f, j, w, z)
    private val vietnameseWordRegex = Regex(
        """^[a-eg-ik-vxyA-EG-IK-VXYàáảãạăắằẳẵặâấầẩẫậđèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬĐÈÉẺẼẸÊẾỀỂỄỆÌÍỈĨỊÒÓỎÕỌÔỐỒỔỖỘƠỚỜỞỠỢÙÚỦŨỤƯỨỪỬỮỰỲÝỶỸỴ]+$"""
    )

    // Function/medical words where a following lowercase "y" is legitimately the word "y" (he / medicine)
    private val legitimateBeforeStandaloneY = setOf(
        "đông", "tây", "quân", "thú", "lương", "danh", "thần",
        "của", "cho", "với", "theo", "như", "mà", "và", "là", "bởi", "vì", "khi", "nếu"
    )

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
     * Full 3-step offline cleaner (Step A -> Step B -> Step C).
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

        val protectedSingleWords = buildProtectedWordSet(protectedProperNouns)
        var tokens = tokenize(collapsed)
        val reasons = linkedSetOf<String>()

        // Step B: Sliding-space & broken-syllable DP re-segmentation
        val (afterStepB, slidingFixes) = fixSlidingSpacesWithDp(tokens, protectedSingleWords)
        tokens = afterStepB
        if (slidingFixes > 0) {
            reasons.add("Sửa lỗi trượt khoảng trắng")
        }

        // Step C: Fake-word split merging (up to 2 passes for nested splits)
        var totalFakeSplitFixes = 0
        repeat(2) {
            val (afterStepC, splitFixes) = fixFakeWordSplits(tokens, protectedSingleWords)
            tokens = afterStepC
            totalFakeSplitFixes += splitFixes
            if (splitFixes == 0) return@repeat
        }
        if (totalFakeSplitFixes > 0) {
            reasons.add("Ghép âm tiết bị tách")
        }

        val finalStr = tokens.joinToString(" ") { it.render() }
        return TextCleanResult(
            cleanedText = finalStr,
            spaceCollapseCount = spaceCount,
            slidingSpaceFixes = slidingFixes,
            fakeSplitFixes = totalFakeSplitFixes,
            reasons = reasons.toList()
        )
    }

    /**
     * Step B: Repair sliding spaces / broken syllables (e.g., "chú ýt ới" -> "chú ý tới",
     * "t ôi" -> "tôi", "n gày" -> "ngày", "n ga y ma i" -> "ngay mai", "ýtới" -> "ý tới").
     */
    private fun fixSlidingSpacesWithDp(
        inputTokens: List<WordToken>,
        protectedWords: Set<String>
    ): Pair<List<WordToken>, Int> {
        val result = inputTokens.toMutableList()
        var fixCount = 0
        var i = 0

        while (i < result.size) {
            val current = result[i]
            if (!isEligibleVietnameseWord(current.core, protectedWords)) {
                i++
                continue
            }

            if (dict.isValidSyllable(current.core)) {
                i++
                continue
            }

            // Found an invalid syllable w[i] (e.g., "ýt", "n", "t", "ng", "ýtới", "đượ")
            val bestCandidate = findBestWindowResegmentation(result, i, protectedWords)
            if (bestCandidate != null) {
                val start = bestCandidate.start
                val end = bestCandidate.endInclusive
                val originalSlice = result.subList(start, end + 1).toList()
                val replacementTokens = buildReplacementTokens(
                    originalSlice = originalSlice,
                    segments = bestCandidate.segments
                )

                val changed = originalSlice.size != replacementTokens.size ||
                    originalSlice.indices.any { idx ->
                        originalSlice[idx].core != replacementTokens[idx].core
                    }

                if (changed) {
                    // Replace slice [start..end]
                    for (k in end downTo start) {
                        result.removeAt(k)
                    }
                    result.addAll(start, replacementTokens)
                    fixCount++
                    i = start + replacementTokens.size
                    continue
                }
            }

            i++
        }

        return result to fixCount
    }

    private fun findBestWindowResegmentation(
        tokens: List<WordToken>,
        invalidIdx: Int,
        protectedWords: Set<String>
    ): WindowCandidate? {
        val candidateRanges = linkedSetOf<IntRange>()

        // 1. Single token DP split (e.g., "ýtới" -> "ý tới", "tâmlí" -> "tâm lí")
        candidateRanges.add(invalidIdx..invalidIdx)

        // 2. Forward windows (size 2..5), stopping at punctuation, non-Vietnamese tokens,
        //    or healthy full syllables beyond the immediate right neighbor
        var maxForwardEnd = invalidIdx
        while (maxForwardEnd + 1 < tokens.size && maxForwardEnd - invalidIdx < 4) {
            val left = tokens[maxForwardEnd]
            val right = tokens[maxForwardEnd + 1]
            if (left.trailingPunct.isNotEmpty() || right.leadingPunct.isNotEmpty()) break
            if (!isEligibleVietnameseWord(right.core, protectedWords)) break
            if (maxForwardEnd >= invalidIdx + 1 && !isFragmentLike(right.core)) break
            maxForwardEnd++
            candidateRanges.add(invalidIdx..maxForwardEnd)
        }

        // 3. Backward + current (and optional +1/+2 forward) when invalid fragment sits after a stem
        //    e.g., "mộ t" -> "một", "nhữ ng" -> "những", "củ atâm" -> "của tâm", "ngà ym ai" -> "ngày mai"
        if (invalidIdx > 0) {
            val prev = tokens[invalidIdx - 1]
            val curr = tokens[invalidIdx]
            if (prev.trailingPunct.isEmpty() &&
                curr.leadingPunct.isEmpty() &&
                isEligibleVietnameseWord(prev.core, protectedWords)
            ) {
                candidateRanges.add((invalidIdx - 1)..invalidIdx)
                if (invalidIdx + 1 <= maxForwardEnd) {
                    candidateRanges.add((invalidIdx - 1)..(invalidIdx + 1))
                }
                if (invalidIdx + 2 <= maxForwardEnd) {
                    candidateRanges.add((invalidIdx - 1)..(invalidIdx + 2))
                }
            }
        }

        var best: WindowCandidate? = null
        var bestGain = Int.MIN_VALUE

        for (range in candidateRanges) {
            val start = range.first
            val end = range.last
            val combined = buildString {
                for (k in start..end) {
                    append(tokens[k].core.lowercase())
                }
            }
            if (combined.length < 2 || combined.length > 28) continue

            val leftContext = if (start > 0 && tokens[start - 1].trailingPunct.isEmpty() && tokens[start].leadingPunct.isEmpty()) {
                tokens[start - 1].core.lowercase()
            } else null

            val rightContext = if (end + 1 < tokens.size && tokens[end].trailingPunct.isEmpty() && tokens[end + 1].leadingPunct.isEmpty()) {
                tokens[end + 1].core.lowercase()
            } else null

            val dpResult = segmentByDp(combined, leftContext, rightContext) ?: continue

            // Compare DP score against baseline score of the original tokens in this range
            val baselineScore = scoreOriginalSlice(tokens, start, end, leftContext, rightContext)
            val netGain = dpResult.score - baselineScore

            // Prefer higher net gain; tie-break by fewer segments (more natural full syllables)
            if (netGain > bestGain ||
                (netGain == bestGain && best != null && dpResult.segments.size < best.segments.size)
            ) {
                bestGain = netGain
                best = WindowCandidate(
                    start = start,
                    endInclusive = end,
                    segments = dpResult.segments,
                    score = dpResult.score
                )
            }
        }

        return if (best != null && bestGain > 0) best else null
    }

    private fun isFragmentLike(core: String): Boolean {
        val lower = core.lowercase()
        if (!dict.isValidSyllable(lower)) return true
        if (dict.isHighSuspicionSingle(lower)) return true
        return lower.length <= 2
    }

    /**
     * Unified per-syllable score used by both DP segmentation and baseline scoring.
     * Because length bonus (10 * len) is invariant across any partition of the same string,
     * the fixed -55 per-segment cost guarantees 1 valid whole syllable ALWAYS strictly beats
     * over-splitting into 2 shorter syllables (e.g. "tiết" always beats "ti ết").
     */
    private fun scoreSyllable(seg: String, isValid: Boolean): Int {
        if (!isValid) return -320
        val lenBonus = seg.length * 10
        val suspicious = dict.isHighSuspicionSingle(seg)
        return when {
            suspicious && seg.length == 1 -> lenBonus - 175
            suspicious -> lenBonus - 120
            else -> lenBonus - 55
        }
    }

    /**
     * Exact Dynamic Programming syllable + compound-word re-segmentation.
     * Finds the highest-scoring split of [combined] into valid Vietnamese syllables from vi_syllables,
     * heavily rewarding matches with vi_words (both internal bigrams/trigrams and left/right context).
     */
    private fun segmentByDp(
        combined: String,
        leftContext: String?,
        rightContext: String?
    ): DpResult? {
        val n = combined.length
        if (n == 0) return null

        // State per position: map of lastSegment -> best DpResult ending at `pos` with `lastSegment`
        val dp = Array<HashMap<String, DpResult>>(n + 1) { HashMap() }
        dp[0][""] = DpResult(segments = emptyList(), score = 0)

        val maxSyllableLen = 8 // Longest Vietnamese syllable is 7 chars (e.g., "nghiêng")

        for (i in 0 until n) {
            val statesAtI = dp[i]
            if (statesAtI.isEmpty()) continue

            val maxEnd = minOf(n, i + maxSyllableLen)
            for (j in (i + 1)..maxEnd) {
                val seg = combined.substring(i, j)
                if (!dict.isValidSyllable(seg)) continue

                val segBaseScore = scoreSyllable(seg, isValid = true)

                for ((prevSeg, prevState) in statesAtI) {
                    var score = prevState.score + segBaseScore

                    if (prevState.segments.isEmpty()) {
                        // First segment: check left boundary context in vi_words (e.g. "chú" + "ý" = "chú ý")
                        if (leftContext != null && dict.isKnownBigram(leftContext, seg)) {
                            score += 180
                        }
                    } else {
                        // Internal bigram in vi_words (e.g. "ngày mai", "tâm lí", "ý tới")
                        if (dict.isKnownBigram(prevSeg, seg)) {
                            score += 160
                        }
                        // Internal trigram or left-context trigram (e.g. "chú ý tới")
                        if (prevState.segments.size >= 2) {
                            val prevPrev = prevState.segments[prevState.segments.size - 2]
                            if (dict.isKnownTrigram(prevPrev, prevSeg, seg)) {
                                score += 95
                            }
                        } else if (leftContext != null && dict.isKnownTrigram(leftContext, prevSeg, seg)) {
                            score += 110
                        }
                    }

                    // Bonus for single clean syllable merge (e.g., "t" + "ôi" -> "tôi")
                    if (i == 0 && j == n) {
                        score += 40
                    }

                    // Final segment: check right boundary context in vi_words
                    if (j == n && rightContext != null) {
                        if (dict.isKnownBigram(seg, rightContext)) {
                            score += 180
                        }
                        if (prevSeg.isNotEmpty() && dict.isKnownTrigram(prevSeg, seg, rightContext)) {
                            score += 95
                        }
                    }

                    val existing = dp[j][seg]
                    if (existing == null || score > existing.score) {
                        dp[j][seg] = DpResult(
                            segments = prevState.segments + seg,
                            score = score
                        )
                    }
                }
            }
        }

        return dp[n].values.maxByOrNull { it.score }
    }

    private fun scoreOriginalSlice(
        tokens: List<WordToken>,
        start: Int,
        end: Int,
        leftContext: String?,
        rightContext: String?
    ): Int {
        var score = 0
        for (k in start..end) {
            val w = tokens[k].core.lowercase()
            val valid = dict.isValidSyllable(w)
            score += scoreSyllable(w, isValid = valid)

            if (k == start && leftContext != null && dict.isKnownBigram(leftContext, w)) {
                score += 180
            }
            if (k > start) {
                val prev = tokens[k - 1].core.lowercase()
                if (dict.isKnownBigram(prev, w)) score += 160
            }
            if (k == end && rightContext != null && dict.isKnownBigram(w, rightContext)) {
                score += 180
            }
        }
        return score
    }

    /**
     * Step C: Repair fake-word syllable splits (e.g., "Củ a tâm lí" -> "Của tâm lí",
     * "ngà y mai" -> "ngày mai", "tô i đi" -> "tôi đi", "khô ng có" -> "không có").
     */
    private fun fixFakeWordSplits(
        inputTokens: List<WordToken>,
        protectedWords: Set<String>
    ): Pair<List<WordToken>, Int> {
        val output = mutableListOf<WordToken>()
        var fixCount = 0
        var i = 0

        while (i < inputTokens.size) {
            if (i + 1 < inputTokens.size) {
                val left = inputTokens[i]
                val right = inputTokens[i + 1]

                if (shouldMergeFakeSplit(inputTokens, i, left, right, protectedWords)) {
                    val mergedCore = mergePreservingCasing(left.core, right.core)
                    output.add(
                        WordToken(
                            leadingPunct = left.leadingPunct,
                            core = mergedCore,
                            trailingPunct = right.trailingPunct
                        )
                    )
                    fixCount++
                    i += 2
                    continue
                }
            }

            output.add(inputTokens[i])
            i++
        }

        return output to fixCount
    }

    private fun shouldMergeFakeSplit(
        tokens: List<WordToken>,
        idx: Int,
        left: WordToken,
        right: WordToken,
        protectedWords: Set<String>
    ): Boolean {
        // Never merge across punctuation boundaries
        if (left.trailingPunct.isNotEmpty() || right.leadingPunct.isNotEmpty()) return false

        // Never merge uppercase second token (protects "khu A", "Chương I", "loại B", proper nouns)
        if (right.core.firstOrNull()?.isUpperCase() == true) return false

        if (!isEligibleVietnameseWord(left.core, protectedWords)) return false
        if (!isEligibleVietnameseWord(right.core, protectedWords)) return false

        val leftLower = left.core.lowercase()
        val rightLower = right.core.lowercase()

        // Condition 1: w[i+1] MUST belong to HIGH_SUSPICION_SINGLES
        if (!dict.isHighSuspicionSingle(rightLower)) return false

        // Condition 2: merged = w[i] + w[i+1] MUST be a valid syllable in vi_syllables
        val mergedLower = leftLower + rightLower
        if (!dict.isValidSyllable(mergedLower)) return false

        // Exception 1: If "w[i] w[i+1]" is a known Vietnamese word/phrase in vi_words -> DO NOT merge!
        // (Protects "ông ấy", "bà ấy", "cô ấy", "trời ơi", "em à", "chú ý", "vừa ý", etc.)
        if (dict.isKnownBigram(leftLower, rightLower)) return false

        val prevLower = if (idx > 0 && tokens[idx - 1].trailingPunct.isEmpty() && left.leadingPunct.isEmpty()) {
            tokens[idx - 1].core.lowercase()
        } else null
        val nextLower = if (idx + 2 < tokens.size && right.trailingPunct.isEmpty() && tokens[idx + 2].leadingPunct.isEmpty()) {
            tokens[idx + 2].core.lowercase()
        } else null

        // Always merge bare consonant fragments (e.g., "khô ng" -> "không", "đan g" -> "đang", "như ng" -> "nhưng")
        if (dict.isStrictConsonantFragment(rightLower)) {
            return true
        }

        // Guard standalone "y" (he / medicine) when preceded by known grammatical/medical prefixes
        if (rightLower == "y" && leftLower in legitimateBeforeStandaloneY) {
            return false
        }

        // Exception 2: If w[i+1] is a multi-char fragment (like "ơi", "ấy", "ưa") AND forms a known
        // compound with w[i+2] (e.g., "ưa thích"), protect it unless merging forms an even stronger compound
        if (rightLower.length >= 2 && nextLower != null && dict.isKnownBigram(rightLower, nextLower)) {
            val mergedFormsBigram = (prevLower != null && dict.isKnownBigram(prevLower, mergedLower)) ||
                dict.isKnownBigram(mergedLower, nextLower)
            if (!mergedFormsBigram) return false
        }

        // Exception 3: If w[i-1] + w[i] is already a known compound AND w[i+1] is multi-char (e.g. "ấy", "ơi")
        // AND merged doesn't match context, avoid false merge
        if (rightLower.length >= 2 && prevLower != null && dict.isKnownBigram(prevLower, leftLower)) {
            val mergedMatchesContext = dict.isKnownBigram(prevLower, mergedLower) ||
                (nextLower != null && dict.isKnownBigram(mergedLower, nextLower))
            if (!mergedMatchesContext) return false
        }

        return true
    }

    private fun buildReplacementTokens(
        originalSlice: List<WordToken>,
        segments: List<String>
    ): List<WordToken> {
        val firstOrig = originalSlice.first()
        val lastOrig = originalSlice.last()
        val allCaps = originalSlice.all { tok ->
            tok.core.any { it.isLetter() } && tok.core == tok.core.uppercase()
        }
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

    private fun mergePreservingCasing(leftCore: String, rightCore: String): String {
        val leftAllCaps = leftCore.length >= 2 && leftCore == leftCore.uppercase()
        val rightLower = rightCore.lowercase()
        return if (leftAllCaps) {
            (leftCore + rightLower).uppercase()
        } else {
            // Preserve leading uppercase of w[i] (e.g. "Củ" + "a" -> "Của")
            leftCore + rightLower
        }
    }

    private fun isEligibleVietnameseWord(core: String, protectedWords: Set<String>): Boolean {
        if (core.isEmpty()) return false
        if (core.lowercase() in protectedWords) return false
        return vietnameseWordRegex.matches(core)
    }

    private fun buildProtectedWordSet(properNouns: Collection<String>): Set<String> {
        if (properNouns.isEmpty()) return emptySet()
        val set = HashSet<String>(properNouns.size * 2)
        for (noun in properNouns) {
            val parts = noun.trim().split(Regex("""\s+"""))
            for (part in parts) {
                // Only protect full valid proper-noun syllables (>= 2 chars) so broken splits aren't blocked
                if (part.length >= 2) {
                    set.add(part.lowercase())
                }
            }
        }
        return set
    }

    private fun tokenize(text: String): List<WordToken> {
        if (text.isBlank()) return emptyList()
        return text.split(' ').filter { it.isNotEmpty() }.map { raw ->
            var start = 0
            var end = raw.length
            while (start < end && !raw[start].isLetterOrDigit()) {
                start++
            }
            while (end > start && !raw[end - 1].isLetterOrDigit()) {
                end--
            }
            WordToken(
                leadingPunct = raw.substring(0, start),
                core = raw.substring(start, end),
                trailingPunct = raw.substring(end)
            )
        }
    }
}
