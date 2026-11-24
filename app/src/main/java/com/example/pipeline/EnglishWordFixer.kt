package com.example.pipeline

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs

data class EnglishFixResult(
    val cleanedText: String,
    val fixCount: Int,
    val fixes: List<Pair<String, String>> = emptyList()
)

/**
 * 100% Offline Standard English Dictionary backed by O(1) HashSet + first-char/length buckets
 * loaded from assets/en_words.txt.
 */
object EnglishDictionary {

    private const val ASSET_EN_WORDS = "en_words.txt"

    @Volatile
    private var loaded: Boolean = false

    private val words: HashSet<String> = HashSet(131_072)

    // Key = (firstChar.code shl 8) or wordLength -> list of lowercase English words
    private val buckets: HashMap<Int, ArrayList<String>> = HashMap(512)

    val isLoaded: Boolean
        get() = loaded

    val wordCount: Int
        get() = words.size

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            context.applicationContext.assets.open(ASSET_EN_WORDS).use { stream ->
                loadStreamInternal(stream)
            }
            loaded = true
        }
    }

    fun loadFromFile(file: File) {
        synchronized(this) {
            FileInputStream(file).use { stream ->
                loadStreamInternal(stream)
            }
            loaded = true
        }
    }

    fun loadFromStream(stream: InputStream) {
        synchronized(this) {
            loadStreamInternal(stream)
            loaded = true
        }
    }

    private fun loadStreamInternal(stream: InputStream) {
        words.clear()
        buckets.clear()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 32 * 1024).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val w = line.trim().lowercase()
                if (w.isNotEmpty()) {
                    words.add(w)
                    val key = bucketKey(w[0], w.length)
                    buckets.getOrPut(key) { ArrayList() }.add(w)
                }
                line = reader.readLine()
            }
        }
    }

    fun isKnownWord(word: String): Boolean {
        if (word.isEmpty()) return false
        return words.contains(word.lowercase())
    }

    /**
     * Fuzzy match via Levenshtein Distance <= 2 with OCR ligature awareness
     * (e.g., "idenfinity" -> "identity", where PDF OCR misread "ti" as "fi"+"ni").
     * Restricts search strictly to words sharing the same first letter and length difference <= 2.
     */
    fun findBestFuzzyMatch(rawMerged: String): String? {
        val merged = rawMerged.lowercase()
        if (!loaded || merged.length < 5) return null

        val firstChar = merged[0]
        // Generate raw merged string + targeted PDF/OCR ligature variants
        val variants = buildOcrVariants(merged)

        var bestWord: String? = null
        var bestDist = Int.MAX_VALUE
        var bestAnchor = -1
        var bestLenDiff = Int.MAX_VALUE
        var tieCount = 0

        val minLen = (merged.length - 2).coerceAtLeast(4)
        val maxLen = merged.length + 2

        for (targetLen in minLen..maxLen) {
            val candidates = buckets[bucketKey(firstChar, targetLen)] ?: continue
            for (cand in candidates) {
                var candBestDist = Int.MAX_VALUE
                for (variant in variants) {
                    if (abs(variant.length - cand.length) > 2) continue
                    val d = levenshteinBounded(variant, cand, maxDist = 2)
                    if (d < candBestDist) {
                        candBestDist = d
                        if (d == 0) break
                    }
                }
                if (candBestDist > 2) continue

                val prefix = commonPrefixLen(merged, cand)
                val suffix = commonSuffixLen(merged, cand)
                val anchorScore = prefix * 2 + suffix
                val lenDiff = abs(cand.length - merged.length)

                val isBetter = when {
                    candBestDist < bestDist -> true
                    candBestDist > bestDist -> false
                    anchorScore > bestAnchor -> true
                    anchorScore < bestAnchor -> false
                    lenDiff < bestLenDiff -> true
                    else -> false
                }

                if (isBetter) {
                    bestWord = cand
                    bestDist = candBestDist
                    bestAnchor = anchorScore
                    bestLenDiff = lenDiff
                    tieCount = 1
                } else if (candBestDist == bestDist && anchorScore == bestAnchor && lenDiff == bestLenDiff) {
                    tieCount++
                }
            }
        }

        if (bestWord == null || bestDist > 2) return null
        // Require a clear, unambiguous winner so we never corrupt unfamiliar terms
        if (tieCount > 1) return null
        // For distance 2 on shorter words, require strong prefix/suffix anchor
        if (bestDist == 2 && merged.length < 7 && bestAnchor < 4) return null
        return bestWord
    }

    private fun buildOcrVariants(merged: String): List<String> {
        val list = ArrayList<String>(4)
        list.add(merged)
        // Classic serif PDF/OCR ligature confusions: "fi" <-> "ti", "rn" -> "m", "cl" -> "d"
        if (merged.contains("fi")) {
            list.add(merged.replace("fi", "ti"))
        }
        if (merged.contains("rn")) {
            list.add(merged.replace("rn", "m"))
        }
        if (merged.contains("cl")) {
            list.add(merged.replace("cl", "d"))
        }
        return list
    }

    private fun bucketKey(firstChar: Char, length: Int): Int {
        return (firstChar.code shl 8) or (length and 0xFF)
    }

    private fun commonPrefixLen(a: String, b: String): Int {
        val lim = minOf(a.length, b.length)
        var i = 0
        while (i < lim && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffixLen(a: String, b: String): Int {
        val lim = minOf(a.length, b.length)
        var i = 0
        while (i < lim && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /**
     * Fast bounded Levenshtein distance returning [maxDist + 1] early whenever distance exceeds [maxDist].
     */
    internal fun levenshteinBounded(a: String, b: String, maxDist: Int): Int {
        val m = a.length
        val n = b.length
        if (abs(m - n) > maxDist) return maxDist + 1
        if (a == b) return 0

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..n) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val del = prev[j] + 1
                val ins = curr[j - 1] + 1
                val sub = prev[j - 1] + cost
                val v = minOf(del, ins, sub)
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}

/**
 * Repairs split/broken English words embedded inside Vietnamese or bilingual ebooks
 * (e.g., "i den  fi ni ty" -> "identity", "com pu ter" -> "computer") 100% offline.
 *
 * Strictly protects valid Vietnamese phrases from [OfflineVietnameseDict].
 */
class EnglishWordFixer(
    private val enDict: EnglishDictionary = EnglishDictionary,
    private val viDict: OfflineVietnameseDict = OfflineVietnameseDict
) {

    private data class Token(
        val leadingPunct: String,
        val core: String,
        val trailingPunct: String
    ) {
        fun render(): String = "$leadingPunct$core$trailingPunct"
    }

    private val asciiLettersRegex = Regex("""^[A-Za-z]+$""")
    private val multiSpaceRegex = Regex("""[\u00A0\t\r\n ]+""")

    // Very common standalone short English words that must not be accidentally fused when standing together
    private val commonStandaloneShortEnglish = setOf(
        "a", "an", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it",
        "me", "my", "no", "of", "on", "or", "so", "to", "up", "us", "we",
        "am", "and", "are", "but", "can", "for", "had", "has", "her", "him", "his",
        "how", "its", "may", "new", "not", "now", "old", "one", "our", "out", "own",
        "put", "run", "say", "see", "she", "the", "too", "two", "use", "was", "way",
        "who", "why", "yes", "yet", "you"
    )

    fun fixText(
        rawText: String,
        protectedProperNouns: Collection<String> = emptyList()
    ): EnglishFixResult {
        if (rawText.isBlank() || !enDict.isLoaded) {
            return EnglishFixResult(cleanedText = rawText, fixCount = 0)
        }

        // Collapse repeated spaces first (e.g. "i den  fi ni ty" -> "i den fi ni ty")
        val collapsed = multiSpaceRegex.replace(rawText, " ").trim()
        val tokens = tokenize(collapsed)
        if (tokens.size < 2) {
            return EnglishFixResult(cleanedText = collapsed, fixCount = 0)
        }

        val protectedSingleWords = buildProtectedSet(protectedProperNouns)
        val output = ArrayList<Token>(tokens.size)
        val fixes = ArrayList<Pair<String, String>>()

        var i = 0
        while (i < tokens.size) {
            val match = tryFixSpanStartingAt(tokens, i, protectedSingleWords)
            if (match != null) {
                val (spanLen, fixedWord) = match
                val firstTok = tokens[i]
                val lastTok = tokens[i + spanLen - 1]
                val originalJoined = tokens.subList(i, i + spanLen).joinToString(" ") { it.core }
                val cased = applyCasing(tokens.subList(i, i + spanLen), fixedWord)

                output.add(
                    Token(
                        leadingPunct = firstTok.leadingPunct,
                        core = cased,
                        trailingPunct = lastTok.trailingPunct
                    )
                )
                fixes.add(originalJoined to cased)
                i += spanLen
            } else {
                output.add(tokens[i])
                i++
            }
        }

        return EnglishFixResult(
            cleanedText = output.joinToString(" ") { it.render() },
            fixCount = fixes.size,
            fixes = fixes
        )
    }

    private fun tryFixSpanStartingAt(
        tokens: List<Token>,
        start: Int,
        protectedWords: Set<String>
    ): Pair<Int, String>? {
        if (!isAsciiCandidate(tokens[start].core, protectedWords)) return null

        // Determine maximum contiguous unaccented ASCII run (up to 6 fragments, stopping at punctuation)
        var maxSpan = 1
        while (start + maxSpan < tokens.size && maxSpan < 6) {
            val prev = tokens[start + maxSpan - 1]
            val next = tokens[start + maxSpan]
            if (prev.trailingPunct.isNotEmpty() || next.leadingPunct.isNotEmpty()) break
            if (!isAsciiCandidate(next.core, protectedWords)) break
            // Never cross an interior uppercase start (e.g. "in Vietnam") unless all tokens are ALL CAPS
            if (next.core[0].isUpperCase() && !isAllCaps(prev.core)) break
            maxSpan++
        }

        if (maxSpan < 2) return null

        // Phase 1: Exact match in en_words.txt (prefer longest span from maxSpan down to 2)
        for (span in maxSpan downTo 2) {
            val slice = tokens.subList(start, start + span)
            if (!isEligibleBrokenEnglishSpan(slice)) continue

            val merged = slice.joinToString("") { it.core.lowercase() }
            if (merged.length < 4) continue
            if (viDict.isLoaded && viDict.isValidSyllable(merged)) continue

            if (enDict.isKnownWord(merged)) {
                return span to merged
            }
        }

        // Phase 2: Fuzzy match (Levenshtein <= 2 with OCR ligature awareness)
        for (span in maxSpan downTo 2) {
            val slice = tokens.subList(start, start + span)
            if (!isEligibleBrokenEnglishSpan(slice, strictForFuzzy = true)) continue

            val merged = slice.joinToString("") { it.core.lowercase() }
            if (merged.length < 5) continue
            if (viDict.isLoaded && viDict.isValidSyllable(merged)) continue

            val fuzzy = enDict.findBestFuzzyMatch(merged)
            if (fuzzy != null) {
                return span to fuzzy
            }
        }

        return null
    }

    /**
     * Checks whether [slice] looks like broken English word fragments rather than valid Vietnamese
     * or normal adjacent English words.
     */
    private fun isEligibleBrokenEnglishSpan(
        slice: List<Token>,
        strictForFuzzy: Boolean = false
    ): Boolean {
        val lowers = slice.map { it.core.lowercase() }

        // 1. Check how many fragments are NOT valid Vietnamese syllables or are 1-3 char pieces
        val nonViCount = if (viDict.isLoaded) {
            lowers.count { !viDict.isValidSyllable(it) }
        } else {
            lowers.count { it.length <= 3 }
        }

        // 2. Strict Vietnamese phrase protection:
        //    - When all fragments are valid Vietnamese syllables (nonViCount == 0) or during fuzzy matching,
        //      block if ANY adjacent pair/triple is a known Vietnamese phrase.
        //    - When non-Vietnamese fragments exist (e.g. "psy cho lo gy" where "psy" & "gy" are impossible in Vi),
        //      still protect Vietnamese bigrams at the span boundary (leading/trailing) so valid Vietnamese words
        //      adjacent to an English fragment are never swallowed, while allowing coincidental interior syllables
        //      like "cho lo" inside "psychology".
        if (viDict.isLoaded) {
            for (k in 0 until lowers.lastIndex) {
                if (viDict.isKnownBigram(lowers[k], lowers[k + 1])) {
                    val isBoundaryBigram = (k == 0 || k == lowers.lastIndex - 1)
                    if (nonViCount == 0 || strictForFuzzy || isBoundaryBigram) {
                        return false
                    }
                }
            }
            if (lowers.size >= 3) {
                for (k in 0 until lowers.size - 2) {
                    if (viDict.isKnownTrigram(lowers[k], lowers[k + 1], lowers[k + 2])) {
                        return false
                    }
                }
            }
        }

        // 3. Do not merge if EVERY fragment is already a common standalone English word (e.g. "to be", "in the", "for me")
        val allCommonEnglish = lowers.all { it in commonStandaloneShortEnglish || (it.length >= 3 && enDict.isKnownWord(it)) }
        if (allCommonEnglish) return false

        val shortFragCount = lowers.count { it.length <= 3 }
        val nonEnWordCount = lowers.count { it !in commonStandaloneShortEnglish && !enDict.isKnownWord(it) }

        // Require at least one fragment that is not a normal standalone English word
        if (nonEnWordCount == 0) return false

        // For Vietnamese safety:
        // - If at least one fragment is NOT a valid Vietnamese syllable (e.g. "fi", "pu", "ter", "psy", "gy"),
        //   the run is strongly non-Vietnamese -> eligible!
        // - Or if every fragment is short (1..3 chars) and at least one is a broken 1-char piece (like "i")
        //   or >= 3 fragments form a long English word (e.g., "i den ti ty")
        val hasSingleCharPiece = lowers.any { it.length == 1 }
        if (strictForFuzzy) {
            if (nonViCount == 0 && !(hasSingleCharPiece && slice.size >= 3)) return false
            if (shortFragCount < (slice.size + 1) / 2) return false
        } else {
            if (nonViCount == 0) {
                // Every piece happens to exist as an unaccented Vietnamese syllable (e.g., "i den ti ty" or "com pa ny")
                // Only allow if all fragments are short (<= 3) AND (span >= 3 OR contains a 1-char fragment)
                val allShort = lowers.all { it.length <= 3 }
                if (!(allShort && (slice.size >= 3 || hasSingleCharPiece))) return false
            }
        }

        return true
    }

    private fun isAsciiCandidate(core: String, protectedWords: Set<String>): Boolean {
        if (core.isEmpty() || core.length > 10) return false
        if (core.lowercase() in protectedWords) return false
        return asciiLettersRegex.matches(core)
    }

    private fun isAllCaps(core: String): Boolean {
        return core.length >= 2 && core == core.uppercase()
    }

    private fun applyCasing(slice: List<Token>, fixedLower: String): String {
        val allCaps = slice.all { tok ->
            tok.core.any { it.isLetter() } && tok.core == tok.core.uppercase()
        }
        val firstUpper = slice.first().core.firstOrNull()?.isUpperCase() == true
        return when {
            allCaps -> fixedLower.uppercase()
            firstUpper -> fixedLower.replaceFirstChar { it.uppercaseChar() }
            else -> fixedLower
        }
    }

    private fun buildProtectedSet(properNouns: Collection<String>): Set<String> {
        if (properNouns.isEmpty()) return emptySet()
        val set = HashSet<String>(properNouns.size * 2)
        for (noun in properNouns) {
            noun.trim().split(Regex("""\s+""")).forEach { part ->
                if (part.length >= 2) set.add(part.lowercase())
            }
        }
        return set
    }

    private fun tokenize(text: String): List<Token> {
        if (text.isBlank()) return emptyList()
        return text.split(' ').filter { it.isNotEmpty() }.map { raw ->
            var s = 0
            var e = raw.length
            while (s < e && !raw[s].isLetterOrDigit()) s++
            while (e > s && !raw[e - 1].isLetterOrDigit()) e--
            Token(
                leadingPunct = raw.substring(0, s),
                core = raw.substring(s, e),
                trailingPunct = raw.substring(e)
            )
        }
    }
}
