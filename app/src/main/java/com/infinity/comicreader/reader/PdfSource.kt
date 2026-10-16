package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/** Renders PDF pages with the platform [PdfRenderer]. */
class PdfSource(file: File) : PageSource {
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    override val title: String = file.name
    override val pageCount: Int get() = renderer.pageCount

    /**
     * [PdfRenderer] allows only one open [PdfRenderer.Page] at a time and is not
     * safe for concurrent use; the adapter loads neighbours concurrently, so
     * access is serialized here.
     */
    @Synchronized
    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        try {
            val page = renderer.openPage(index)
            try {
                val targetW = (if (reqWidth > 0) reqWidth else page.width * 2).coerceIn(360, 3000)
                val ratio = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.4f
                val w = targetW
                val h = (w * ratio).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } finally {
                page.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to render PDF page $index", t)
            throw t
        }
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (index < 0 || index >= renderer.pageCount) return null
        return runCatching {
            val page = renderer.openPage(index)
            try {
                intArrayOf(page.width, page.height)
            } finally {
                page.close()
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val TAG = "PdfSource"
    }
}
