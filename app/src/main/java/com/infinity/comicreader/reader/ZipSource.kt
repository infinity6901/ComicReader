package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reads image pages from a zip/cbz archive. */
class ZipSource(file: File, charsetName: String) : PageSource {
    private val zip: ZipFile = runCatching { ZipFile(file, Charset.forName(charsetName)) }
        .getOrElse { ZipFile(file) }

    private val entries: List<ZipEntry> = try {
        zip.entries().asSequence()
            .filter { !it.isDirectory && Formats.isImageName(it.name) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .toList()
    } catch (t: Throwable) {
        runCatching { zip.close() }
        throw t
    }

    override val pageCount: Int get() = entries.size

    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (entries.isEmpty()) throw IOException("Archive contains no images")
        val entry = entries[index]
        zip.getInputStream(entry).use { input ->
            return BitmapUtils.decodeStream(input, reqWidth, reqHeight)
                ?: throw IOException("Could not decode ${entry.name}")
        }
    }

    override fun pageSize(index: Int): IntArray? {
        if (index !in entries.indices) return null
        return runCatching {
            zip.getInputStream(entries[index]).use { BitmapUtils.boundsStream(it) }
        }.getOrNull()
    }

    override fun close() {
        runCatching { zip.close() }
    }
}
