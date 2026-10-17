package com.infinity.comicreader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ListActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var adapter: BookAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var pathbar: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyText: TextView
    private lateinit var emptySubtitle: TextView

    private var currentDir: File? = null
    private var mode = TAB_BROWSE
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
        tabs = findViewById(R.id.tabs)
        pathbar = findViewById(R.id.pathbar)
        recycler = findViewById(R.id.recycler)
        fab = findViewById(R.id.fab)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty_view)
        emptyIcon = findViewById(R.id.empty_icon)
        emptyText = findViewById(R.id.empty_text)
        emptySubtitle = findViewById(R.id.empty_subtitle)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        toolbar.setTitle(R.string.app_name)

        adapter = BookAdapter(
            grid = prefs.listGrid,
            prefs = prefs,
            onClick = { onEntryClick(it) },
            onLongClick = { onEntryLongClick(it) },
            onStarClick = { toggleFavorite(it.file.absolutePath) }
        )
        recycler.adapter = adapter
        applyLayoutManager()
        recycler.setHasFixedSize(false)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = onTabChanged(tab.position)

            override fun onTabUnselected(tab: TabLayout.Tab) {
                // Intentionally empty: only the newly selected tab needs handling.
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                recycler.scrollToPosition(0)
            }
        })

        fab.setOnClickListener { toggleGrid() }
        updateFabIcon()
        fab.visibility = View.GONE
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
        if (!hasStorageAccess() || mode != TAB_BROWSE) return
        if (!resumedOnce) {
            resumedOnce = true
            if (currentDir == null) openStartDirectory()
            return
        }
        // Refresh so files added/removed outside the app (or from the viewer)
        // appear immediately.
        currentDir?.let { loadDirectory(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.release()
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
        mode = TAB_BROWSE
        selectTab(TAB_BROWSE)
        val start = prefs.homePath?.let { File(it) }?.takeIf { it.isDirectory }
            ?: prefs.lastPath?.let { File(it) }?.takeIf { it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
        loadDirectory(start)
    }

    // ------------------------------------------------------------------ tabs

    private fun selectTab(index: Int) {
        if (tabs.selectedTabPosition != index) tabs.getTabAt(index)?.select()
    }

    private fun onTabChanged(index: Int) {
        if (index == mode) return
        when (index) {
            TAB_FAVORITES -> showFavorites()
            TAB_HISTORY -> showHistory()
            TAB_BOOKMARKS -> showBookmarks()
            else -> {
                val dir = currentDir
                if (dir != null) loadDirectory(dir) else openStartDirectory()
            }
        }
    }

    // ------------------------------------------------------------------ listing

    private fun loadDirectory(dir: File, highlight: String? = null) {
        mode = TAB_BROWSE
        currentDir = dir
        prefs.lastPath = dir.absolutePath
        adapter.highlightPath = highlight
        selectTab(TAB_BROWSE)

        val root = Environment.getExternalStorageDirectory()
        val atRoot = dir.absolutePath == root.absolutePath
        supportActionBar?.title = if (atRoot || dir.name.isBlank()) getString(R.string.app_name) else dir.name
        pathbar.text = dir.absolutePath
        pathbar.visibility = if (prefs.listPathView) View.VISIBLE else View.GONE
        invalidateOptionsMenu()

        showProgress(true)
        lifecycleScope.launch {
            val list = withContext(AppDispatchers.io) { buildDirectoryEntries(dir) }
            progress.visibility = View.GONE
            displayEntries(list)
            if (highlight != null) scrollToHighlight(highlight)
        }
    }

    private fun buildDirectoryEntries(dir: File): List<BookEntry> {
        val files = dir.listFiles() ?: return emptyList()
        val showHidden = prefs.showHidden
        val out = ArrayList<BookEntry>(files.size)
        for (f in files) {
            if (!showHidden && f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            val type = if (isDir) EntryType.FOLDER else Formats.typeOf(f.name)
            if (!isDir && type == EntryType.OTHER) continue
            val history = if (isDir) null else library.historyFor(f.absolutePath)
            out.add(
                BookEntry(
                    file = f,
                    name = f.name,
                    isDir = isDir,
                    type = type,
                    size = if (isDir) 0 else f.length(),
                    lastModified = f.lastModified(),
                    childCount = if (isDir) (f.listFiles()?.size ?: 0) else -1,
                    pages = history?.total ?: -1,
                    progressPage = history?.page ?: -1,
                    favorite = library.isFavorite(f.absolutePath),
                    read = library.isRead(f.absolutePath)
                )
            )
        }
        return out
    }

    private fun showFavorites() {
        mode = TAB_FAVORITES
        currentDir = null
        adapter.highlightPath = null
        selectTab(TAB_FAVORITES)
        supportActionBar?.title = getString(R.string.menu_favorites)
        pathbar.visibility = View.GONE
        invalidateOptionsMenu()
        showProgress(true)
        lifecycleScope.launch {
            val list = withContext(AppDispatchers.io) {
                library.favorites().map { entryFor(File(it)) }
            }
            progress.visibility = View.GONE
            displayEntries(list)
        }
    }

    private fun showHistory() {
        mode = TAB_HISTORY
        currentDir = null
        adapter.highlightPath = null
        selectTab(TAB_HISTORY)
        supportActionBar?.title = getString(R.string.menu_history)
        pathbar.visibility = View.GONE
        invalidateOptionsMenu()
        showProgress(true)
        lifecycleScope.launch {
            val list = withContext(AppDispatchers.io) { library.history().map { entryForHistory(it) } }
            progress.visibility = View.GONE
            displayEntries(list)
        }
    }

    private fun showBookmarks() {
        mode = TAB_BOOKMARKS
        currentDir = null
        adapter.highlightPath = null
        selectTab(TAB_BOOKMARKS)
        supportActionBar?.title = getString(R.string.menu_bookmarks)
        pathbar.visibility = View.GONE
        invalidateOptionsMenu()
        showProgress(true)
        lifecycleScope.launch {
            val list = withContext(AppDispatchers.io) { library.bookmarks().map { entryForBookmark(it) } }
            progress.visibility = View.GONE
            displayEntries(list)
        }
    }

    private fun entryFor(file: File): BookEntry {
        val isDir = file.isDirectory
        val history = if (isDir) null else library.historyFor(file.absolutePath)
        return BookEntry(
            file = file,
            name = file.name,
            isDir = isDir,
            type = if (isDir) EntryType.FOLDER else Formats.typeOf(file.name),
            size = if (isDir) 0 else file.length(),
            lastModified = file.lastModified(),
            childCount = if (isDir) (file.listFiles()?.size ?: 0) else -1,
            pages = history?.total ?: -1,
            progressPage = history?.page ?: -1,
            favorite = library.isFavorite(file.absolutePath),
            read = library.isRead(file.absolutePath)
        )
    }

    private fun entryForHistory(item: HistoryItem): BookEntry {
        val file = File(item.path)
        val exists = file.exists()
        return BookEntry(
            file = file,
            name = item.name,
            isDir = false,
            type = Formats.typeOf(item.name),
            size = if (exists) file.length() else 0,
            lastModified = item.time,
            pages = item.total,
            progressPage = item.page,
            favorite = library.isFavorite(item.path),
            read = library.isRead(item.path),
            subtitle = if (!exists) getString(R.string.missing_file) else null
        )
    }

    private fun entryForBookmark(bookmark: com.infinity.comicreader.data.Bookmark): BookEntry {
        val file = File(bookmark.path)
        val history = library.historyFor(bookmark.path)
        return BookEntry(
            file = file,
            name = file.name.ifEmpty { bookmark.name },
            isDir = false,
            type = Formats.typeOf(file.name),
            size = 0,
            lastModified = bookmark.time,
            pages = history?.total ?: -1,
            progressPage = bookmark.page,
            favorite = library.isFavorite(bookmark.path),
            read = library.isRead(bookmark.path),
            subtitle = getString(R.string.page_number, bookmark.page + 1)
        )
    }

    private fun displayEntries(list: List<BookEntry>) {
        allEntries.clear()
        allEntries.addAll(list)
        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        val filtered = if (query.isBlank()) allEntries
        else allEntries.filter { it.name.contains(query, ignoreCase = true) }

        var byField: Comparator<BookEntry> = when (prefs.listSort) {
            "size" -> compareBy { it.size }
            "date" -> compareBy { it.lastModified }
            "type" -> compareBy { it.type.ordinal }
            else -> compareBy(NaturalOrder) { it.name }
        }
        if (prefs.listSortDir == "desc") byField = byField.reversed()

        val comparator = compareByDescending<BookEntry> { it.isDir }
            .then(byField)
            .thenBy(NaturalOrder) { it.name }

        shown.clear()
        shown.addAll(filtered.sortedWith(comparator))
        adapter.submit(shown)
        updateEmptyState()
        updateFabIcon()
    }

    private fun updateEmptyState() {
        val visible = shown.isEmpty()
        emptyView.visibility = if (visible) View.VISIBLE else View.GONE
        fab.visibility = if (!visible && prefs.listQuickFab) View.VISIBLE else View.GONE
        if (!visible) return

        val (icon, title, sub) = when {
            query.isNotBlank() -> Triple(R.drawable.ic_search, getString(R.string.empty_search), null)
            mode == TAB_FAVORITES -> Triple(
                R.drawable.ic_star_border, getString(R.string.no_favorites),
                getString(R.string.menu_add_favorite)
            )
            mode == TAB_HISTORY -> Triple(R.drawable.ic_history, getString(R.string.no_history), null)
            mode == TAB_BOOKMARKS -> Triple(
                R.drawable.ic_bookmark_border, getString(R.string.no_bookmarks),
                getString(R.string.menu_bookmark_add)
            )
            else -> Triple(R.drawable.ic_folder_open, getString(R.string.empty_folder), null)
        }
        emptyIcon.setImageResource(icon)
        emptyText.text = title
        emptySubtitle.text = sub.orEmpty()
        emptySubtitle.visibility = if (sub == null) View.GONE else View.VISIBLE
    }

    private fun scrollToHighlight(path: String) {
        val index = shown.indexOfFirst { it.path == path }
        if (index >= 0) recycler.scrollToPosition(index)
    }

    private fun showProgress(visible: Boolean) {
        progress.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) emptyView.visibility = View.GONE
    }

    // ------------------------------------------------------------------ actions

    private fun onEntryClick(entry: BookEntry) {
        when {
            entry.isDir -> loadDirectory(entry.file)
            mode == TAB_HISTORY -> {
                // History entries open the containing folder first, so the reader
                // never resumes from a stale/missing path.
                if (entry.file.exists()) openLocation(entry.file) else toast(R.string.open_location_failed)
            }
            mode == TAB_BOOKMARKS -> {
                if (entry.file.exists()) openViewer(entry.file, entry.progressPage.coerceAtLeast(0))
                else toast(R.string.open_location_failed)
            }
            Formats.isOpenable(entry.name) -> {
                val page = if (mode == TAB_FAVORITES) library.pageFor(entry.path) else 0
                openViewer(entry.file, page)
            }
            else -> toast(R.string.error_open)
        }
    }

    private fun onEntryLongClick(entry: BookEntry) {
        val path = entry.path
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }

        if (mode == TAB_HISTORY && entry.file.exists()) {
            add(getString(R.string.menu_continue)) {
                openViewer(entry.file, library.pageFor(path))
            }
        }
        if (mode == TAB_BOOKMARKS) {
            add(getString(R.string.menu_remove_favorite)) {
                library.removeBookmark(path, entry.progressPage)
                showBookmarks()
            }
        } else {
            val favorite = library.isFavorite(path)
            add(if (favorite) getString(R.string.menu_remove_favorite) else getString(R.string.menu_add_favorite)) {
                toggleFavorite(path)
            }
            if (!entry.isDir) {
                val read = library.isRead(path)
                add(if (read) getString(R.string.menu_mark_unread) else getString(R.string.menu_mark_read)) {
                    library.setRead(path, !read)
                    refreshCurrent()
                }
            }
            add(getString(R.string.menu_open_location)) { openLocation(entry.file) }
            add(getString(R.string.menu_set_home)) {
                prefs.homePath = path
                toast(R.string.home_set)
            }
            if (entry.file.exists()) {
                add(getString(R.string.menu_rename)) { renameEntry(entry.file) }
                add(getString(R.string.delete)) { confirmDelete(entry.file) }
            }
        }
        if (mode == TAB_HISTORY) {
            add(getString(R.string.pref_clear_history)) { library.clearHistory(); refreshCurrent() }
        }

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun toggleGrid() {
        prefs.listGrid = !prefs.listGrid
        adapter.grid = prefs.listGrid
        applyLayoutManager()
        adapter.notifyDataSetChanged()
        updateFabIcon()
        invalidateOptionsMenu()
    }

    private fun updateFabIcon() {
        fab.setImageResource(if (prefs.listGrid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
        fab.contentDescription = getString(if (prefs.listGrid) R.string.menu_list_view else R.string.menu_grid)
    }

    private fun applyLayoutManager() {
        recycler.layoutManager = if (prefs.listGrid) {
            GridLayoutManager(this, columnCount())
        } else {
            LinearLayoutManager(this)
        }
    }

    private fun columnCount(): Int {
        if (prefs.gridColumns > 0) return prefs.gridColumns
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 120f).toInt().coerceIn(2, 6)
    }

    private fun toggleFavorite(path: String?) {
        if (path == null) return
        val added = library.toggleFavorite(path)
        toast(if (added) R.string.menu_add_favorite else R.string.menu_remove_favorite)
        refreshCurrent()
    }

    private fun openLocation(file: File) {
        val dir = file.parentFile
        if (dir == null || !dir.isDirectory) {
            toast(R.string.open_location_failed)
            return
        }
        loadDirectory(dir, highlight = file.absolutePath)
        toast(R.string.menu_open_location)
    }

    private fun renameEntry(file: File) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(file.name)
            selectAll()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_rename)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == file.name) return@setPositiveButton
                val target = File(file.parentFile, name)
                val ok = runCatching { file.renameTo(target) }.getOrDefault(false)
                toast(if (ok) R.string.renamed else R.string.rename_failed)
                refreshCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                val ok = runCatching { file.deleteRecursively() }.getOrDefault(false)
                if (ok) {
                    library.removeFavorite(file.absolutePath)
                    library.removeHistory(file.absolutePath)
                    toast(getString(R.string.deleted_format, 1))
                }
                refreshCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        val dir = currentDir ?: return
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = getString(R.string.new_folder_hint) }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_new_folder)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val created = File(dir, name).mkdirs()
                toast(if (created) R.string.folder_created else R.string.error_open)
                refreshCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCurrent() {
        when (mode) {
            TAB_FAVORITES -> showFavorites()
            TAB_HISTORY -> showHistory()
            TAB_BOOKMARKS -> showBookmarks()
            else -> currentDir?.let { loadDirectory(it) } ?: openStartDirectory()
        }
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
                applyQuery(q)
                searchView.clearFocus() // collapse the keyboard once the search is committed
                return true
            }

            override fun onQueryTextChange(q: String?): Boolean = applyQuery(q)
        })
        return true
    }

    /** Filters and re-sorts the list for [q], live as the user types and on submit. */
    private fun applyQuery(q: String?): Boolean {
        query = q.orEmpty()
        applyFilterAndSort()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val browsing = mode == TAB_BROWSE
        menu.findItem(R.id.menu_grid)?.title =
            getString(if (prefs.listGrid) R.string.menu_list_view else R.string.menu_grid)
        menu.findItem(R.id.menu_columns)?.isVisible = prefs.listGrid
        menu.findItem(R.id.menu_show_hidden)?.apply {
            isChecked = prefs.showHidden
            title = getString(if (prefs.showHidden) R.string.menu_hide_hidden else R.string.menu_show_hidden)
        }
        menu.findItem(R.id.menu_add_favorite)?.isVisible = browsing
        menu.findItem(R.id.menu_add_favorite)?.title =
            getString(
                if (currentDir?.let { library.isFavorite(it.absolutePath) } == true)
                    R.string.menu_remove_favorite else R.string.menu_add_favorite
            )
        menu.findItem(R.id.menu_new_folder)?.isVisible = browsing
        menu.findItem(R.id.menu_sort_dir)?.isVisible =
            mode == TAB_BROWSE || mode == TAB_FAVORITES
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_grid -> toggleGrid()
            R.id.menu_sort -> showSortDialog()
            R.id.menu_sort_dir -> toggleSortDirection()
            R.id.menu_columns -> showColumnsDialog()
            R.id.menu_show_hidden -> {
                prefs.showHidden = !prefs.showHidden
                invalidateOptionsMenu()
                refreshCurrent()
            }
            R.id.menu_new_folder -> newFolder()
            R.id.menu_add_favorite -> toggleFavorite(currentDir?.absolutePath)
            R.id.menu_refresh -> refreshCurrent()
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

    private fun toggleSortDirection() {
        prefs.listSortDir = if (prefs.listSortDir == "asc") "desc" else "asc"
        toast(if (prefs.listSortDir == "asc") R.string.menu_ascending else R.string.menu_descending)
        applyFilterAndSort()
    }

    private fun showColumnsDialog() {
        val values = resources.getStringArray(R.array.columns_values)
        val labels = resources.getStringArray(R.array.columns_entries)
        val checked = values.indexOf(prefs.gridColumns.toString()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_columns)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.gridColumns = values[which].toIntOrNull() ?: 0
                applyLayoutManager()
                adapter.notifyDataSetChanged()
                dialog.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ back

    /** Returns true when the back press was handled by navigating somewhere. */
    private fun navigateUp(): Boolean {
        if (mode != TAB_BROWSE) {
            val dir = currentDir
            if (dir != null) loadDirectory(dir) else selectTab(TAB_BROWSE)
            return true
        }
        val dir = currentDir ?: return false
        val parent = parentWithinBounds(dir) ?: return false
        loadDirectory(parent)
        return true
    }

    /**
     * The parent directory, unless that would climb above the shared storage
     * root (`/storage/emulated/0`). Browsing must never leave the user's files.
     */
    private fun parentWithinBounds(dir: File): File? {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = dir.absolutePath
        if (path == root) return null
        val parent = dir.parentFile ?: return null
        val parentPath = parent.absolutePath
        if (parentPath == root || parentPath.startsWith("$root/")) return parent
        if (parentPath == "/" || parentPath == "/storage" || parentPath == "/storage/emulated") return null
        return parent
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!navigateUp()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_BROWSE = 0
        private const val TAB_FAVORITES = 1
        private const val TAB_HISTORY = 2
        private const val TAB_BOOKMARKS = 3
    }
}
