package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

/** Reads image pages from a 7z/cb7 archive (via Apache Commons Compress). */
class SevenZipSource(file: File) : PageSource {
    private val sz: SevenZFile = SevenZFile.builder().setFile(file).get()

    private val entries: List<SevenZArchiveEntry>

    override val title: String = file.name

    init {
        val list = ArrayList<SevenZArchiveEntry>()
        var e = sz.nextEntry
        while (e != null) {
            if (!e.isDirectory && Formats.isImageName(e.name)) list.add(e)
            e = sz.nextEntry
        }
        entries = list.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    override val pageCount: Int get() = entries.size

    /**
     * [SevenZFile] keeps decode state on the instance and is not thread-safe: two
     * concurrent [SevenZFile.getInputStream] reads corrupt each other's stream
     * (observed as `CorruptedInputException`). The [PageAdapter] and [ThumbAdapter]
     * load neighbours concurrently, so every access is serialized here.
     */
    @Synchronized
    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (entries.isEmpty()) throw IOException("Archive contains no images")
        val entry = entries[index]
        try {
            sz.getInputStream(entry).use { input ->
                return BitmapUtils.decodeStream(input, reqWidth, reqHeight)
                    ?: throw IOException("Could not decode ${entry.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load 7z entry ${entry.name} (index $index)", t)
            throw t
        }
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (index !in entries.indices) return null
        return runCatching {
            sz.getInputStream(entries[index]).use { BitmapUtils.boundsStream(it) }
        }.getOrNull()
    }

    override fun close() {
        runCatching { sz.close() }
    }

    companion object {
        private const val TAG = "SevenZipSource"
    }
}
