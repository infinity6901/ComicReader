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
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (t: Throwable) {
        // Encrypted or corrupt PDF: do not leak the descriptor.
        runCatching { pfd.close() }
        throw t
    }

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
                val ratio = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.4f
                // Callers pass 1 for the dimension they do not constrain, so derive the
                // width from whichever limits are real (fit-height requests only set the height).
                val fromWidth = if (reqWidth > 1) reqWidth else 0
                val fromHeight = if (reqHeight > 1) (reqHeight / ratio).toInt() else 0
                val wanted = when {
                    fromWidth > 0 && fromHeight > 0 -> minOf(fromWidth, fromHeight)
                    fromWidth > 0 -> fromWidth
                    fromHeight > 0 -> fromHeight
                    else -> page.width * 2
                }
                val w = wanted.coerceIn(360, MAX_WIDTH)
                val h = (w * ratio).toInt().coerceIn(1, MAX_HEIGHT)
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
        private const val MAX_WIDTH = 3000
        private const val MAX_HEIGHT = 8000
    }
}
