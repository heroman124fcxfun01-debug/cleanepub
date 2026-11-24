package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lightweight record of a cleaned ebook stored offline in SharedPreferences (JSON),
 * optionally linked to a local diff snapshot file (`filesDir/history_diffs/<id>.json`)
 * so the user can tap `⋮ -> Ghi nhớ lỗi (Lưu vào thư viện)` anytime later.
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
    val timestampMillis: Long = System.currentTimeMillis(),
    val diffSnapshotFile: String? = null
)

/**
 * 100% Offline History Manager backed by SharedPreferences (lightweight JSON, max 20 recent files)
 * plus per-book `(original, edited)` diff snapshots stored in `filesDir/history_diffs/`.
 */
class HistoryManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val diffsDir: File
        get() = File(appContext.filesDir, "history_diffs")

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
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                        diffSnapshotFile = obj.optString("diffSnapshotFile").takeIf { it.isNotBlank() }
                    )
                )
            }
            result.sortedByDescending { it.timestampMillis }.take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    /**
     * Adds a history entry and persists its associated `(before, after)` diff pairs offline
     * so `ErrorMemoryManager` can analyze & learn from this book later.
     */
    fun addEntry(
        entry: CleanedFileHistory,
        diffPairs: List<ParagraphDiffPair> = emptyList()
    ): List<CleanedFileHistory> {
        val snapshotName = if (diffPairs.isNotEmpty()) {
            saveDiffPairsFile(entry.id, diffPairs)
        } else {
            entry.diffSnapshotFile
        }
        val finalEntry = entry.copy(diffSnapshotFile = snapshotName)

        val current = getHistory().toMutableList()
        val removed = current.filter { existing ->
            existing.id == finalEntry.id ||
                (finalEntry.filePath.isNotBlank() && existing.filePath == finalEntry.filePath)
        }
        for (old in removed) {
            if (old.diffSnapshotFile != null && old.diffSnapshotFile != snapshotName) {
                deleteDiffFile(old.diffSnapshotFile)
            }
        }
        current.removeAll { existing ->
            existing.id == finalEntry.id ||
                (finalEntry.filePath.isNotBlank() && existing.filePath == finalEntry.filePath)
        }
        current.add(0, finalEntry)
        val trimmed = current.take(MAX_ITEMS)
        val pruned = current.drop(MAX_ITEMS)
        for (old in pruned) {
            old.diffSnapshotFile?.let { deleteDiffFile(it) }
        }
        saveList(trimmed)
        return trimmed
    }

    fun loadDiffPairsForEntry(entry: CleanedFileHistory): List<ParagraphDiffPair> {
        val fileName = entry.diffSnapshotFile ?: return emptyList()
        val file = File(diffsDir, fileName)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val result = ArrayList<ParagraphDiffPair>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val before = obj.optString("b")
                val after = obj.optString("a")
                if (before.isNotBlank() && before != after) {
                    result.add(ParagraphDiffPair(beforeText = before, afterText = after))
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    fun removeEntry(id: String): List<CleanedFileHistory> {
        val current = getHistory()
        current.filter { it.id == id }.forEach { old ->
            old.diffSnapshotFile?.let { deleteDiffFile(it) }
        }
        val updated = current.filterNot { it.id == id }
        saveList(updated)
        return updated
    }

    fun clearAll(): List<CleanedFileHistory> {
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
        runCatching {
            if (diffsDir.exists()) diffsDir.deleteRecursively()
        }
        return emptyList()
    }

    private fun saveDiffPairsFile(historyId: String, pairs: List<ParagraphDiffPair>): String? {
        return runCatching {
            if (!diffsDir.exists()) diffsDir.mkdirs()
            val safeName = "diff_${historyId.replace(Regex("[^a-zA-Z0-9_-]"), "")}.json"
            val arr = JSONArray()
            // Cap at 1500 changed pairs per book for crisp disk/memory efficiency
            for (pair in pairs.take(MAX_DIFF_PAIRS_PER_BOOK)) {
                if (pair.beforeText.isNotBlank() && pair.beforeText != pair.afterText) {
                    arr.put(
                        JSONObject().apply {
                            put("b", pair.beforeText)
                            put("a", pair.afterText)
                        }
                    )
                }
            }
            if (arr.length() == 0) return null
            File(diffsDir, safeName).writeText(arr.toString(), Charsets.UTF_8)
            safeName
        }.getOrNull()
    }

    private fun deleteDiffFile(fileName: String) {
        runCatching {
            val f = File(diffsDir, fileName)
            if (f.exists()) f.delete()
        }
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
                put("diffSnapshotFile", item.diffSnapshotFile.orEmpty())
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "epubclean_history_prefs"
        private const val KEY_HISTORY_JSON = "cleaned_files_history_v1"
        const val MAX_ITEMS = 20
        private const val MAX_DIFF_PAIRS_PER_BOOK = 1500

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
