package com.infinity.comicreader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Central settings access. Everything that must survive across chapters, screens
 * and app launches lives here: the settings screen writes the `pref_*` keys and
 * the reader writes back the values the user changes while reading, so reader
 * state is global and persistent rather than per-activity.
 *
 * [ui] holds small transient browsing state (last folder, active tab).
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val ui: SharedPreferences = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun integer(key: String, def: Int): Int = sp.getInt(key, def)
    private fun put(key: String, value: Any?) {
        val e = sp.edit()
        when (value) {
            is String -> e.putString(key, value)
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            else -> e.remove(key)
        }
        e.apply()
    }

    // ---------------------------------------------------------------- general

    var theme: String
        get() = str(KEY_THEME, "system")
        set(v) = put(KEY_THEME, v)

    var localeTag: String
        get() = str(KEY_LOCALE, "system")
        set(v) = put(KEY_LOCALE, v)

    var charset: String
        get() = str(KEY_CHARSET, "UTF-8")
        set(v) = put(KEY_CHARSET, v)

    // ---------------------------------------------------------------- reader

    var fullscreen: Boolean
        get() = bool(KEY_FULLSCREEN, true)
        set(v) = put(KEY_FULLSCREEN, v)

    var keepScreenOn: Boolean
        get() = bool(KEY_KEEP_ON, false)
        set(v) = put(KEY_KEEP_ON, v)

    var readingDir: String
        get() = str(KEY_READING_DIR, "ltr")
        set(v) = put(KEY_READING_DIR, v)

    /** paged | vertical | longstrip */
    var pageMode: String
        get() = str(KEY_PAGE_MODE, "paged")
        set(v) = put(KEY_PAGE_MODE, v)

    /** screen | width | height | stretch */
    var fit: String
        get() = str(KEY_FIT, "screen")
        set(v) = put(KEY_FIT, v)

    /** none | invert | grayscale | contrast | sepia */
    var filter: String
        get() = str(KEY_FILTER, "none")
        set(v) = put(KEY_FILTER, v)

    var brightness: Int
        get() = integer(KEY_BRIGHTNESS, 100).coerceIn(20, 100)
        set(v) = put(KEY_BRIGHTNESS, v.coerceIn(20, 100))

    var pageSpacing: Int
        get() = integer(KEY_SPACING, 0).coerceIn(0, 48)
        set(v) = put(KEY_SPACING, v.coerceIn(0, 48))

    var autoNext: Boolean
        get() = bool(KEY_AUTO_NEXT, false)
        set(v) = put(KEY_AUTO_NEXT, v)

    var resume: Boolean
        get() = bool(KEY_RESUME, true)
        set(v) = put(KEY_RESUME, v)

    /** Ask whether to resume or start from the first page when a chapter has saved progress. */
    var askResume: Boolean
        get() = bool(KEY_ASK_RESUME, true)
        set(v) = put(KEY_ASK_RESUME, v)

    var twoPage: Boolean
        get() = bool(KEY_TWO_PAGE, false)
        set(v) = put(KEY_TWO_PAGE, v)

    var nightFilter: Boolean
        get() = bool(KEY_NIGHT, false)
        set(v) = put(KEY_NIGHT, v)

    /** Trim blank borders from pages in paged and vertical modes. */
    var autoCrop: Boolean
        get() = bool(KEY_AUTO_CROP, false)
        set(v) = put(KEY_AUTO_CROP, v)

    /** How many pages ahead to decode (0 = off). Stored as a string by the list preference. */
    var preload: Int
        get() = str(KEY_PRELOAD, "2").toIntOrNull()?.coerceIn(0, 6) ?: 2
        set(v) = put(KEY_PRELOAD, v.coerceIn(0, 6).toString())

    /** Auto-scroll speed, 1 (slow) .. 10 (fast). */
    var autoSpeed: Int
        get() = integer(KEY_AUTO_SPEED, 5).coerceIn(1, 10)
        set(v) = put(KEY_AUTO_SPEED, v.coerceIn(1, 10))

    /** Let the reader remember mode / direction / fit / spread per folder. */
    var perSeries: Boolean
        get() = bool(KEY_PER_SERIES, true)
        set(v) = put(KEY_PER_SERIES, v)

    // ---------------------------------------------------------------- gestures

    var tapZones: Boolean
        get() = bool(KEY_TAP_ZONES, true)
        set(v) = put(KEY_TAP_ZONES, v)

    var volumeKeys: Boolean
        get() = bool(KEY_VOLUME_KEYS, false)
        set(v) = put(KEY_VOLUME_KEYS, v)

    var brightnessGesture: Boolean
        get() = bool(KEY_BRIGHTNESS_GESTURE, true)
        set(v) = put(KEY_BRIGHTNESS_GESTURE, v)

    var doubleTapZoom: Boolean
        get() = bool(KEY_DOUBLE_TAP, true)
        set(v) = put(KEY_DOUBLE_TAP, v)

    // ---------------------------------------------------------------- library

    var showHidden: Boolean
        get() = bool(KEY_SHOW_HIDDEN, false)
        set(v) = put(KEY_SHOW_HIDDEN, v)

    var thumbnails: Boolean
        get() = bool(KEY_THUMBS, true)
        set(v) = put(KEY_THUMBS, v)

    var listGrid: Boolean
        get() = bool(KEY_GRID, false)
        set(v) = put(KEY_GRID, v)

    /** 0 = auto */
    var gridColumns: Int
        get() = str(KEY_COLUMNS, "0").toIntOrNull()?.coerceIn(0, 6) ?: 0
        set(v) = put(KEY_COLUMNS, v.toString())

    var listSort: String
        get() = str(KEY_SORT, "name")
        set(v) = put(KEY_SORT, v)

    var listSortDir: String
        get() = str(KEY_SORT_DIR, "asc")
        set(v) = put(KEY_SORT_DIR, v)

    var showFilenames: Boolean
        get() = bool(KEY_FILENAMES, true)
        set(v) = put(KEY_FILENAMES, v)

    var listPathView: Boolean
        get() = bool(KEY_PATHVIEW, true)
        set(v) = put(KEY_PATHVIEW, v)

    var listStatusBar: Boolean
        get() = bool(KEY_LIST_STATUSBAR, true)
        set(v) = put(KEY_LIST_STATUSBAR, v)

    var listQuickFab: Boolean
        get() = bool(KEY_QUICK_FAB, true)
        set(v) = put(KEY_QUICK_FAB, v)

    // ---------------------------------------------------------------- ui state

    var lastPath: String?
        get() = ui.getString("last_path", null)
        set(v) = ui.edit().putString("last_path", v).apply()

    var homePath: String?
        get() = ui.getString("home_path", null)
        set(v) = ui.edit().putString("home_path", v).apply()

    /** Search below the current folder, not only in it. */
    var deepSearch: Boolean
        get() = ui.getBoolean("deep_search", false)
        set(v) = ui.edit().putBoolean("deep_search", v).apply()

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

    /** Every stored `pref_*` value of a simple type, for backup. */
    fun exportAll(): Map<String, Any> =
        sp.all.filter { (k, v) -> k in KNOWN_KEYS && (v is String || v is Boolean || v is Int) }
            .mapValues { it.value as Any }

    /** Restores values written by [exportAll]; unknown keys and odd types are ignored. */
    fun importAll(values: Map<String, Any>) {
        val e = sp.edit()
        for ((k, v) in values) {
            if (k !in KNOWN_KEYS) continue
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
            }
        }
        e.apply()
    }

    companion object {
        private const val KEY_THEME = "pref_theme"
        private const val KEY_LOCALE = "pref_locale"
        private const val KEY_CHARSET = "pref_charset"
        private const val KEY_FULLSCREEN = "pref_fullscreen"
        private const val KEY_KEEP_ON = "pref_keep_screen_on"
        private const val KEY_READING_DIR = "pref_reading_dir"
        private const val KEY_PAGE_MODE = "pref_page_mode"
        private const val KEY_FIT = "pref_fit"
        private const val KEY_FILTER = "pref_filter"
        private const val KEY_BRIGHTNESS = "pref_brightness"
        private const val KEY_SPACING = "pref_page_spacing"
        private const val KEY_AUTO_NEXT = "pref_auto_next"
        private const val KEY_RESUME = "pref_resume"
        private const val KEY_ASK_RESUME = "pref_ask_resume"
        private const val KEY_TWO_PAGE = "pref_two_page"
        private const val KEY_NIGHT = "pref_night"
        private const val KEY_TAP_ZONES = "pref_tap_zones"
        private const val KEY_VOLUME_KEYS = "pref_volume_keys"
        private const val KEY_BRIGHTNESS_GESTURE = "pref_brightness_gesture"
        private const val KEY_DOUBLE_TAP = "pref_double_tap"
        private const val KEY_SHOW_HIDDEN = "pref_show_hidden"
        private const val KEY_THUMBS = "pref_thumbs"
        private const val KEY_GRID = "pref_grid"
        private const val KEY_COLUMNS = "pref_grid_columns"
        private const val KEY_SORT = "pref_sort"
        private const val KEY_SORT_DIR = "pref_sort_dir"
        private const val KEY_FILENAMES = "pref_show_filenames"
        private const val KEY_PATHVIEW = "pref_list_pathview"
        private const val KEY_LIST_STATUSBAR = "pref_list_statusbar"
        private const val KEY_QUICK_FAB = "pref_list_quickfab"
        private const val KEY_AUTO_CROP = "pref_auto_crop"
        private const val KEY_PRELOAD = "pref_preload"
        private const val KEY_AUTO_SPEED = "pref_auto_speed"
        private const val KEY_PER_SERIES = "pref_per_series"

        private val KNOWN_KEYS = setOf(
            KEY_THEME, KEY_LOCALE, KEY_CHARSET, KEY_FULLSCREEN, KEY_KEEP_ON, KEY_READING_DIR,
            KEY_PAGE_MODE, KEY_FIT, KEY_FILTER, KEY_BRIGHTNESS, KEY_SPACING, KEY_AUTO_NEXT,
            KEY_RESUME, KEY_ASK_RESUME, KEY_TWO_PAGE, KEY_NIGHT, KEY_TAP_ZONES, KEY_VOLUME_KEYS,
            KEY_BRIGHTNESS_GESTURE, KEY_DOUBLE_TAP, KEY_SHOW_HIDDEN, KEY_THUMBS, KEY_GRID,
            KEY_COLUMNS, KEY_SORT, KEY_SORT_DIR, KEY_FILENAMES, KEY_PATHVIEW, KEY_LIST_STATUSBAR,
            KEY_QUICK_FAB, KEY_AUTO_CROP, KEY_PRELOAD, KEY_AUTO_SPEED, KEY_PER_SERIES
        )
    }
}
