package com.infinity.comicreader.util

import android.content.Context
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object FileUtils {
    fun formatSize(context: Context, bytes: Long): String =
        if (bytes <= 0) "0 B" else Formatter.formatShortFileSize(context, bytes)
}

object Locales {
    /** Apply an app-wide locale using AppCompat's per-app language support. */
    fun apply(tag: String) {
        val locales = if (tag.isBlank() || tag == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
