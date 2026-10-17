package com.infinity.comicreader.util

import android.content.Context
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.io.File
import java.nio.file.Files

object FileUtils {
    fun formatSize(context: Context, bytes: Long): String =
        if (bytes <= 0) "0 B" else Formatter.formatShortFileSize(context, bytes)

    /**
     * True when [name] is a plain file or folder name: not empty, not "." or "..", and
     * with no path separator, so it can never point outside the folder it is created in.
     */
    fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }

    /**
     * Deletes [file] and everything below it. Symbolic links are removed as links and are
     * never followed, so a link inside the tree cannot cause files elsewhere to be deleted.
     */
    fun deleteTree(file: File): Boolean {
        var ok = true
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            file.listFiles()?.forEach { ok = deleteTree(it) && ok }
        }
        return file.delete() && ok
    }
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
