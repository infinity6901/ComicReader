package com.infinity.comicreader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ListActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var adapter: BookAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathbar: android.widget.TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var empty: android.widget.TextView

    private var currentDir: File? = null
    private var mode = MODE_BROWSE
    private var query = ""
    private var resumedOnce = false
    private val allEntries = ArrayList<BookEntry>()
    private val shown = ArrayList<BookEntry>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) openStartDirectory()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        library = Library(this)
        setContentView(R.layout.activity_list)

        toolbar = findViewById(R.id.toolbar)
        pathbar = findViewById(R.id.pathbar)
        recycler = findViewById(R.id.recycler)
        fab = findViewById(R.id.fab)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)

        setSupportActionBar(toolbar)
        toolbar.setTitle(R.string.app_name)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { navigateUp() }

        adapter = BookAdapter(
            grid = prefs.listGrid,
            showStar = true,
            onClick = { onEntryClick(it) },
            onLongClick = { onEntryLongClick(it) },
            onStarClick = { toggleFavorite(it.file.absolutePath) }
        )
        recycler.adapter = adapter
        applyLayoutManager()

        fab.setOnClickListener { toggleFavorite(currentDir?.absolutePath) }
        fab.visibility = if (prefs.listQuickFab) View.VISIBLE else View.GONE
        pathbar.visibility = if (prefs.listPathView) View.VISIBLE else View.GONE

        intent.data?.path?.let { handleIncomingFile(File(it)) }

        if (!hasStorageAccess()) {
            requestStorageAccess()
        } else {
            openStartDirectory()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasStorageAccess() || mode != MODE_BROWSE) return
        if (!resumedOnce) {
            // First resume: onCreate has usually already loaded a folder; if it
            // only prompted for access, populate a default folder now.
            resumedOnce = true
            if (currentDir == null) openStartDirectory()
            return
        }
        // Refresh the current folder so externally added/removed files appear
        // when returning from the viewer or other activities.
        currentDir?.let { loadDirectory(it) }
    }

    // ------------------------------------------------------------------ storage

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setMessage(R.string.all_files_access)
                .setPositiveButton(R.string.grant_access) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    private fun openStartDirectory() {
        val start = prefs.homePath?.let { File(it) }?.takeIf { it.isDirectory }
            ?: prefs.lastPath?.let { File(it) }?.takeIf { it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
        loadDirectory(start)
    }

    // ------------------------------------------------------------------ listing

    private fun loadDirectory(dir: File) {
        mode = MODE_BROWSE
        currentDir = dir
        prefs.lastPath = dir.absolutePath
        // When returning to Browse mode (e.g. from History/Favorites), reset
        // the toolbar title to the directory name/path context. The storage
        // root's own name is a bare user id ("0"), so show the app name there.
        val atStorageRoot = dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath
        supportActionBar?.title = if (atStorageRoot || dir.name.isBlank())
            getString(R.string.app_name) else dir.name
        pathbar.text = dir.absolutePath
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        invalidateOptionsMenu()

        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { buildEntries(dir) }
            allEntries.clear()
            allEntries.addAll(list)
            progress.visibility = View.GONE
            applyFilterAndSort()
        }
    }

    private fun buildEntries(dir: File): List<BookEntry> {
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<BookEntry>(files.size)
        for (f in files) {
            if (f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            val type = if (isDir) com.infinity.comicreader.data.EntryType.FOLDER
            else Formats.typeOf(f.name)
            if (!isDir && type == com.infinity.comicreader.data.EntryType.OTHER) continue
            out.add(
                BookEntry(
                    file = f,
                    name = f.name,
                    isDir = isDir,
                    type = type,
                    size = if (isDir) 0 else f.length(),
                    lastModified = f.lastModified()
                )
            )
        }
        return out
    }

    private fun applyFilterAndSort() {
        shown.clear()
        val filtered = if (query.isBlank()) allEntries
        else allEntries.filter { it.name.contains(query, ignoreCase = true) }

        val comparator: Comparator<BookEntry> = when (prefs.listSort) {
            "size" -> compareByDescending<BookEntry> { it.isDir }.thenByDescending { it.size }
            "date" -> compareByDescending<BookEntry> { it.isDir }.thenByDescending { it.lastModified }
            "type" -> compareByDescending<BookEntry> { it.isDir }
                .thenBy { it.type.ordinal }
                .thenBy(NaturalOrder) { it.name }
            else -> compareByDescending<BookEntry> { it.isDir }.thenBy(NaturalOrder) { it.name }
        }
        shown.addAll(filtered.sortedWith(comparator))
        adapter.submit(shown)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (query.isNotBlank()) R.string.no_history else R.string.empty_folder)
    }

    private fun showFavorites() {
        mode = MODE_FAVORITES
        currentDir = null
        invalidateOptionsMenu()
        val list = library.favorites().map { path ->
            val f = File(path)
            BookEntry(
                file = f, name = f.name, isDir = f.isDirectory,
                type = if (f.isDirectory) com.infinity.comicreader.data.EntryType.FOLDER
                else Formats.typeOf(f.name),
                size = if (f.isFile) f.length() else 0,
                lastModified = f.lastModified()
            )
        }
        allEntries.clear(); allEntries.addAll(list); query = ""
        applyFilterAndSort()
        supportActionBar?.title = getString(R.string.menu_favorites)
        pathbar.text = getString(R.string.menu_favorites)
    }

    private fun showHistory() {
        mode = MODE_HISTORY
        currentDir = null
        invalidateOptionsMenu()
        val list = library.history().map { h ->
            BookEntry(
                file = File(h.path), name = h.name, isDir = false,
                type = Formats.typeOf(h.name),
                size = if (File(h.path).isFile) File(h.path).length() else 0,
                lastModified = h.time, childCount = h.page
            )
        }
        allEntries.clear(); allEntries.addAll(list); query = ""
        applyFilterAndSort()
        supportActionBar?.title = getString(R.string.menu_history)
        pathbar.text = getString(R.string.menu_history)
    }

    // ------------------------------------------------------------------ actions

    private fun onEntryClick(entry: BookEntry) {
        if (entry.isDir) {
            loadDirectory(entry.file)
        } else if (Formats.isOpenable(entry.name)) {
            val page = if (mode == MODE_HISTORY) library.pageFor(entry.file.absolutePath) else 0
            openViewer(entry.file, page)
        } else {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onEntryLongClick(entry: BookEntry) {
        val path = entry.file.absolutePath
        val favorite = library.isFavorite(path)
        val items = ArrayList<String>()
        items.add(if (favorite) getString(R.string.menu_remove_favorite) else getString(R.string.menu_add_favorite))
        items.add(getString(R.string.menu_set_home))
        if (mode == MODE_HISTORY) items.add(getString(R.string.delete))

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.menu_add_favorite),
                    getString(R.string.menu_remove_favorite) -> toggleFavorite(path)
                    getString(R.string.menu_set_home) -> prefs.homePath = path
                    getString(R.string.delete) -> {
                        library.removeHistory(path); showHistory()
                    }
                }
            }
            .show()
    }

    private fun toggleFavorite(path: String?) {
        if (path == null) return
        val added = library.toggleFavorite(path)
        Toast.makeText(
            this,
            if (added) R.string.menu_add_favorite else R.string.menu_remove_favorite,
            Toast.LENGTH_SHORT
        ).show()
        if (mode == MODE_FAVORITES) showFavorites()
    }

    private fun openViewer(file: File, page: Int) {
        val intent = Intent(this, ViewerActivity::class.java)
        intent.putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
        intent.putExtra(ViewerActivity.EXTRA_PAGE, page)
        startActivity(intent)
    }

    private fun handleIncomingFile(file: File) {
        if (file.isFile && Formats.isOpenable(file.name)) {
            openViewer(file, 0)
        } else if (file.isDirectory) {
            loadDirectory(file)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.path?.let { handleIncomingFile(File(it)) }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        val search = menu.findItem(R.id.menu_search)
        val searchView = search.actionView as? SearchView ?: return true
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                query = q.orEmpty(); applyFilterAndSort(); return true
            }

            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty(); applyFilterAndSort(); return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // The three mode items form an exclusive (single-choice) group. Setting
        // isChecked = false on a group item is ignored by the framework
        // (MenuBuilder.setExclusiveItemChecked always forces the passed item to
        // true), so only the current item may be marked checked; the group
        // unchecks the rest automatically.
        val current = when (mode) {
            MODE_FAVORITES -> R.id.menu_favorites
            MODE_HISTORY -> R.id.menu_history
            else -> R.id.menu_browse
        }
        menu.findItem(current).isChecked = true
        menu.findItem(R.id.menu_grid).setTitle(if (prefs.listGrid) R.string.menu_list_view else R.string.menu_grid)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_browse -> {
                currentDir?.let { loadDirectory(it) } ?: openStartDirectory()
            }
            R.id.menu_favorites -> showFavorites()
            R.id.menu_history -> showHistory()
            R.id.menu_grid -> {
                prefs.listGrid = !prefs.listGrid
                adapter.grid = prefs.listGrid
                applyLayoutManager()
                adapter.notifyDataSetChanged()
                invalidateOptionsMenu()
            }
            R.id.menu_sort -> showSortDialog()
            R.id.menu_add_favorite -> toggleFavorite(currentDir?.absolutePath)
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.menu_exit -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showSortDialog() {
        val values = resources.getStringArray(R.array.sort_values)
        val labels = resources.getStringArray(R.array.sort_entries)
        val checked = values.indexOf(prefs.listSort).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.listSort = values[which]
                applyFilterAndSort()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyLayoutManager() {
        recycler.layoutManager = if (prefs.listGrid) {
            GridLayoutManager(this, 3)
        } else {
            LinearLayoutManager(this)
        }
    }

    // ------------------------------------------------------------------ back

    private fun navigateUp() {
        when {
            mode != MODE_BROWSE -> openStartDirectory()
            currentDir != null && currentDir!!.parentFile != null ->
                loadDirectory(currentDir!!.parentFile!!)
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode != MODE_BROWSE || (currentDir != null && currentDir!!.parentFile != null)) {
            navigateUp()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        private const val MODE_BROWSE = 0
        private const val MODE_FAVORITES = 1
        private const val MODE_HISTORY = 2
    }
}
