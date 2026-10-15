package com.infinity.comicreader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Wraps the default [SharedPreferences] used by the settings screen, plus a small
 * "ui" store for transient browsing state (grid/list, sort, last path).
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val ui: SharedPreferences = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    val theme: String get() = sp.getString("pref_theme", "system") ?: "system"
    val localeTag: String get() = sp.getString("pref_locale", "system") ?: "system"
    val charset: String get() = sp.getString("pref_charset", "UTF-8") ?: "UTF-8"

    val fullscreen: Boolean get() = sp.getBoolean("pref_fullscreen", true)
    val keepScreenOn: Boolean get() = sp.getBoolean("pref_keep_screen_on", false)
    val readingDir: String get() = sp.getString("pref_reading_dir", "ltr") ?: "ltr"
    val pageMode: String get() = sp.getString("pref_page_mode", "paged") ?: "paged"
    val doubleTapZoom: Boolean get() = sp.getBoolean("pref_double_tap", true)

    val listStatusBar: Boolean get() = sp.getBoolean("pref_list_statusbar", true)
    val listPathView: Boolean get() = sp.getBoolean("pref_list_pathview", true)
    val listQuickFab: Boolean get() = sp.getBoolean("pref_list_quickfab", true)

    // ---- transient UI state ----
    var listGrid: Boolean
        get() = ui.getBoolean("list_grid", false)
        set(v) { ui.edit().putBoolean("list_grid", v).apply() }

    var listSort: String
        get() = ui.getString("list_sort", "name") ?: "name"
        set(v) { ui.edit().putString("list_sort", v).apply() }

    var lastPath: String?
        get() = ui.getString("last_path", null)
        set(v) { ui.edit().putString("last_path", v).apply() }

    var homePath: String?
        get() = ui.getString("home_path", null)
        set(v) { ui.edit().putString("home_path", v).apply() }

    var twoPage: Boolean
        get() = ui.getBoolean("two_page", false)
        set(v) { ui.edit().putBoolean("two_page", v).apply() }

    val nightMode: Int
        get() = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    fun reset() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("pref_") }.forEach { e.remove(it) }
        e.apply()
    }
}
