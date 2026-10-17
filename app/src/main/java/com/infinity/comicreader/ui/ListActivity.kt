package com.infinity.comicreader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var deepJob: Job? = null
    private var deepResults: List<BookEntry>? = null
    private val allEntries = ArrayList<BookEntry>()
    private val shown = ArrayList<BookEntry>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Only read access is required to browse; write access (old Android) is a bonus.
            if (result[Manifest.permission.READ_EXTERNAL_STORAGE] == true) openStartDirectory()
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

        handleIncoming(intent)

        if (!hasStorageAccess()) {
            requestStorageAccess()
        } else {
            openStartDirectory()
        }
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarSetting()
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

    private fun applyStatusBarSetting() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (prefs.listStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
            val wanted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(wanted)
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
        deepJob?.cancel()
        deepResults = null
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

    /** Favorites, read marks and history, loaded once per listing instead of once per row. */
    private class Snapshot(
        val favorites: Set<String>,
        val read: Set<String>,
        val history: Map<String, HistoryItem>
    )

    private fun snapshot() = Snapshot(library.favoriteSet(), library.readSet(), library.historyMap())

    /** A folder's path relative to the shared storage root, e.g. "Comics/One Piece". */
    private fun relativePath(dir: File): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = dir.absolutePath
        return when {
            path == root -> ""
            path.startsWith("$root/") -> path.removePrefix("$root/")
            else -> path
        }
    }

    /** Items a user would see inside [dir]: sub-folders and openable files, minus hidden ones. */
    private fun visibleChildren(dir: File): List<File> {
        val showHidden = prefs.showHidden
        return dir.listFiles()?.filter {
            (showHidden || !it.name.startsWith(".")) && (it.isDirectory || Formats.isOpenable(it.name))
        } ?: emptyList()
    }

    private fun buildDirectoryEntries(dir: File): List<BookEntry> {
        val files = dir.listFiles() ?: return emptyList()
        val showHidden = prefs.showHidden
        val snap = snapshot()
        val out = ArrayList<BookEntry>(files.size)
        for (f in files) {
            if (!showHidden && f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            if (!isDir && Formats.typeOf(f.name) == EntryType.OTHER) continue
            out.add(entryFor(f, snap))
        }
        return out
    }

    private fun entryFor(file: File, snap: Snapshot): BookEntry {
        val isDir = file.isDirectory
        val history = if (isDir) null else snap.history[file.absolutePath]
        val children = if (isDir) visibleChildren(file) else null
        return BookEntry(
            file = file,
            name = file.name,
            isDir = isDir,
            type = if (isDir) EntryType.FOLDER else Formats.typeOf(file.name),
            size = if (isDir) 0 else file.length(),
            lastModified = file.lastModified(),
            childCount = children?.size ?: -1,
            readCount = children?.count { it.absolutePath in snap.read } ?: -1,
            pages = history?.total ?: -1,
            progressPage = history?.page ?: -1,
            favorite = file.absolutePath in snap.favorites,
            read = file.absolutePath in snap.read
        )
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
                val snap = snapshot()
                library.favorites().map { entryFor(File(it), snap) }
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
            val list = withContext(AppDispatchers.io) {
                val snap = snapshot()
                library.history().map { entryForHistory(it, snap) }
            }
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
            val list = withContext(AppDispatchers.io) {
                val snap = snapshot()
                library.bookmarks().map { entryForBookmark(it, snap) }
            }
            progress.visibility = View.GONE
            displayEntries(list)
        }
    }

    private fun entryForHistory(item: HistoryItem, snap: Snapshot): BookEntry {
        val file = File(item.path)
        val exists = file.exists()
        // Chapters are usually just "Chapter 12.cbz", so the folder below the name is
        // what tells two chapters from different comics apart.
        val where = file.parentFile?.let { relativePath(it) }
        return BookEntry(
            file = file,
            name = item.name,
            isDir = false,
            type = Formats.typeOf(item.name),
            size = if (exists) file.length() else 0,
            lastModified = item.time,
            pages = item.total,
            progressPage = item.page,
            favorite = item.path in snap.favorites,
            read = item.path in snap.read,
            subtitle = when {
                !exists -> getString(R.string.missing_file)
                where != null && where.isNotEmpty() -> where
                else -> null
            }
        )
    }

    private fun entryForBookmark(bookmark: com.infinity.comicreader.data.Bookmark, snap: Snapshot): BookEntry {
        val file = File(bookmark.path)
        return BookEntry(
            file = file,
            name = file.name.ifEmpty { bookmark.name },
            isDir = false,
            type = Formats.typeOf(file.name),
            size = 0,
            lastModified = bookmark.time,
            pages = snap.history[bookmark.path]?.total ?: -1,
            progressPage = bookmark.page,
            favorite = bookmark.path in snap.favorites,
            read = bookmark.path in snap.read,
            subtitle = getString(R.string.page_number, bookmark.page + 1)
        )
    }

    private fun displayEntries(list: List<BookEntry>) {
        allEntries.clear()
        allEntries.addAll(list)
        applyFilterAndSort()
        if (query.isNotBlank()) scheduleDeepSearch()
    }

    private fun applyFilterAndSort() {
        val deep = deepResults
        val filtered = when {
            query.isBlank() -> allEntries
            deep != null -> deep
            else -> allEntries.filter { it.name.contains(query, ignoreCase = true) }
        }

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
        updateRecentHeader()
        updateEmptyState()
        updateFabIcon()
    }

    /**
     * Keeps a "Recent" row at the top of the browse list, pointing at the folder of
     * the last chapter that was read. Chapters are almost always just numbered, so
     * the folder name is what identifies the series — and tapping the row jumps
     * straight back to it.
     */
    private fun updateRecentHeader() {
        val show = mode == TAB_BROWSE && query.isBlank() && shown.isNotEmpty()
        val dir = if (show) library.history().firstOrNull()?.let { File(it.path).parentFile } else null
        if (dir == null) {
            adapter.header = null
            return
        }
        adapter.header = BookAdapter.Header(
            title = getString(R.string.recent),
            subtitle = relativePath(dir).ifEmpty { dir.absolutePath },
            iconRes = R.drawable.ic_history,
            onClick = { loadDirectory(dir) }
        )
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
        // The "Recent" row shifts every entry down by one position.
        if (index >= 0) recycler.scrollToPosition(index + adapter.itemOffset)
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
                // The reader decides where to start: the resume/first-page prompt when
                // it is on, the saved page, or the first page for a finished book.
                val page = if (library.isRead(entry.path)) 0 else -1
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
            if (entry.isDir) {
                add(getString(R.string.menu_continue)) { continueReading(entry.file) }
                add(getString(R.string.menu_mark_all_read)) { markFolder(entry.file, true) }
                add(getString(R.string.menu_mark_all_unread)) { markFolder(entry.file, false) }
            } else {
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
            GridLayoutManager(this, columnCount()).apply {
                // The "Recent" row is not a grid cell: it spans the whole width.
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (position == 0 && adapter.header != null) spanCount else 1
                }
            }
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
                if (!FileUtils.isSafeName(name)) {
                    toast(R.string.invalid_name)
                    return@setPositiveButton
                }
                val target = File(file.parentFile, name)
                // renameTo silently replaces an existing file, so refuse to.
                val ok = !target.exists() && runCatching { file.renameTo(target) }.getOrDefault(false)
                if (ok) library.replacePath(file.absolutePath, target.absolutePath)
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
                lifecycleScope.launch {
                    val ok = withContext(AppDispatchers.io) {
                        runCatching { FileUtils.deleteTree(file) }.getOrDefault(false)
                    }
                    // Even a partial failure may have removed some files: forget what is gone.
                    if (!file.exists()) library.removePathsUnder(file.absolutePath)
                    toast(if (ok) getString(R.string.deleted_format, 1) else getString(R.string.op_failed))
                    refreshCurrent()
                }
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
                if (!FileUtils.isSafeName(name)) {
                    toast(R.string.invalid_name)
                    return@setPositiveButton
                }
                val created = File(dir, name).mkdir()
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
        if (!isUnderStorage(file)) return
        if (file.isFile && Formats.isOpenable(file.name)) {
            openViewer(file, 0)
        } else if (file.isDirectory) {
            loadDirectory(file)
        }
    }

    /** Only shared storage may be opened by path: never the app's own or other private folders. */
    private fun isUnderStorage(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val root = Environment.getExternalStorageDirectory().canonicalPath
        return path == root || path.startsWith("$root/") || path.startsWith("/storage/")
    }

    private fun handleIncoming(intent: Intent) {
        val uri = intent.data ?: return
        when (uri.scheme) {
            "file" -> uri.path?.let { handleIncomingFile(File(it)) }
            "content" -> openSharedContent(uri)
        }
    }

    /** Another app handed us a content:// URI: copy it into the cache and open the copy. */
    private fun openSharedContent(uri: Uri) {
        showProgress(true)
        lifecycleScope.launch {
            val copy = withContext(AppDispatchers.io) { copyToCache(uri) }
            progress.visibility = View.GONE
            if (copy != null) openViewer(copy, 0) else toast(R.string.error_open)
        }
    }

    private fun copyToCache(uri: Uri): File? {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val safe = name?.let { File(it).name }
        if (safe == null || !FileUtils.isSafeName(safe) || !Formats.isOpenable(safe)) return null

        val dir = File(cacheDir, INCOMING_DIR)
        FileUtils.deleteTree(dir) // keep only the file being read
        if (!dir.mkdirs()) return null
        val out = File(dir, safe)
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_INCOMING_BYTES) throw java.io.IOException("File is too large")
                        sink.write(buffer, 0, n)
                    }
                }
            }
            out
        } catch (e: java.io.IOException) {
            out.delete()
            null
        }
    }

    // ------------------------------------------------------------------ continue / mark

    private fun continueReading(dir: File) {
        lifecycleScope.launch {
            val target = withContext(AppDispatchers.io) { pickContinue(dir) }
            if (target == null) toast(R.string.continue_none) else openViewer(target.first, target.second)
        }
    }

    /**
     * What to read next in [dir]: the most recently read book if it is unfinished, else the
     * first unread book after it, else the first unread book.
     */
    private fun pickContinue(dir: File): Pair<File, Int>? {
        val books = dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && Formats.isOpenable(it.name) }
            ?.sortedWith(compareBy(NaturalOrder) { it.name })
            ?: return null
        if (books.isEmpty()) return null
        val history = library.historyMap()
        val read = library.readSet()
        val last = books.filter { it.absolutePath in history }
            .maxByOrNull { history.getValue(it.absolutePath).time }
        if (last != null) {
            val page = history.getValue(last.absolutePath).page
            if (page > 0 && last.absolutePath !in read) return last to page
            books.drop(books.indexOf(last) + 1).firstOrNull { it.absolutePath !in read }
                ?.let { return it to 0 }
        }
        return books.firstOrNull { it.absolutePath !in read }?.let { it to 0 }
    }

    private fun markFolder(dir: File, read: Boolean) {
        lifecycleScope.launch {
            withContext(AppDispatchers.io) {
                val paths = dir.listFiles()
                    ?.filter { it.isFile && Formats.isOpenable(it.name) }
                    ?.map { it.absolutePath }
                    .orEmpty()
                library.setReadMany(paths, read)
            }
            toast(if (read) R.string.marked_read else R.string.marked_unread)
            refreshCurrent()
        }
    }

    private fun showStats() {
        val st = library.stats()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_stats)
            .setMessage(
                getString(
                    R.string.stats_body, st.chapters, st.pages, st.seconds / 3600,
                    (st.seconds % 3600) / 60, st.daysRead, st.streak, st.bestStreak
                )
            )
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.reset_stats) { _, _ -> library.clearStats() }
            .show()
    }

    private fun openCleanup(cleanupMode: String) {
        val root = currentDir ?: Environment.getExternalStorageDirectory()
        startActivity(
            Intent(this, CleanupActivity::class.java)
                .putExtra(CleanupActivity.EXTRA_MODE, cleanupMode)
                .putExtra(CleanupActivity.EXTRA_ROOT, root.absolutePath)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
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
        deepJob?.cancel()
        deepResults = null
        applyFilterAndSort()
        scheduleDeepSearch()
        return true
    }

    /** With "search subfolders" on, looks below the current folder once typing pauses. */
    private fun scheduleDeepSearch() {
        val dir = currentDir
        val text = query.trim()
        if (!prefs.deepSearch || mode != TAB_BROWSE || dir == null || text.length < 2) return
        deepJob?.cancel()
        deepJob = lifecycleScope.launch {
            delay(DEEP_SEARCH_DELAY_MS)
            val found = withContext(AppDispatchers.io) { deepSearch(dir, text) { isActive } }
            deepResults = found
            applyFilterAndSort()
        }
    }

    private fun deepSearch(root: File, text: String, alive: () -> Boolean): List<BookEntry> {
        val showHidden = prefs.showHidden
        val snap = snapshot()
        val rootPath = root.absolutePath
        return root.walkTopDown()
            .maxDepth(DEEP_SEARCH_DEPTH)
            .onEnter { showHidden || !it.name.startsWith(".") }
            .takeWhile { alive() }
            .filter { it != root && it.name.contains(text, ignoreCase = true) }
            .filter { it.isDirectory || Formats.isOpenable(it.name) }
            .take(DEEP_SEARCH_LIMIT)
            .map { f ->
                val where = f.parentFile?.absolutePath?.removePrefix(rootPath)?.trimStart('/').orEmpty()
                entryFor(f, snap).copy(subtitle = where.ifEmpty { f.parentFile?.name.orEmpty() })
            }
            .toList()
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
        menu.findItem(R.id.menu_continue)?.isVisible = browsing
        menu.findItem(R.id.menu_mark_all_read)?.isVisible = browsing
        menu.findItem(R.id.menu_mark_all_unread)?.isVisible = browsing
        menu.findItem(R.id.menu_dupes)?.isVisible = browsing
        menu.findItem(R.id.menu_large)?.isVisible = browsing
        menu.findItem(R.id.menu_search_deep)?.apply {
            isVisible = browsing
            isChecked = prefs.deepSearch
        }
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
            R.id.menu_continue -> currentDir?.let { continueReading(it) }
            R.id.menu_mark_all_read -> currentDir?.let { markFolder(it, true) }
            R.id.menu_mark_all_unread -> currentDir?.let { markFolder(it, false) }
            R.id.menu_search_deep -> {
                prefs.deepSearch = !prefs.deepSearch
                deepJob?.cancel()
                deepResults = null
                invalidateOptionsMenu()
                applyFilterAndSort()
                scheduleDeepSearch()
            }
            R.id.menu_stats -> showStats()
            R.id.menu_dupes -> openCleanup(CleanupActivity.MODE_DUPES)
            R.id.menu_large -> openCleanup(CleanupActivity.MODE_LARGE)
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
        private const val INCOMING_DIR = "incoming"
        private const val MAX_INCOMING_BYTES = 2L * 1024 * 1024 * 1024
        private const val DEEP_SEARCH_DELAY_MS = 300L
        private const val DEEP_SEARCH_DEPTH = 8
        private const val DEEP_SEARCH_LIMIT = 500
    }
}
