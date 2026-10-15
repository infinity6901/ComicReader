package com.infinity.comicreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favorites, reading history and bookmarks, persisted as JSON in SharedPreferences. */
class Library(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---------------- favorites ----------------
    fun favorites(): MutableList<String> = readStringList("favorites")

    fun isFavorite(path: String): Boolean = favorites().contains(path)

    /** Returns true when the path was added, false when it was removed. */
    fun toggleFavorite(path: String): Boolean {
        val list = favorites()
        val added = if (list.contains(path)) {
            list.remove(path); false
        } else {
            list.add(0, path); true
        }
        writeStringList("favorites", list)
        return added
    }

    fun removeFavorite(path: String) {
        val list = favorites(); list.remove(path); writeStringList("favorites", list)
    }

    fun clearFavorites() = sp.edit().putString("favorites", "[]").apply()

    // ---------------- history ----------------
    fun history(): MutableList<HistoryItem> {
        val arr = JSONArray(sp.getString("history", "[]"))
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

    fun addHistory(item: HistoryItem) {
        val list = ArrayList(history().filterNot { it.path == item.path })
        list.add(0, item)
        while (list.size > 200) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("path", h.path).put("name", h.name)
                    .put("page", h.page).put("total", h.total).put("time", h.time)
            )
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun pageFor(path: String): Int = history().firstOrNull { it.path == path }?.page ?: 0

    fun removeHistory(path: String) {
        val arr = JSONArray()
        history().filterNot { it.path == path }.forEach { h ->
            arr.put(JSONObject().put("path", h.path).put("name", h.name)
                .put("page", h.page).put("total", h.total).put("time", h.time))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() = sp.edit().putString("history", "[]").apply()

    // ---------------- bookmarks ----------------
    fun bookmarks(): MutableList<Bookmark> {
        val arr = JSONArray(sp.getString("bookmarks", "[]"))
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
        val list = ArrayList(bookmarks().filterNot { it.path == bookmark.path && it.page == bookmark.page })
        list.add(0, bookmark)
        writeBookmarks(list)
    }

    fun removeBookmark(path: String, page: Int) {
        writeBookmarks(bookmarks().filterNot { it.path == path && it.page == page })
    }

    fun clearBookmarks() = sp.edit().putString("bookmarks", "[]").apply()

    // ---------------- helpers ----------------
    private fun readStringList(key: String): MutableList<String> {
        val arr = JSONArray(sp.getString(key, "[]"))
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    private fun writeStringList(key: String, list: List<String>) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun writeBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("path", b.path).put("name", b.name)
                .put("page", b.page).put("note", b.note).put("time", b.time))
        }
        sp.edit().putString("bookmarks", arr.toString()).apply()
    }
}
