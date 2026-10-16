package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Bitmap decoding helpers. All decoders honour [reqW] x [reqH] by picking a power
 * of two [BitmapFactory.Options.inSampleSize] and additionally clamp the result to
 * [capPixels] so a pathological (e.g. extremely tall) page can never allocate an
 * outsized bitmap.
 */
object BitmapUtils {
    private const val MAX_DIM = 4096
    private const val DEFAULT_CAP_PIXELS = 12_000_000L

    fun decodeFile(path: String, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH, capPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun decodeStream(input: InputStream, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap? =
        decodeBytes(input.readBytes(), reqW, reqH, capPixels)

    fun decodeBytes(bytes: ByteArray, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH, capPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Intrinsic size of an encoded image without decoding its pixels, or null. */
    fun boundsFile(path: String): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return sizeOf(bounds)
    }

    fun boundsBytes(bytes: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return sizeOf(bounds)
    }

    fun boundsStream(input: InputStream): IntArray? = boundsBytes(input.readBytes())

    private fun sizeOf(bounds: BitmapFactory.Options): IntArray? =
        if (bounds.outWidth > 0 && bounds.outHeight > 0)
            intArrayOf(bounds.outWidth, bounds.outHeight)
        else null

    fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int, capPixels: Long): Int {
        if (w <= 0 || h <= 0) return 1
        val tw = if (reqW > 0) reqW else MAX_DIM
        val th = if (reqH > 0) reqH else MAX_DIM
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        while (w.toLong() * h.toLong() / (sample.toLong() * sample.toLong()) > capPixels) sample *= 2
        return sample
    }
}
