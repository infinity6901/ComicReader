package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.File
import java.io.IOException

/** A single image file is a one-page book. */
class ImageSource(private val file: File) : PageSource {
    override val title: String get() = file.name
    override val pageCount: Int get() = 1

    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap =
        BitmapUtils.decodeFile(file.absolutePath, reqWidth, reqHeight)
            ?: throw IOException("Could not decode image: ${file.name}")

    override fun pageSize(index: Int): IntArray? = BitmapUtils.boundsFile(file.absolutePath)

    override fun close() {
        // Intentionally empty: pages are decoded on demand and no file handle is kept open.
    }
}
