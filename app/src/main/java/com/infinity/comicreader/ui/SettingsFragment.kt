package com.infinity.comicreader.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.util.Locales

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        val prefs = Prefs(requireContext())
        val library = Library(requireContext())

        findPreference<Preference>("pref_theme")?.setOnPreferenceChangeListener { _, value ->
            val mode = when (value as String) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
            true
        }

        findPreference<Preference>("pref_locale")?.setOnPreferenceChangeListener { _, value ->
            Locales.apply(value as String)
            activity?.recreate()
            true
        }

        findPreference<Preference>("pref_clear_history")?.setOnPreferenceClickListener {
            library.clearHistory()
            toast(R.string.pref_cleared)
            true
        }

        findPreference<Preference>("pref_clear_bookmarks")?.setOnPreferenceClickListener {
            library.clearBookmarks()
            toast(R.string.pref_cleared)
            true
        }

        findPreference<Preference>("pref_clear_thumbs")?.setOnPreferenceClickListener {
            Thumbs.clear(requireContext())
            toast(R.string.pref_cleared_thumbs)
            true
        }

        findPreference<Preference>("pref_reset")?.setOnPreferenceClickListener {
            prefs.reset()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            Locales.apply("system")
            toast(R.string.pref_reset_done)
            activity?.recreate()
            true
        }
    }

    private fun toast(res: Int) =
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
}
