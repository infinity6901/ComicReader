package com.infinity.comicreader.data

import java.io.File

enum class EntryType { FOLDER, IMAGE, ARCHIVE, PDF, OTHER }

/**
 * A single row in the library. [childCount] is used by folders, [pages] holds a
 * known page count, [progressPage] the last read page (-1 when unknown) and the
 * flags mirror the favorites / read-mark stores so the adapter can render badges
 * without touching disk.
 */
data class BookEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val type: EntryType,
    val size: Long,
    val lastModified: Long,
    val childCount: Int = -1,
    val pages: Int = -1,
    val progressPage: Int = -1,
    val favorite: Boolean = false,
    val read: Boolean = false,
    /** For folders: how many direct children are already marked read (-1 = unknown). */
    val readCount: Int = -1,
    val subtitle: String? = null
) {
    val path: String get() = file.absolutePath
}

data class HistoryItem(
    val path: String,
    val name: String,
    val page: Int,
    val total: Int,
    val time: Long
)

data class Bookmark(
    val path: String,
    val name: String,
    val page: Int,
    val note: String,
    val time: Long
)

/** Reader options remembered for one folder ("series"). */
data class SeriesSettings(
    val mode: String,
    val dir: String,
    val fit: String,
    val twoPage: Boolean
)

/** Aggregated reading statistics. */
data class ReadingStats(
    val seconds: Long,
    val pages: Long,
    val chapters: Int,
    val daysRead: Int,
    val streak: Int,
    val bestStreak: Int
)
