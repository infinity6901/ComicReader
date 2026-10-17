package com.infinity.comicreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/**
 * Favorites, read marks, reading history, bookmarks, per-series settings and
 * reading statistics, persisted as JSON documents in SharedPreferences.
 *
 * Every read tolerates a corrupted document (it yields an empty value instead of
 * throwing) and every read-modify-write runs under one process-wide lock, because
 * the reader, the library and background scans each create their own instance.
 */
class Library(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- favorites ----------------
    fun favorites(): MutableList<String> = readStringList(KEY_FAVORITES)

    fun favoriteSet(): Set<String> = favorites().toHashSet()

    fun isFavorite(path: String): Boolean = favorites().contains(path)

    /** Returns true when the path was added, false when it was removed. */
    fun toggleFavorite(path: String): Boolean = synchronized(LOCK) {
        val list = favorites()
        val added = if (list.contains(path)) {
            list.remove(path)
            false
        } else {
            list.add(0, path)
            true
        }
        writeStringList(KEY_FAVORITES, list)
        added
    }

    // ---------------- read marks ----------------
    fun readSet(): MutableSet<String> = readStringList(KEY_READ).toHashSet()

    fun isRead(path: String): Boolean = readSet().contains(path)

    fun setRead(path: String, read: Boolean) = setReadMany(listOf(path), read)

    fun setReadMany(paths: Collection<String>, read: Boolean) {
        synchronized(LOCK) {
            val set = readSet()
            val changed = if (read) set.addAll(paths) else set.removeAll(paths.toSet())
            if (changed) writeStringList(KEY_READ, set.toList())
        }
    }

    // ---------------- history ----------------
    fun history(): MutableList<HistoryItem> {
        val arr = readArray(KEY_HISTORY)
        val out = ArrayList<HistoryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                HistoryItem(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    page = o.optInt("page"),
                    total = o.optInt("total"),
                    time = o.optLong("time")
                )
            )
        }
        return out
    }

    fun historyMap(): Map<String, HistoryItem> = history().associateBy { it.path }

    fun historyFor(path: String): HistoryItem? = history().firstOrNull { it.path == path }

    fun pageFor(path: String): Int = historyFor(path)?.page ?: 0

    fun addHistory(item: HistoryItem) {
        synchronized(LOCK) {
            val list = ArrayList(history().filterNot { it.path == item.path })
            list.add(0, item)
            while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
            writeHistory(list)
        }
    }

    fun clearHistory() = sp.edit().putString(KEY_HISTORY, "[]").apply()

    // ---------------- bookmarks ----------------
    fun bookmarks(): MutableList<Bookmark> {
        val arr = readArray(KEY_BOOKMARKS)
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Bookmark(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    page = o.optInt("page"),
                    note = o.optString("note"),
                    time = o.optLong("time")
                )
            )
        }
        return out
    }

    fun bookmarksFor(path: String): List<Bookmark> =
        bookmarks().filter { it.path == path }.sortedBy { it.page }

    fun isBookmarked(path: String, page: Int): Boolean =
        bookmarks().any { it.path == path && it.page == page }

    fun addBookmark(bookmark: Bookmark) {
        synchronized(LOCK) {
            val list = ArrayList(bookmarks().filterNot { it.path == bookmark.path && it.page == bookmark.page })
            list.add(0, bookmark)
            writeBookmarks(list)
        }
    }

    fun removeBookmark(path: String, page: Int) {
        synchronized(LOCK) {
            writeBookmarks(bookmarks().filterNot { it.path == path && it.page == page })
        }
    }

    fun clearBookmarks() = sp.edit().putString(KEY_BOOKMARKS, "[]").apply()

    // ---------------- path maintenance (rename / move / delete) ----------------
    private fun under(path: String, root: String) = path == root || path.startsWith("$root/")

    /** Re-points every stored path after a file or folder was renamed or moved. */
    fun replacePath(old: String, new: String) {
        synchronized(LOCK) {
            fun remap(p: String) = if (under(p, old)) new + p.removePrefix(old) else p
            writeStringList(KEY_FAVORITES, favorites().map { remap(it) })
            writeStringList(KEY_READ, readSet().map { remap(it) })
            val series = readObject(KEY_SERIES)
            val moved = JSONObject()
            series.keys().forEach { k -> moved.put(remap(k), series.get(k)) }
            sp.edit().putString(KEY_SERIES, moved.toString()).apply()
            writeHistory(
                history().map { h ->
                    if (under(h.path, old)) {
                        val np = remap(h.path)
                        h.copy(path = np, name = np.substringAfterLast('/'))
                    } else h
                }
            )
            writeBookmarks(
                bookmarks().map { b ->
                    if (under(b.path, old)) {
                        val np = remap(b.path)
                        b.copy(path = np, name = np.substringAfterLast('/'))
                    } else b
                }
            )
        }
    }

    /** Forgets everything stored for a deleted file or folder. */
    fun removePathsUnder(root: String) {
        synchronized(LOCK) {
            writeStringList(KEY_FAVORITES, favorites().filterNot { under(it, root) })
            writeStringList(KEY_READ, readSet().filterNot { under(it, root) })
            val series = readObject(KEY_SERIES)
            val kept = JSONObject()
            series.keys().forEach { k -> if (!under(k, root)) kept.put(k, series.get(k)) }
            sp.edit().putString(KEY_SERIES, kept.toString()).apply()
            writeHistory(history().filterNot { under(it.path, root) })
            writeBookmarks(bookmarks().filterNot { under(it.path, root) })
        }
    }

    // ---------------- per-series reader settings ----------------
    fun seriesFor(folder: String): SeriesSettings? {
        val o = readObject(KEY_SERIES).optJSONObject(folder) ?: return null
        return SeriesSettings(
            mode = o.optString("mode", "paged"),
            dir = o.optString("dir", "ltr"),
            fit = o.optString("fit", "screen"),
            twoPage = o.optBoolean("two", false)
        )
    }

    /** Saves the settings for [folder], or forgets them when [settings] is null. */
    fun setSeries(folder: String, settings: SeriesSettings?) {
        synchronized(LOCK) {
            val all = readObject(KEY_SERIES)
            if (settings == null) {
                all.remove(folder)
            } else {
                all.put(
                    folder,
                    JSONObject().put("mode", settings.mode).put("dir", settings.dir)
                        .put("fit", settings.fit).put("two", settings.twoPage)
                )
            }
            sp.edit().putString(KEY_SERIES, all.toString()).apply()
        }
    }

    // ---------------- reading statistics ----------------
    /** Adds [seconds] of reading time and [pages] newly seen pages to today's totals. */
    fun addReading(seconds: Long, pages: Int) {
        synchronized(LOCK) {
            if (seconds <= 0 && pages <= 0) return@synchronized
            val o = readObject(KEY_STATS)
            o.put("seconds", o.optLong("seconds") + seconds.coerceAtLeast(0))
            o.put("pages", o.optLong("pages") + pages.coerceAtLeast(0))
            val days = o.optJSONArray("days") ?: JSONArray()
            val today = LocalDate.now().toString()
            var has = false
            for (i in 0 until days.length()) if (days.optString(i) == today) has = true
            if (!has) days.put(today)
            // Keep roughly ten years of days.
            val trimmed = JSONArray()
            for (i in (days.length() - MAX_DAYS).coerceAtLeast(0) until days.length()) trimmed.put(days.get(i))
            o.put("days", trimmed)
            sp.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    fun stats(): ReadingStats {
        val o = readObject(KEY_STATS)
        val days = HashSet<LocalDate>()
        o.optJSONArray("days")?.let { arr ->
            for (i in 0 until arr.length()) {
                runCatching { days.add(LocalDate.parse(arr.getString(i))) }
            }
        }
        // Current streak: consecutive days ending today (or yesterday if not read yet today).
        var streak = 0
        var d = LocalDate.now()
        if (d !in days) d = d.minusDays(1)
        while (d in days) {
            streak++
            d = d.minusDays(1)
        }
        // Best streak over all days.
        var best = 0
        for (day in days) {
            if (day.minusDays(1) in days) continue // not the start of a run
            var len = 0
            var cur = day
            while (cur in days) {
                len++
                cur = cur.plusDays(1)
            }
            if (len > best) best = len
        }
        return ReadingStats(
            seconds = o.optLong("seconds"),
            pages = o.optLong("pages"),
            chapters = readSet().size,
            daysRead = days.size,
            streak = streak,
            bestStreak = best
        )
    }

    fun clearStats() = sp.edit().putString(KEY_STATS, "{}").apply()

    // ---------------- backup ----------------
    /** The stored documents, keyed by name, as parsed JSON (for backup). */
    fun exportAll(): JSONObject {
        val out = JSONObject()
        for (k in ARRAY_KEYS) out.put(k, readArray(k))
        for (k in OBJECT_KEYS) out.put(k, readObject(k))
        return out
    }

    /**
     * Replaces the stored documents with those in [data]. Only known keys of the
     * right JSON type are taken; everything else is ignored.
     */
    fun importAll(data: JSONObject) {
        synchronized(LOCK) {
            val e = sp.edit()
            for (k in ARRAY_KEYS) data.optJSONArray(k)?.let { e.putString(k, sanitizeArray(k, it).toString()) }
            for (k in OBJECT_KEYS) data.optJSONObject(k)?.let { e.putString(k, it.toString()) }
            e.apply()
        }
    }

    /** Drops malformed rows so a hand-edited backup cannot break the lists later. */
    private fun sanitizeArray(key: String, arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            when (key) {
                KEY_FAVORITES, KEY_READ -> arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { out.put(it) }
                else -> arr.optJSONObject(i)?.takeIf { it.optString("path").isNotEmpty() }?.let { out.put(it) }
            }
        }
        return out
    }

    // ---------------- helpers ----------------
    private fun readArray(key: String): JSONArray =
        try {
            JSONArray(sp.getString(key, "[]") ?: "[]")
        } catch (e: JSONException) {
            JSONArray()
        }

    private fun readObject(key: String): JSONObject =
        try {
            JSONObject(sp.getString(key, "{}") ?: "{}")
        } catch (e: JSONException) {
            JSONObject()
        }

    private fun readStringList(key: String): MutableList<String> {
        val arr = readArray(key)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun writeStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun writeHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("path", h.path).put("name", h.name)
                    .put("page", h.page).put("total", h.total).put("time", h.time)
            )
        }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun writeBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject().put("path", b.path).put("name", b.name)
                    .put("page", b.page).put("note", b.note).put("time", b.time)
            )
        }
        sp.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    private companion object {
        const val FILE = "library"
        const val KEY_FAVORITES = "favorites"
        const val KEY_READ = "read"
        const val KEY_HISTORY = "history"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_SERIES = "series"
        const val KEY_STATS = "stats"
        const val MAX_HISTORY = 200
        const val MAX_DAYS = 3650
        val ARRAY_KEYS = listOf(KEY_FAVORITES, KEY_READ, KEY_HISTORY, KEY_BOOKMARKS)
        val OBJECT_KEYS = listOf(KEY_SERIES, KEY_STATS)
        val LOCK = Any()
    }
}
