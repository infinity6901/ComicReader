package com.infinity.comicreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.util.Locales

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        Locales.apply(prefs.localeTag)
    }
}
