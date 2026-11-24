package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.text.Normalizer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

enum class ErrorMemoryCategory(val label: String) {
    WHITESPACE_SPLIT("Tách/trượt khoảng trắng"),
    OCR_MISRECOGNITION("Nhận diện sai OCR"),
    TONE_TYPO("Sai dấu / Đánh máy"),
    PROPER_NOUN_TERM("Tên riêng / Thuật ngữ"),
    JUNK_DELETION("Xóa rác / Địa chỉ web")
}

data class ErrorMemoryRule(
    val id: String = UUID.randomUUID().toString(),
    val wrongText: String,
    val correctText: String,
    val category: ErrorMemoryCategory,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class ParagraphDiffPair(
    val beforeText: String,
    val afterText: String
)

data class LearnErrorsResult(
    val newRulesAdded: Int,
    val totalRulesCount: Int,
    val addedByCategory: Map<ErrorMemoryCategory, Int> = emptyMap()
)

data class ImportMemoryResult(
    val importedNewCount: Int,
    val skippedDuplicateCount: Int,
    val totalRulesCount: Int
)

data class ApplyMemoryResult(
    val cleanedText: String,
    val vocabFixCount: Int,
    val junkDeletionCount: Int
) {
    val totalFixes: Int
        get() = vocabFixCount + junkDeletionCount
}

/**
 * 100% Offline Self-Learning Error Memory Library (Upgrade 1.3):
 * - Extracts 5 rule categories from `(original, edited)` paragraph pairs:
 *   1. Whitespace split/slide errors (1–4 words, e.g., `cóm ột` -> `có một`, `i den fi ni ty` -> `identity`)
 *   2. OCR character misrecognition (1–4 words, e.g., `Iàm` -> `làm`, `rnột` -> `một`, `ta ra` -> `tạo ra`)
 *   3. Diacritics / typing errors (1–4 words, e.g., `muồn` -> `muốn`, `nựa` -> `nửa`, `quạ chuối` -> `quả chuối`)
 *   4. Proper nouns / terminology fixes (1–4 words)
 *   5. Junk / URL / website / signature deletions (`wrong -> ""`, 5–150 chars, case-insensitive)
 * - Persists offline in `context.filesDir/error_memory_lib.json` and auto-apply toggle in `SharedPreferences` (default ON).
 * - Supports safe JSON Export (`EpubCleanAI_ErrorLib.json`) and non-destructive Merge Import.
 */
class ErrorMemoryManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val libraryFile: File
        get() = File(appContext.filesDir, LIBRARY_FILE_NAME)

    fun isAutoApplyEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_APPLY, true)

    fun setAutoApplyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_APPLY, enabled).apply()
    }

    @Synchronized
    fun getRules(): List<ErrorMemoryRule> {
        if (!libraryFile.exists()) return emptyList()
        return runCatching {
            parseRulesFromJson(libraryFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun getRuleCount(): Int = getRules().size

    /**
     * Analyzes `(before, after)` paragraph pairs from a completed book, filters out unsafe rewrites
     * or single-char edits, merges new unique rules into `error_memory_lib.json`, and returns stats.
     */
    @Synchronized
    fun learnFromDiffPairs(pairs: List<ParagraphDiffPair>): LearnErrorsResult {
        val existing = getRules()
        if (pairs.isEmpty()) {
            return LearnErrorsResult(newRulesAdded = 0, totalRulesCount = existing.size)
        }

        val candidates = extractRulesFromPairs(pairs)
        val merged = mergeUniqueRules(existing, candidates)
        if (merged.addedRules.isNotEmpty()) {
            saveRules(merged.allRules)
        }

        val byCategory = merged.addedRules.groupingBy { it.category }.eachCount()
        return LearnErrorsResult(
            newRulesAdded = merged.addedRules.size,
            totalRulesCount = merged.allRules.size,
            addedByCategory = byCategory
        )
    }

    /**
     * Exports the entire library to a SAF URI (`EpubCleanAI_ErrorLib.json`).
     * Returns number of exported rules, or -1 on write error.
     */
    @Synchronized
    fun exportToUri(uri: Uri): Int {
        val rules = getRules()
        return runCatching {
            val json = serializeRulesToJson(rules)
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return -1
            rules.size
        }.getOrDefault(-1)
    }

    /**
     * Reads a backup `.json` from SAF URI and merges new rules into the current library
     * without overwriting existing entries (automatically skipping duplicates).
     */
    @Synchronized
    fun importAndMergeFromUri(uri: Uri): ImportMemoryResult? {
        return runCatching {
            val rawJson = appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return null

            val incoming = parseRulesFromJson(rawJson)
            val existing = getRules()
            val merged = mergeUniqueRules(existing, incoming)
            if (merged.addedRules.isNotEmpty()) {
                saveRules(merged.allRules)
            }
            ImportMemoryResult(
                importedNewCount = merged.addedRules.size,
                skippedDuplicateCount = (incoming.size - merged.addedRules.size).coerceAtLeast(0),
                totalRulesCount = merged.allRules.size
            )
        }.getOrNull()
    }

    @Synchronized
    fun clearLibrary() {
        runCatching {
            if (libraryFile.exists()) libraryFile.delete()
        }
    }

    private fun saveRules(rules: List<ErrorMemoryRule>) {
        val json = serializeRulesToJson(rules)
        val temp = File(libraryFile.parentFile, "${LIBRARY_FILE_NAME}.tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (libraryFile.exists()) libraryFile.delete()
        if (!temp.renameTo(libraryFile)) {
            libraryFile.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        private const val PREFS_NAME = "epub_clean_error_memory_prefs"
        private const val KEY_AUTO_APPLY = "error_memory_auto_apply_v1"
        private const val LIBRARY_FILE_NAME = "error_memory_lib.json"
        const val DEFAULT_EXPORT_FILENAME = "EpubCleanAI_ErrorLib.json"

        private const val MIN_DELETION_LEN = 5
        private const val MAX_DELETION_LEN = 150
        private const val MAX_PHRASE_WORDS = 4

        private val whitespaceRegex = Regex("""\s+""")
        private val tokenRegex = Regex("""[\p{L}\p{N}]+|[^\s\p{L}\p{N}]+""")
        private val diacriticsRegex = Regex("""\p{InCombiningDiacriticalMarks}+""")
        private val multiSpaceRegex = Regex("""[ \t]{2,}""")
        private val spaceBeforePunctRegex = Regex("""\s+([,.;:!?])""")

        internal data class MergeOutcome(
            val allRules: List<ErrorMemoryRule>,
            val addedRules: List<ErrorMemoryRule>
        )

        /**
         * Pure Kotlin rule extractor analyzing all 5 categories from `(original, edited)` pairs.
         * Fully testable on JVM without Android dependencies.
         */
        fun extractRulesFromPairs(pairs: List<ParagraphDiffPair>): List<ErrorMemoryRule> {
            val extracted = mutableListOf<ErrorMemoryRule>()
            val seenKeys = HashSet<String>()

            fun addCandidate(wrong: String, correct: String, category: ErrorMemoryCategory) {
                val key = dedupKey(wrong, category)
                if (seenKeys.add(key)) {
                    extracted.add(
                        ErrorMemoryRule(
                            wrongText = wrong,
                            correctText = correct,
                            category = category
                        )
                    )
                }
            }

            for (pair in pairs) {
                val before = pair.beforeText.trim()
                val after = pair.afterText.trim()
                if (before.isEmpty() || before == after) continue

                // Case 1: Entire paragraph / promo line / URL deleted by user
                if (after.isEmpty()) {
                    if (before.length in MIN_DELETION_LEN..MAX_DELETION_LEN) {
                        addCandidate(before, "", ErrorMemoryCategory.JUNK_DELETION)
                    }
                    continue
                }

                // Case 2: Token-level LCS diff to isolate minimal contiguous changed spans
                val diffSpans = computeMinimalDiffSpans(before, after)
                for ((rawWrong, rawCorrect) in diffSpans) {
                    val wrong = rawWrong.trim()
                    val correct = rawCorrect.trim()
                    if (wrong.isEmpty() || wrong == correct) continue

                    // Group 5: Inline Deletion Rule (e.g. removed URL, website name, source signature)
                    if (correct.isEmpty()) {
                        if (wrong.length in MIN_DELETION_LEN..MAX_DELETION_LEN) {
                            addCandidate(wrong, "", ErrorMemoryCategory.JUNK_DELETION)
                        }
                        continue
                    }

                    // Safety Filter: skip single-character replacements (high false-positive risk)
                    if (wrong.length <= 1 || correct.length <= 1) continue

                    // Safety Filter: skip pure punctuation tweaks with no letters
                    if (!wrong.any { it.isLetter() } || !correct.any { it.isLetter() }) continue

                    val wrongWords = countWords(wrong)
                    val correctWords = countWords(correct)

                    // Allow 1–4 word corrections; also allow 5–6 tiny split fragments collapsing into 1 word
                    // (e.g., "i den fi ni ty" -> "identity")
                    val isSplitSingleWord = correctWords == 1 &&
                        wrongWords in 2..6 &&
                        isLikelyWhitespaceSplit(wrong, correct)

                    if (!isSplitSingleWord && (wrongWords !in 1..MAX_PHRASE_WORDS || correctWords !in 1..MAX_PHRASE_WORDS)) {
                        continue
                    }

                    // If an adjacent span has identical word counts (2..4 words) and is NOT a whitespace split
                    // or Title Case proper noun, break into individual reusable word-level rules when each pair
                    // is a valid single-word OCR/tone fix (e.g. "Iàm rnột" -> "làm một").
                    if (wrongWords in 2..MAX_PHRASE_WORDS &&
                        wrongWords == correctWords &&
                        !isLikelyWhitespaceSplit(wrong, correct) &&
                        !isLikelyProperNounFix(wrong, correct)
                    ) {
                        val wTokens = wrong.split(whitespaceRegex).filter { it.isNotEmpty() }
                        val cTokens = correct.split(whitespaceRegex).filter { it.isNotEmpty() }
                        val allWordLevelValid = wTokens.size == cTokens.size && wTokens.indices.all { idx ->
                            val wt = wTokens[idx]
                            val ct = cTokens[idx]
                            wt.length > 1 && ct.length > 1 && classifyReplacement(wt, ct) != null
                        }
                        if (allWordLevelValid) {
                            for (idx in wTokens.indices) {
                                val wt = wTokens[idx]
                                val ct = cTokens[idx]
                                if (wt != ct) {
                                    val cat = classifyReplacement(wt, ct) ?: continue
                                    addCandidate(wt, ct, cat)
                                }
                            }
                            continue
                        }
                    }

                    val category = classifyReplacement(wrong, correct) ?: continue
                    addCandidate(wrong, correct, category)
                }
            }

            return extracted
        }

        /**
         * Applies learned Error Memory rules to a paragraph:
         * - First strips learned Junk/URL deletion rules (case-insensitive, longest first).
         * - Then applies learned vocabulary/OCR/tone/proper-noun fixes (longest first, word-boundary safe).
         */
        fun applyRulesToText(text: String, rules: List<ErrorMemoryRule>): ApplyMemoryResult {
            if (text.isEmpty() || rules.isEmpty()) {
                return ApplyMemoryResult(text, vocabFixCount = 0, junkDeletionCount = 0)
            }

            var working = text
            var junkCount = 0
            var vocabCount = 0

            // 1. Deletion rules first (case-insensitive, longest match first)
            val deletionRules = rules
                .filter { it.category == ErrorMemoryCategory.JUNK_DELETION || it.correctText.isEmpty() }
                .sortedByDescending { it.wrongText.length }

            for (rule in deletionRules) {
                val target = rule.wrongText
                if (target.length < MIN_DELETION_LEN) continue
                if (!working.contains(target, ignoreCase = true)) continue

                val regex = Regex(Regex.escape(target), RegexOption.IGNORE_CASE)
                val hits = regex.findAll(working).count()
                if (hits > 0) {
                    working = regex.replace(working, "")
                    junkCount += hits
                }
            }

            if (junkCount > 0) {
                working = working
                    .replace(multiSpaceRegex, " ")
                    .replace(spaceBeforePunctRegex, "$1")
                    .trim()
                if (working.isEmpty()) {
                    return ApplyMemoryResult("", vocabFixCount = 0, junkDeletionCount = junkCount)
                }
            }

            // 2. Vocabulary / Whitespace / OCR / Tone / Proper Noun rules (longest first, boundary-safe)
            val replaceRules = rules
                .filter { it.category != ErrorMemoryCategory.JUNK_DELETION && it.correctText.isNotEmpty() }
                .sortedByDescending { it.wrongText.length }

            for (rule in replaceRules) {
                val target = rule.wrongText
                if (target.length <= 1 || target == rule.correctText) continue
                if (!working.contains(target)) continue

                val (replaced, hits) = replaceWholePhraseSafe(working, target, rule.correctText)
                if (hits > 0) {
                    working = replaced
                    vocabCount += hits
                }
            }

            return ApplyMemoryResult(
                cleanedText = working,
                vocabFixCount = vocabCount,
                junkDeletionCount = junkCount
            )
        }

        internal fun mergeUniqueRules(
            existing: List<ErrorMemoryRule>,
            incoming: List<ErrorMemoryRule>
        ): MergeOutcome {
            val seen = HashSet<String>(existing.size + incoming.size)
            for (rule in existing) {
                seen.add(dedupKey(rule.wrongText, rule.category))
            }
            val added = mutableListOf<ErrorMemoryRule>()
            for (candidate in incoming) {
                val cleanWrong = candidate.wrongText.trim()
                val cleanCorrect = candidate.correctText.trim()
                if (cleanWrong.isEmpty() || cleanWrong == cleanCorrect) continue
                if (candidate.category == ErrorMemoryCategory.JUNK_DELETION) {
                    if (cleanWrong.length !in MIN_DELETION_LEN..MAX_DELETION_LEN) continue
                } else {
                    if (cleanWrong.length <= 1 || cleanCorrect.length <= 1) continue
                }
                val key = dedupKey(cleanWrong, candidate.category)
                if (seen.add(key)) {
                    added.add(candidate.copy(wrongText = cleanWrong, correctText = cleanCorrect))
                }
            }
            // Place newly learned rules first so they are easy to inspect/export
            return MergeOutcome(
                allRules = added + existing,
                addedRules = added
            )
        }

        fun serializeRulesToJson(rules: List<ErrorMemoryRule>): String {
            val sb = StringBuilder(rules.size * 128 + 160)
            sb.append("{\n")
            sb.append("  \"app\": \"EpubCleanAI\",\n")
            sb.append("  \"formatVersion\": 1,\n")
            sb.append("  \"exportedAtMillis\": ").append(System.currentTimeMillis()).append(",\n")
            sb.append("  \"ruleCount\": ").append(rules.size).append(",\n")
            sb.append("  \"rules\": [\n")
            rules.forEachIndexed { idx, r ->
                sb.append("    {\n")
                sb.append("      \"id\": \"").append(escapeJsonString(r.id)).append("\",\n")
                sb.append("      \"wrongText\": \"").append(escapeJsonString(r.wrongText)).append("\",\n")
                sb.append("      \"correctText\": \"").append(escapeJsonString(r.correctText)).append("\",\n")
                sb.append("      \"category\": \"").append(escapeJsonString(r.category.name)).append("\",\n")
                sb.append("      \"createdAtMillis\": ").append(r.createdAtMillis).append('\n')
                sb.append("    }")
                if (idx < rules.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append("  ]\n")
            sb.append("}\n")
            return sb.toString()
        }

        fun parseRulesFromJson(rawJson: String): List<ErrorMemoryRule> {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return emptyList()
            val objects = extractJsonObjectsFromArray(trimmed)
            val result = ArrayList<ErrorMemoryRule>(objects.size)
            for (fields in objects) {
                val wrong = fields["wrongText"]?.trim().orEmpty()
                val correct = fields["correctText"]?.trim().orEmpty()
                if (wrong.isEmpty() || wrong == correct) continue
                val catName = fields["category"].orEmpty()
                val category = runCatching { ErrorMemoryCategory.valueOf(catName) }
                    .getOrElse {
                        if (correct.isEmpty()) ErrorMemoryCategory.JUNK_DELETION
                        else classifyReplacement(wrong, correct) ?: ErrorMemoryCategory.OCR_MISRECOGNITION
                    }
                val id = fields["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val createdAt = fields["createdAtMillis"]?.toLongOrNull() ?: System.currentTimeMillis()
                result.add(
                    ErrorMemoryRule(
                        id = id,
                        wrongText = wrong,
                        correctText = correct,
                        category = category,
                        createdAtMillis = createdAt
                    )
                )
            }
            return result
        }

        private fun escapeJsonString(input: String): String {
            val sb = StringBuilder(input.length + 8)
            for (ch in input) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u%04x".format(ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            return sb.toString()
        }

        /**
         * Lightweight pure-Kotlin RFC-8259 JSON object reader for `rules` arrays
         * (supports both `{ "rules": [ ... ] }` and bare `[ ... ]` formats).
         */
        private fun extractJsonObjectsFromArray(raw: String): List<Map<String, String>> {
            val startBracket = if (raw.startsWith("[")) {
                0
            } else {
                val rulesKeyIdx = raw.indexOf("\"rules\"")
                if (rulesKeyIdx < 0) return emptyList()
                raw.indexOf('[', startIndex = rulesKeyIdx)
            }
            if (startBracket < 0) return emptyList()

            val results = mutableListOf<Map<String, String>>()
            var pos = startBracket + 1
            val len = raw.length

            fun skipWs() {
                while (pos < len && raw[pos].isWhitespace()) pos++
            }

            fun readQuotedString(): String? {
                skipWs()
                if (pos >= len || raw[pos] != '"') return null
                pos++
                val sb = StringBuilder()
                while (pos < len) {
                    val ch = raw[pos++]
                    if (ch == '"') return sb.toString()
                    if (ch == '\\' && pos < len) {
                        when (val esc = raw[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= len) {
                                    val hex = raw.substring(pos, pos + 4)
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return null
            }

            fun readPrimitive(): String {
                skipWs()
                val start = pos
                while (pos < len && raw[pos] !in charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t')) {
                    pos++
                }
                return raw.substring(start, pos)
            }

            while (pos < len) {
                skipWs()
                if (pos >= len || raw[pos] == ']') break
                if (raw[pos] == ',') {
                    pos++
                    continue
                }
                if (raw[pos] != '{') {
                    pos++
                    continue
                }
                pos++ // consume '{'
                val fields = LinkedHashMap<String, String>()
                while (pos < len) {
                    skipWs()
                    if (pos >= len) break
                    if (raw[pos] == '}') {
                        pos++
                        break
                    }
                    if (raw[pos] == ',') {
                        pos++
                        continue
                    }
                    val key = readQuotedString() ?: break
                    skipWs()
                    if (pos >= len || raw[pos] != ':') break
                    pos++ // consume ':'
                    skipWs()
                    if (pos >= len) break
                    val value = if (raw[pos] == '"') {
                        readQuotedString().orEmpty()
                    } else {
                        readPrimitive()
                    }
                    fields[key] = value
                }
                if (fields.isNotEmpty()) {
                    results.add(fields)
                }
            }
            return results
        }

        private fun dedupKey(wrongText: String, category: ErrorMemoryCategory): String {
            return if (category == ErrorMemoryCategory.JUNK_DELETION) {
                "DEL::${wrongText.trim().lowercase()}"
            } else {
                "REP::${wrongText.trim()}"
            }
        }

        private fun countWords(text: String): Int {
            return text.trim().split(whitespaceRegex).count { it.isNotEmpty() }
        }

        private fun classifyReplacement(wrong: String, correct: String): ErrorMemoryCategory? {
            // 1. Whitespace split / slide (e.g., "cóm ột" -> "có một", "chú ýt ới" -> "chú ý tới", "i den fi ni ty" -> "identity")
            if (isLikelyWhitespaceSplit(wrong, correct)) {
                return ErrorMemoryCategory.WHITESPACE_SPLIT
            }

            // 2. Tone / diacritics typo (same base Latin letters ignoring diacritics, e.g., "muồn" -> "muốn", "quạ chuối" -> "quả chuối")
            val wrongBase = stripDiacritics(wrong).lowercase()
            val correctBase = stripDiacritics(correct).lowercase()
            if (wrongBase == correctBase && wrong.lowercase() != correct.lowercase()) {
                return ErrorMemoryCategory.TONE_TYPO
            }

            // 3. Proper noun / terminology (Title Case / Capitalized name corrections)
            if (isLikelyProperNounFix(wrong, correct)) {
                return ErrorMemoryCategory.PROPER_NOUN_TERM
            }

            // 4. OCR misrecognition or closely related short typo (e.g., "Iàm" -> "làm", "rnột" -> "một", "ta ra" -> "tạo ra")
            val dist = levenshtein(wrongBase, correctBase)
            val maxLen = maxOf(wrongBase.length, correctBase.length).coerceAtLeast(1)
            val similarityRatio = 1.0 - (dist.toDouble() / maxLen.toDouble())
            return when {
                isClassicOcrConfusion(wrong, correct) || similarityRatio >= 0.45 -> {
                    ErrorMemoryCategory.OCR_MISRECOGNITION
                }
                countWords(wrong) <= 2 && countWords(correct) <= 2 && dist <= 3 -> {
                    ErrorMemoryCategory.TONE_TYPO
                }
                else -> null // Reject unrelated semantic rewrites for safety
            }
        }

        private fun isLikelyWhitespaceSplit(wrong: String, correct: String): Boolean {
            val wNoSpace = wrong.replace(whitespaceRegex, "")
            val cNoSpace = correct.replace(whitespaceRegex, "")
            if (wNoSpace.equals(cNoSpace, ignoreCase = true) && wrong != correct) {
                return true
            }
            // Fuzzy split like "i den fi ni ty" -> "identity" (OCR slip + multiple broken spaces)
            val wCount = countWords(wrong)
            val cCount = countWords(correct)
            if (wCount != cCount) {
                val lenDiff = abs(wNoSpace.length - cNoSpace.length)
                val dist = levenshtein(wNoSpace.lowercase(), cNoSpace.lowercase())
                if (lenDiff <= 1 && dist <= 2 && cNoSpace.length >= 5) {
                    return true
                }
                if (wCount >= 3 && cCount == 1 && lenDiff <= 3 && dist <= 3 && cNoSpace.length >= 6) {
                    return true
                }
            }
            return false
        }

        private fun isLikelyProperNounFix(wrong: String, correct: String): Boolean {
            val correctTokens = correct.trim().split(whitespaceRegex).filter { it.isNotEmpty() }
            if (correctTokens.isEmpty()) return false
            val capitalizedCount = correctTokens.count { tok ->
                tok.firstOrNull()?.isUpperCase() == true
            }
            // Multi-word Title Case name (e.g., "Lý Tiêu Dao", "Trương Vô Kỵ") or capitalization-only fix
            if (correctTokens.size >= 2 && capitalizedCount >= 2) return true
            if (wrong.equals(correct, ignoreCase = true) && capitalizedCount >= 1) return true
            return false
        }

        private fun isClassicOcrConfusion(wrong: String, correct: String): Boolean {
            val ocrPairs = listOf(
                "rn" to "m",
                "I" to "l",
                "1" to "l",
                "0" to "o",
                "cl" to "d",
                "vv" to "w"
            )
            for ((a, b) in ocrPairs) {
                if ((wrong.contains(a) && correct.contains(b)) || (wrong.contains(b) && correct.contains(a))) {
                    return true
                }
            }
            return false
        }

        private fun stripDiacritics(input: String): String {
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            return diacriticsRegex.replace(normalized, "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.length]
        }

        private fun replaceWholePhraseSafe(
            source: String,
            target: String,
            replacement: String
        ): Pair<String, Int> {
            var idx = source.indexOf(target)
            if (idx < 0) return source to 0

            val sb = StringBuilder(source.length)
            var lastEnd = 0
            var count = 0

            while (idx >= 0) {
                val end = idx + target.length
                val prevChar = if (idx > 0) source[idx - 1] else null
                val nextChar = if (end < source.length) source[end] else null
                val startBoundaryOk = !target.first().isLetterOrDigit() || prevChar == null || !prevChar.isLetterOrDigit()
                val endBoundaryOk = !target.last().isLetterOrDigit() || nextChar == null || !nextChar.isLetterOrDigit()

                if (startBoundaryOk && endBoundaryOk) {
                    sb.append(source, lastEnd, idx)
                    sb.append(replacement)
                    count++
                    lastEnd = end
                    idx = source.indexOf(target, startIndex = end)
                } else {
                    idx = source.indexOf(target, startIndex = idx + 1)
                }
            }

            if (count == 0) return source to 0
            sb.append(source, lastEnd, source.length)
            return sb.toString() to count
        }

        /**
         * Tokenizes `before` and `after` and computes contiguous changed spans via LCS DP.
         */
        private fun computeMinimalDiffSpans(before: String, after: String): List<Pair<String, String>> {
            val aTokens = tokenRegex.findAll(before).toList()
            val bTokens = tokenRegex.findAll(after).toList()
            val n = aTokens.size
            val m = bTokens.size
            if (n == 0 || m == 0) return listOf(before to after)

            // Guard against massive paragraphs (cap DP matrix size for 4GB RAM phones)
            if (n * m > 80_000) {
                return listOf(before to after)
            }

            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                val aVal = aTokens[i].value
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (aVal == bTokens[j].value) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }

            val spans = mutableListOf<Pair<String, String>>()
            var i = 0
            var j = 0
            while (i < n || j < m) {
                if (i < n && j < m && aTokens[i].value == bTokens[j].value) {
                    i++
                    j++
                } else {
                    val startI = i
                    val startJ = j
                    while (i < n || j < m) {
                        if (i < n && j < m && aTokens[i].value == bTokens[j].value) {
                            break
                        }
                        if (i < n && (j == m || dp[i + 1][j] >= dp[i][j + 1])) {
                            i++
                        } else if (j < m) {
                            j++
                        }
                    }

                    var wrongSlice = if (startI < i) {
                        before.substring(aTokens[startI].range.first, aTokens[i - 1].range.last + 1)
                    } else {
                        ""
                    }
                    var correctSlice = if (startJ < j) {
                        after.substring(bTokens[startJ].range.first, bTokens[j - 1].range.last + 1)
                    } else {
                        ""
                    }

                    // If user appended a Title Case proper-noun word (e.g. "Lý Tiêu" -> "Lý Tiêu Dao"),
                    // pull in up to 2 immediately preceding Title Case tokens so the rule is context-specific.
                    if (wrongSlice.isEmpty() &&
                        correctSlice.isNotEmpty() &&
                        correctSlice.first().isUpperCase() &&
                        startI > 0 && startJ > 0
                    ) {
                        var backI = startI - 1
                        var backJ = startJ - 1
                        var pulled = 0
                        while (backI >= 0 && backJ >= 0 && pulled < 2 &&
                            aTokens[backI].value == bTokens[backJ].value &&
                            aTokens[backI].value.firstOrNull()?.isUpperCase() == true
                        ) {
                            backI--
                            backJ--
                            pulled++
                        }
                        if (pulled >= 2) {
                            val prefixStartA = aTokens[backI + 1].range.first
                            val prefixEndA = aTokens[startI - 1].range.last + 1
                            val prefixStartB = bTokens[backJ + 1].range.first
                            val prefixEndB = bTokens[j - 1].range.last + 1
                            wrongSlice = before.substring(prefixStartA, prefixEndA)
                            correctSlice = after.substring(prefixStartB, prefixEndB)
                        }
                    }

                    if (wrongSlice.isNotBlank() || correctSlice.isNotBlank()) {
                        spans.add(wrongSlice to correctSlice)
                    }
                }
            }
            return spans
        }
    }
}
