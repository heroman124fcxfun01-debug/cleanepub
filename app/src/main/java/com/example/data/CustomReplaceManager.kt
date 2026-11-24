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
    }
}
