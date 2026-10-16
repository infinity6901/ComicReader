package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.File
import java.io.IOException

/** Reads image pages from a rar/cbr archive (via junrar). */
class RarSource(file: File) : PageSource {
    private val archive = Archive(file)

    private val headers: List<FileHeader> = archive.fileHeaders
        .filter { !it.isDirectory && Formats.isImageName(it.fileName) }
        .sortedWith { a, b -> NaturalOrder.compare(a.fileName, b.fileName) }

    override val title: String = file.name
    override val pageCount: Int get() = headers.size

    /**
     * junrar's [Archive] is not thread-safe; the adapter loads neighbours
     * concurrently, so access is serialized here.
     */
    @Synchronized
    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (headers.isEmpty()) throw IOException("Archive contains no images")
        val header = headers[index]
        try {
            archive.getInputStream(header).use { input ->
                return BitmapUtils.decodeStream(input, reqWidth, reqHeight)
                    ?: throw IOException("Could not decode ${header.fileName}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load rar entry ${header.fileName} (index $index)", t)
            throw t
        }
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (index !in headers.indices) return null
        return runCatching {
            archive.getInputStream(headers[index]).use { BitmapUtils.boundsStream(it) }
        }.getOrNull()
    }

    override fun close() {
        runCatching { archive.close() }
    }

    companion object {
        private const val TAG = "RarSource"
    }
}
