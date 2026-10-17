package com.infinity.comicreader.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.util.Backup
import com.infinity.comicreader.util.Locales

class SettingsFragment : PreferenceFragmentCompat() {

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = runCatching {
                requireContext().contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(Backup.export(requireContext()).toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            toast(if (ok) R.string.backup_done else R.string.op_failed)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching { readCapped(uri) }.getOrNull()
            if (text != null && Backup.import(requireContext(), text)) {
                // Theme and language come from the restored settings.
                val restored = Prefs(requireContext())
                AppCompatDelegate.setDefaultNightMode(restored.nightMode)
                Locales.apply(restored.localeTag)
                toast(R.string.restore_done)
                activity?.recreate()
            } else {
                toast(R.string.restore_failed)
            }
        }

    /** Reads at most [Backup.MAX_BYTES] from [uri]; a larger file is not a backup. */
    private fun readCapped(uri: android.net.Uri): String? {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > Backup.MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
        return null
    }

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

        findPreference<Preference>("pref_backup")?.setOnPreferenceClickListener {
            exportLauncher.launch("comicreader-backup.json")
            true
        }

        findPreference<Preference>("pref_restore")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.restore_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        findPreference<Preference>("pref_stats")?.setOnPreferenceClickListener {
            val st = library.stats()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_stats)
                .setMessage(
                    getString(
                        R.string.stats_body, st.chapters, st.pages, st.seconds / 3600,
                        (st.seconds % 3600) / 60, st.daysRead, st.streak, st.bestStreak
                    )
                )
                .setPositiveButton(R.string.ok, null)
                .show()
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
