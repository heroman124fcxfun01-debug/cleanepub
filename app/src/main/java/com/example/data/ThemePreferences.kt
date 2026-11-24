package com.example.data

import android.content.Context

/**
 * Lightweight SharedPreferences persistence for Light / Dark theme preference.
 */
class ThemePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isDarkMode(): Boolean = prefs.getBoolean(KEY_DARK_MODE, false)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "epub_clean_ai_settings"
        private const val KEY_DARK_MODE = "dark_mode_enabled"
    }
}
