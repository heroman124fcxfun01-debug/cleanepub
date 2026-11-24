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
 * Unified lifecycle status for every book in "Tất cả các file":
 * - DRAFT ("Bản nháp"): Saved in progress via "Lưu nháp" or auto-save draft.
 * - SAVED ("Đã lưu"): Exported to a complete .epub via "Lưu" (SaveDialog).
 */
enum class FileRecordStatus {
    DRAFT,
    SAVED
}

/**
 * Unified record for every Draft or Saved ebook in "Tất cả các file", stored offline in
 * SharedPreferences (JSON) and linked to:
 * - `filesDir/edit_states/{id}.json` (full editing snapshot for "Sửa tiếp" & "So sánh")
 * - `filesDir/history_diffs/diff_{id}.json` (diff pairs for "Ghi nhớ lỗi")
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
    val diffSnapshotFile: String? = null,
    val status: FileRecordStatus = FileRecordStatus.SAVED,
    val lastChapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val lastParagraphIndex: Int = 0,
    val editStateFile: String? = null
)

/**
 * 100% Offline Unified File Manager ("Tất cả các file") backed by SharedPreferences
 * (max 30 recent Draft + Saved files) plus per-book `(original, edited)` diff snapshots
 * stored in `filesDir/history_diffs/`.
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
                val status = runCatching {
                    FileRecordStatus.valueOf(obj.optString("status", FileRecordStatus.SAVED.name))
                }.getOrDefault(FileRecordStatus.SAVED)

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
                        diffSnapshotFile = obj.optString("diffSnapshotFile").takeIf { it.isNotBlank() },
                        status = status,
                        lastChapterIndex = obj.optInt("lastChapterIndex", 0).coerceAtLeast(0),
                        totalChapters = obj.optInt("totalChapters", 0).coerceAtLeast(0),
                        lastParagraphIndex = obj.optInt("lastParagraphIndex", 0).coerceAtLeast(0),
                        editStateFile = obj.optString("editStateFile").takeIf { it.isNotBlank() }
                    )
                )
            }
            result.sortedByDescending { it.timestampMillis }.take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    /**
     * Upserts a Draft or Saved entry cleanly at the top of "Tất cả các file":
     * - If an entry with the same `id` exists (for instance, promoting a `DRAFT` entry to `SAVED`
     *   when the user clicks "Lưu"), updates it in-place and moves it to the top with the latest
     *   timestamp — never creating duplicate rows!
     * - Also persists associated `(before, after)` diff pairs offline for "Ghi nhớ lỗi".
     */
    fun addEntry(
        entry: CleanedFileHistory,
        diffPairs: List<ParagraphDiffPair> = emptyList()
    ): List<CleanedFileHistory> {
        val current = getHistory()
        val existingSameId = current.firstOrNull { it.id == entry.id }

        val snapshotName = if (diffPairs.isNotEmpty()) {
            saveDiffPairsFile(entry.id, diffPairs)
        } else {
            entry.diffSnapshotFile ?: existingSameId?.diffSnapshotFile
        }
        val finalEntry = entry.copy(
            diffSnapshotFile = snapshotName,
            editStateFile = entry.editStateFile ?: existingSameId?.editStateFile
        )

        val (updated, removedOrPruned) = mergeHistoryEntry(
            current = current,
            incoming = finalEntry,
            maxItems = MAX_ITEMS
        )
        for (old in removedOrPruned) {
            if (old.id != finalEntry.id &&
                old.diffSnapshotFile != null &&
                old.diffSnapshotFile != snapshotName
            ) {
                deleteDiffFile(old.diffSnapshotFile)
            }
        }
        saveList(updated)
        return updated
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
                put("status", item.status.name)
                put("lastChapterIndex", item.lastChapterIndex)
                put("totalChapters", item.totalChapters)
                put("lastParagraphIndex", item.lastParagraphIndex)
                put("editStateFile", item.editStateFile.orEmpty())
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "epubclean_history_prefs"
        private const val KEY_HISTORY_JSON = "cleaned_files_history_v1"
        const val MAX_ITEMS = 30
        private const val MAX_DIFF_PAIRS_PER_BOOK = 1500

        /**
         * Pure Kotlin deduplication & Draft->Saved promotion engine:
         * - Removes any existing row with the same `id` (promoting Draft -> Saved without duplicates)
         *   OR matching saved output `filePath` (when saving over an existing exported file path).
         * - Inserts `incoming` at index 0 sorted by latest timestamp.
         * Returns `(updatedList, removedOrPrunedEntries)`.
         */
        fun mergeHistoryEntry(
            current: List<CleanedFileHistory>,
            incoming: CleanedFileHistory,
            maxItems: Int = MAX_ITEMS
        ): Pair<List<CleanedFileHistory>, List<CleanedFileHistory>> {
            val mutable = current.toMutableList()
            val removed = mutableListOf<CleanedFileHistory>()

            val iterator = mutable.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                val sameId = existing.id == incoming.id
                val sameSavedPath = incoming.status == FileRecordStatus.SAVED &&
                    incoming.filePath.isNotBlank() &&
                    existing.filePath == incoming.filePath
                if (sameId || sameSavedPath) {
                    removed.add(existing)
                    iterator.remove()
                }
            }

            mutable.add(0, incoming)
            val sorted = mutable.sortedByDescending { it.timestampMillis }
            val trimmed = sorted.take(maxItems)
            if (sorted.size > maxItems) {
                removed.addAll(sorted.drop(maxItems))
            }
            return trimmed to removed
        }

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
