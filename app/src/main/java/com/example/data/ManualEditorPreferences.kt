package com.example.data

import android.content.Context
import com.example.pipeline.ManualReadingMode

/**
 * SharedPreferences persistence for ManualEditorScreen preferences:
 * - "Không hỏi lại" (skip Undo confirmation dialog)
 * - Reading page-turn mode (Cuộn dọc vs Lướt ngang)
 */
class ManualEditorPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isSkipUndoConfirm(): Boolean = prefs.getBoolean(KEY_SKIP_UNDO_CONFIRM, false)

    fun setSkipUndoConfirm(skip: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_UNDO_CONFIRM, skip).apply()
    }

    fun getReadingMode(): ManualReadingMode {
        val raw = prefs.getString(KEY_READING_MODE, ManualReadingMode.VERTICAL_SCROLL.name)
        return runCatching { ManualReadingMode.valueOf(raw ?: "") }
            .getOrDefault(ManualReadingMode.VERTICAL_SCROLL)
    }

    fun setReadingMode(mode: ManualReadingMode) {
        prefs.edit().putString(KEY_READING_MODE, mode.name).apply()
    }

    fun isAutoSaveDraft(): Boolean = prefs.getBoolean(KEY_AUTO_SAVE_DRAFT, false)

    fun setAutoSaveDraft(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SAVE_DRAFT, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "epub_clean_manual_editor_prefs"
        private const val KEY_SKIP_UNDO_CONFIRM = "skip_undo_confirm"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_AUTO_SAVE_DRAFT = "auto_save_draft"
    }
}
