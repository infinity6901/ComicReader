package com.infinity.comicreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Locales
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        Locales.apply(prefs.localeTag)
        cleanCaches()
    }

    /** Drops temporary files left by earlier runs and keeps the cover cache bounded. */
    private fun cleanCaches() {
        thread(name = "cache-cleanup", isDaemon = true) {
            runCatching {
                // Only files that are clearly stale: a document another app just handed us
                // may be copied into the cache while this thread runs.
                val cutoff = System.currentTimeMillis() - STALE_MS
                for (dir in TEMP_DIRS) {
                    File(cacheDir, dir).listFiles()?.filter { it.lastModified() < cutoff }
                        ?.forEach { FileUtils.deleteTree(it) }
                }
                File(cacheDir, "crop_src.png").takeIf { it.lastModified() < cutoff }?.delete()
                Thumbs.trim(this)
            }
        }
    }

    private companion object {
        /** Files shared out of the reader and documents copied in from other apps. */
        val TEMP_DIRS = listOf("shared", "incoming", "pictures", "cropped")
        const val STALE_MS = 10L * 60 * 1000
    }
}
