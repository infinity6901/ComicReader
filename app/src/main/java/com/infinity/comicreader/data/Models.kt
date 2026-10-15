package com.infinity.comicreader.data

import java.io.File

enum class EntryType { FOLDER, IMAGE, ARCHIVE, PDF, OTHER }

data class BookEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val type: EntryType,
    val size: Long,
    val lastModified: Long,
    val childCount: Int = -1
)

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
