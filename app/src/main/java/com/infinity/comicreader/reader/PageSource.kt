package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.Closeable

/** A random-access sequence of pages that can each be rendered to a [Bitmap]. */
interface PageSource : Closeable {
    val title: String
    val pageCount: Int

    /**
     * Render page [index] at up to approximately [reqWidth] x [reqHeight] pixels.
     * Implementations may return a smaller image; callers must not assume exact size.
     */
    fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap

    /**
     * Intrinsic `[width, height]` of page [index] without decoding its pixels, or
     * null when it cannot be determined cheaply. Used to lay out continuous long
     * strips so pages do not resize as they load.
     */
    fun pageSize(index: Int): IntArray? = null
}
