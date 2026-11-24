package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lightweight record of a cleaned ebook stored offline in SharedPreferences (JSON).
 */
data class CleanedFileHistory(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val filePath: String,
    val uriString: String? = null,
    val sourceType: String, // "EPUB" or "PDF"
    val totalFixes: Int,
    val junkLinksFixed: Int = 0,
    val whitespaceFixed: Int = 0,
    val spellingFixed: Int = 0,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * 100% Offline History Manager backed by SharedPreferences (lightweight JSON, max 20 recent files).
 * Automatically prepends newly saved files to the top and deduplicates by file path.
 */
class HistoryManager(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getHistory(): List<CleanedFileHistory> {
        val rawJson = prefs.getString(KEY_HISTORY_JSON, null)
        if (rawJson.isNullOrBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(rawJson)
            val result = ArrayList<CleanedFileHistory>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val fileName = obj.optString("fileName").orEmpty()
                val filePath = obj.optString("filePath").orEmpty()
                if (fileName.isBlank()) continue
                result.add(
                    CleanedFileHistory(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        fileName = fileName,
                        filePath = filePath,
                        uriString = obj.optString("uriString").takeIf { it.isNotBlank() },
                        sourceType = obj.optString("sourceType").ifBlank { "EPUB" },
                        totalFixes = obj.optInt("totalFixes", 0),
                        junkLinksFixed = obj.optInt("junkLinksFixed", 0),
                        whitespaceFixed = obj.optInt("whitespaceFixed", 0),
                        spellingFixed = obj.optInt("spellingFixed", 0),
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis())
                    )
                )
            }
            result.sortedByDescending { it.timestampMillis }.take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    fun addEntry(entry: CleanedFileHistory): List<CleanedFileHistory> {
        val current = getHistory().toMutableList()
        // Deduplicate by exact saved path or same file name + path so re-cleaned books move to top
        current.removeAll { existing ->
            existing.id == entry.id ||
                (entry.filePath.isNotBlank() && existing.filePath == entry.filePath)
        }
        current.add(0, entry)
        val trimmed = current.take(MAX_ITEMS)
        saveList(trimmed)
        return trimmed
    }

    fun removeEntry(id: String): List<CleanedFileHistory> {
        val updated = getHistory().filterNot { it.id == id }
        saveList(updated)
        return updated
    }

    fun clearAll(): List<CleanedFileHistory> {
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
        return emptyList()
    }

    private fun saveList(items: List<CleanedFileHistory>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("filePath", item.filePath)
                put("uriString", item.uriString.orEmpty())
                put("sourceType", item.sourceType)
                put("totalFixes", item.totalFixes)
                put("junkLinksFixed", item.junkLinksFixed)
                put("whitespaceFixed", item.whitespaceFixed)
                put("spellingFixed", item.spellingFixed)
                put("timestampMillis", item.timestampMillis)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "epubclean_history_prefs"
        private const val KEY_HISTORY_JSON = "cleaned_files_history_v1"
        const val MAX_ITEMS = 20

        /**
         * Formats timestamp concisely in Vietnamese:
         * - Today: "14:25 Hôm nay"
         * - Yesterday: "14:25 Hôm qua"
         * - Older: "14:25 23/11"
         */
        fun formatRelativeTime(
            timestampMillis: Long,
            nowMillis: Long = System.currentTimeMillis()
        ): String {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())
            val targetDate = Date(timestampMillis)
            val timePart = timeFormat.format(targetDate)

            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMillis }

            val isSameYear = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR)
            val dayDiff = nowCal.get(Calendar.DAY_OF_YEAR) - targetCal.get(Calendar.DAY_OF_YEAR)

            return when {
                isSameYear && dayDiff == 0 -> "$timePart Hôm nay"
                isSameYear && dayDiff == 1 -> "$timePart Hôm qua"
                else -> "$timePart ${dateFormat.format(targetDate)}"
            }
        }
    }
}
