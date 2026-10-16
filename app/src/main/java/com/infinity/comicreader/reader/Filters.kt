package com.infinity.comicreader.reader

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorFilter

/** Named colour filters applied to rendered pages. */
object Filters {
    const val NONE = "none"
    const val INVERT = "invert"
    const val GRAYSCALE = "grayscale"
    const val CONTRAST = "contrast"
    const val SEPIA = "sepia"

    fun colorFilter(name: String): ColorFilter? = when (name) {
        INVERT -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        GRAYSCALE -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

        CONTRAST -> {
            val c = 1.35f
            val t = (1f - c) * 127.5f + 8f
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        SEPIA -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        else -> null
    }
}
