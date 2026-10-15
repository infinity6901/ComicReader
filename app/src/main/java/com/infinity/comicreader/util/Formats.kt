package com.infinity.comicreader.util

import com.infinity.comicreader.data.EntryType

/**
 * Recognised formats. Mirrors the format set used by the reference application:
 * images (jpg/jpeg/png/gif/bmp/webp/avif) and the archives zip/cbz, rar/cbr,
 * 7z/cb7, plus pdf.
 */
object Formats {
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heic", "heif")
    val ZIP = setOf("zip", "cbz")
    val RAR = setOf("rar", "cbr")
    val SEVEN_ZIP = setOf("7z", "cb7")
    val ARCHIVE = ZIP + RAR + SEVEN_ZIP
    val PDF = setOf("pdf")

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i < 0) "" else name.substring(i + 1).lowercase()
    }

    fun typeOf(name: String): EntryType {
        val e = ext(name)
        return when {
            e in IMAGE -> EntryType.IMAGE
            e in ARCHIVE -> EntryType.ARCHIVE
            e in PDF -> EntryType.PDF
            else -> EntryType.OTHER
        }
    }

    fun isImageName(name: String): Boolean = ext(name) in IMAGE

    fun isOpenable(name: String): Boolean = typeOf(name) != EntryType.OTHER
}
