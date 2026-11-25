package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.pipeline.ManualChapterContent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomReplaceRule(
    val id: String = UUID.randomUUID().toString(),
    val findText: String,
    val replaceText: String
)

/**
 * 100% Offline Personal Quick-Replace Dictionary (Upgrade 1.2):
 * Stores recurring OCR/name correction pairs (`Từ lỗi -> Từ đúng`, e.g., `rn -> m`, `Iàm -> làm`)
 * in SharedPreferences and supports 1-tap batch replacement across an entire book.
 */
class CustomReplaceManager(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getRules(): List<CustomReplaceRule> {
        val raw = prefs.getString(KEY_RULES_JSON, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val result = ArrayList<CustomReplaceRule>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val findText = obj.optString("findText").orEmpty()
                val replaceText = obj.optString("replaceText").orEmpty()
                if (findText.isEmpty()) continue
                result.add(
                    CustomReplaceRule(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        findText = findText,
                        replaceText = replaceText
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun addRule(findText: String, replaceText: String): List<CustomReplaceRule> {
        if (findText.isEmpty()) return getRules()
        val current = getRules().toMutableList()
        // Replace existing rule if same exact findText already exists
        current.removeAll { it.findText == findText }
        current.add(
            0,
            CustomReplaceRule(
                findText = findText,
                replaceText = replaceText
            )
        )
        saveRules(current)
        return current
    }

    fun removeRule(id: String): List<CustomReplaceRule> {
        val updated = getRules().filterNot { it.id == id }
        saveRules(updated)
        return updated
    }

    fun clearAll(): List<CustomReplaceRule> {
        prefs.edit().remove(KEY_RULES_JSON).apply()
        return emptyList()
    }

    private fun saveRules(rules: List<CustomReplaceRule>) {
        val array = JSONArray()
        for (rule in rules) {
            array.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("findText", rule.findText)
                    put("replaceText", rule.replaceText)
                }
            )
        }
        prefs.edit().putString(KEY_RULES_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "epub_clean_custom_replace_prefs"
        private const val KEY_RULES_JSON = "custom_replace_rules_v1"

        /**
         * Real-time case-insensitive filter across BOTH `findText` ("Từ lỗi")
         * and `replaceText` ("Từ đúng") columns for the compact Settings table.
         */
        fun filterRules(
            rules: List<CustomReplaceRule>,
            query: String
        ): List<CustomReplaceRule> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return rules
            return rules.filter { rule ->
                rule.findText.contains(trimmed, ignoreCase = true) ||
                    rule.replaceText.contains(trimmed, ignoreCase = true)
            }
        }

        /**
         * Pure Kotlin helper: applies all saved rules sequentially to a single paragraph
         * and returns `(updatedText, totalReplacementsCount)`.
         */
        fun applyRulesToText(text: String, rules: List<CustomReplaceRule>): Pair<String, Int> {
            if (text.isEmpty() || rules.isEmpty()) return text to 0
            var working = text
            var count = 0
            for (rule in rules) {
                if (rule.findText.isEmpty() || rule.findText == rule.replaceText) continue
                var idx = working.indexOf(rule.findText)
                if (idx < 0) continue

                val sb = StringBuilder(working.length)
                var lastEnd = 0
                while (idx >= 0) {
                    sb.append(working, lastEnd, idx)
                    sb.append(rule.replaceText)
                    count++
                    lastEnd = idx + rule.findText.length
                    idx = working.indexOf(rule.findText, startIndex = lastEnd)
                }
                sb.append(working, lastEnd, working.length)
                working = sb.toString()
            }
            return working to count
        }

        /**
         * Applies every saved personal quick-replace rule across all chapters in 1 batch pass.
         */
        fun applyRulesToChapters(
            chapters: List<ManualChapterContent>,
            rules: List<CustomReplaceRule>
        ): Pair<List<ManualChapterContent>, Int> {
            if (chapters.isEmpty() || rules.isEmpty()) return chapters to 0
            var totalReplaced = 0
            val updated = chapters.map { chapter ->
                var chapterChanged = false
                val newParagraphs = chapter.currentParagraphs.map { para ->
                    val (replacedText, hits) = applyRulesToText(para, rules)
                    if (hits > 0) {
                        chapterChanged = true
                        totalReplaced += hits
                        replacedText
                    } else {
                        para
                    }
                }
                if (chapterChanged) chapter.copy(currentParagraphs = newParagraphs) else chapter
            }
            return updated to totalReplaced
        }

        private val whitespaceSplitRegex = Regex("""\s+""")
        private val edgePunctChars = setOf('.', ',', ';', ':', '!', '?', '…', '"', '“', '”', '\'', '‘', '’', '(', ')', '[', ']')

        /**
         * Upgrade 1.5: Automatically diffs a paragraph before & after manual editing to extract a clean
         * short replacement pair (`1..3 words -> 1..3 words`) suitable for 1-tap saving into the
         * Quick-Replace Dictionary ("Thư viện thay thế").
         */
        fun extractQuickRuleSuggestion(
            beforeParagraph: String,
            afterParagraph: String,
            existingRules: List<CustomReplaceRule> = emptyList()
        ): Pair<String, String>? {
            if (beforeParagraph.isBlank() || afterParagraph.isBlank() || beforeParagraph == afterParagraph) {
                return null
            }

            // 1. Find longest common prefix and suffix
            val minLen = minOf(beforeParagraph.length, afterParagraph.length)
            var prefix = 0
            while (prefix < minLen && beforeParagraph[prefix] == afterParagraph[prefix]) {
                prefix++
            }

            var beforeEnd = beforeParagraph.length
            var afterEnd = afterParagraph.length
            while (beforeEnd > prefix && afterEnd > prefix &&
                beforeParagraph[beforeEnd - 1] == afterParagraph[afterEnd - 1]
            ) {
                beforeEnd--
                afterEnd--
            }

            // 2. Expand boundaries outward to complete words when the diff touches inside a word
            //    (e.g. "muồn" -> "muốn", "uốn" -> "muốn"), without swallowing unrelated following
            //    words on whole-word insertions/deletions (e.g. "Lý Tiêu" -> "Lý Tiêu Dao").
            var start = prefix
            while (start > 0 && isWordChar(beforeParagraph[start - 1])) {
                start--
            }
            val shouldExpandBeforeEnd = beforeEnd < beforeParagraph.length &&
                isWordChar(beforeParagraph[beforeEnd]) &&
                (
                    (beforeEnd > start && isWordChar(beforeParagraph[beforeEnd - 1])) ||
                        (beforeEnd == start && afterEnd > start && isWordChar(afterParagraph[afterEnd - 1]))
                )
            val shouldExpandAfterEnd = afterEnd < afterParagraph.length &&
                isWordChar(afterParagraph[afterEnd]) &&
                (
                    (afterEnd > start && isWordChar(afterParagraph[afterEnd - 1])) ||
                        (afterEnd == start && beforeEnd > start && isWordChar(beforeParagraph[beforeEnd - 1]))
                )
            if (shouldExpandBeforeEnd) {
                while (beforeEnd < beforeParagraph.length && isWordChar(beforeParagraph[beforeEnd])) {
                    beforeEnd++
                }
            }
            if (shouldExpandAfterEnd) {
                while (afterEnd < afterParagraph.length && isWordChar(afterParagraph[afterEnd])) {
                    afterEnd++
                }
            }

            // 2b. For whole-word insertions/deletions where one side is empty, pull preceding
            //     context word(s) (up to 2 consecutive capitalized proper-noun words, or 1 word).
            if (start >= beforeEnd || start >= afterEnd) {
                val expandedStart = expandStartToPrecedingContextWords(beforeParagraph, start)
                if (expandedStart < start) {
                    start = expandedStart
                } else {
                    // Fallback when insertion/deletion is at the very beginning of the paragraph:
                    // pull 1 following word into both spans.
                    var bEnd = beforeEnd
                    var aEnd = afterEnd
                    while (bEnd < beforeParagraph.length && beforeParagraph[bEnd].isWhitespace()) bEnd++
                    while (bEnd < beforeParagraph.length && isWordChar(beforeParagraph[bEnd])) bEnd++
                    while (aEnd < afterParagraph.length && afterParagraph[aEnd].isWhitespace()) aEnd++
                    while (aEnd < afterParagraph.length && isWordChar(afterParagraph[aEnd])) aEnd++
                    beforeEnd = bEnd
                    afterEnd = aEnd
                }
            }

            if (start >= beforeEnd || start >= afterEnd) return null

            var oldPhrase = beforeParagraph.substring(start, beforeEnd).trim()
            var newPhrase = afterParagraph.substring(start, afterEnd).trim()

            // 3. Strip identical leading/trailing punctuation shared by both sides
            while (oldPhrase.length > 1 && newPhrase.length > 1 &&
                oldPhrase.first() == newPhrase.first() &&
                oldPhrase.first() in edgePunctChars
            ) {
                oldPhrase = oldPhrase.drop(1).trimStart()
                newPhrase = newPhrase.drop(1).trimStart()
            }
            while (oldPhrase.length > 1 && newPhrase.length > 1 &&
                oldPhrase.last() == newPhrase.last() &&
                oldPhrase.last() in edgePunctChars
            ) {
                oldPhrase = oldPhrase.dropLast(1).trimEnd()
                newPhrase = newPhrase.dropLast(1).trimEnd()
            }

            if (oldPhrase.isEmpty() || newPhrase.isEmpty() || oldPhrase == newPhrase) return null
            if (oldPhrase.length !in 2..48 || newPhrase.length !in 1..48) return null
            if (oldPhrase.contains('\n') || newPhrase.contains('\n')) return null

            val oldWords = oldPhrase.split(whitespaceSplitRegex).filter { it.isNotEmpty() }
            val newWords = newPhrase.split(whitespaceSplitRegex).filter { it.isNotEmpty() }
            if (oldWords.size !in 1..3 || newWords.size !in 1..3) return null

            // Avoid suggesting a rule that is already saved identically
            if (existingRules.any { it.findText == oldPhrase && it.replaceText == newPhrase }) {
                return null
            }

            return oldPhrase to newPhrase
        }

        private fun expandStartToPrecedingContextWords(text: String, initialStart: Int): Int {
            var pos = initialStart
            while (pos > 0 && (text[pos - 1] == ' ' || text[pos - 1] == '\t')) {
                pos--
            }
            if (pos <= 0 || !isWordChar(text[pos - 1])) return initialStart

            var firstWordStart = pos
            while (firstWordStart > 0 && isWordChar(text[firstWordStart - 1])) {
                firstWordStart--
            }

            // If the preceding word is capitalized (proper noun), also grab 1 adjacent preceding
            // capitalized word (e.g. "Lý Tiêu" when appending "Dao" -> "Lý Tiêu Dao").
            if (Character.isUpperCase(text[firstWordStart])) {
                var probe = firstWordStart
                while (probe > 0 && (text[probe - 1] == ' ' || text[probe - 1] == '\t')) {
                    probe--
                }
                if (probe > 0 && isWordChar(text[probe - 1])) {
                    var secondWordStart = probe
                    while (secondWordStart > 0 && isWordChar(text[secondWordStart - 1])) {
                        secondWordStart--
                    }
                    if (Character.isUpperCase(text[secondWordStart])) {
                        return secondWordStart
                    }
                }
            }
            return firstWordStart
        }

        private fun isWordChar(ch: Char): Boolean {
            return Character.isLetterOrDigit(ch) || ch == '\'' || ch == '-'
        }
    }
}
