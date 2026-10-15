package com.infinity.comicreader.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library

    private lateinit var pager: ViewPager2
    private lateinit var vertical: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var pageText: TextView
    private lateinit var nightOverlay: View
    private lateinit var progress: android.widget.ProgressBar

    private var source: PageSource? = null
    private var adapter: PageAdapter? = null
    private var file: File? = null

    /** Current page index (always a real page, never a spread index). */
    private var currentIndex = 0
    private var verticalMode = false
    private var twoPage = false
    private var readingDir = "ltr"
    private var fitMode = ZoomImageView.FitMode.SCREEN
    private var nightMode = false
    private var keepOn = false
    private var barsVisible = true

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(CropActivity.EXTRA_RESULT)?.let {
                    toast(getString(R.string.crop_saved, it))
                }
            }
        }

    private val pagesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val page = result.data?.getIntExtra(PagesActivity.EXTRA_RESULT_PAGE, -1) ?: -1
                if (page >= 0) goTo(page)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        library = Library(this)
        setContentView(R.layout.activity_viewer)

        pager = findViewById(R.id.viewer_pager)
        vertical = findViewById(R.id.viewer_vertical)
        toolbar = findViewById(R.id.viewer_toolbar)
        bottomBar = findViewById(R.id.viewer_bottom)
        seek = findViewById(R.id.viewer_seek)
        pageText = findViewById(R.id.viewer_page_text)
        nightOverlay = findViewById(R.id.night_overlay)
        progress = findViewById(R.id.viewer_progress)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnClickListener { toggleBars() }

        keepOn = prefs.keepScreenOn
        applyKeepScreenOn()
        if (prefs.fullscreen) setFullscreen(true)

        verticalMode = prefs.pageMode == "vertical"
        twoPage = prefs.twoPage
        readingDir = prefs.readingDir
        fitMode = if (verticalMode) ZoomImageView.FitMode.WIDTH else ZoomImageView.FitMode.SCREEN

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) goTo(itemToPage(value))
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        open()
    }

    override fun onStop() {
        super.onStop()
        saveHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter?.release()
        runCatching { source?.close() }
    }

    // ------------------------------------------------------------------ loading

    private fun open() {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        file = File(path)
        // The ActionBar (installed via setSupportActionBar) owns the title and
        // overwrites anything set directly on the Toolbar, so update it there.
        supportActionBar?.title = file!!.name
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PageSources.open(file!!, prefs.charset) }
            }
            progress.visibility = View.GONE
            result.onSuccess { src ->
                if (src.pageCount <= 0) {
                    toast(R.string.error_open)
                    finish()
                    return@onSuccess
                }
                source = src
                setup(src)
            }.onFailure { t ->
                android.util.Log.w("ViewerActivity", "Failed to open archive/PDF: ${file?.name}", t)
                toast(R.string.error_open)
                finish()
            }
        }
    }

    private fun setup(src: PageSource) {
        currentIndex = intent.getIntExtra(EXTRA_PAGE, library.pageFor(file!!.absolutePath))
            .coerceIn(0, src.pageCount - 1)

        pager.layoutDirection =
            if (readingDir == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = itemToPage(position)
                seek.progress = position
                updatePageText()
            }
        })

        vertical.layoutManager = LinearLayoutManager(this)
        vertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
                    ?: lm.findFirstVisibleItemPosition()
                if (pos >= 0 && itemToPage(pos) != currentIndex) {
                    currentIndex = itemToPage(pos)
                    seek.progress = pos
                    updatePageText()
                }
            }
        })

        attach()
    }

    /**
     * Builds a fresh adapter for the current layout and attaches it. A
     * [RecyclerView.Adapter] has a fixed item view type, so toggling two-page,
     * reading direction or paged/vertical requires a new instance. These are
     * only emitted from the UI thread, so interactivity is safe.
     */
    private fun attach() {
        val src = source ?: return
        pager.adapter = null
        vertical.adapter = null
        adapter?.release()

        val a = PageAdapter(
            src,
            onPageTap = { toggleBars() },
            doubleTapZoom = prefs.doubleTapZoom,
            twoPage = useSpread(),
            rtl = readingDir == "rtl"
        )
        a.fitMode = fitMode
        adapter = a

        val item = pageToItem(currentIndex)
        if (verticalMode) {
            pager.visibility = View.GONE
            vertical.visibility = View.VISIBLE
            vertical.adapter = a
            vertical.scrollToPosition(item)
        } else {
            vertical.visibility = View.GONE
            pager.visibility = View.VISIBLE
            pager.adapter = a
            pager.setCurrentItem(item, false)
        }
        seek.max = (a.itemCount - 1).coerceAtLeast(0)
        seek.progress = item
        updatePageText()
        invalidateOptionsMenu()
    }

    private fun useSpread() = twoPage && !verticalMode

    private fun pageToItem(page: Int) = if (useSpread()) page / 2 else page

    private fun itemToPage(item: Int) = if (useSpread()) item * 2 else item

    private fun switchMode(enableVertical: Boolean) {
        verticalMode = enableVertical
        fitMode = if (enableVertical) ZoomImageView.FitMode.WIDTH else ZoomImageView.FitMode.SCREEN
        attach()
    }

    private fun goTo(page: Int) {
        val count = source?.pageCount ?: return
        currentIndex = page.coerceIn(0, count - 1)
        val item = pageToItem(currentIndex)
        if (verticalMode) vertical.scrollToPosition(item) else pager.setCurrentItem(item, false)
        seek.progress = item
        updatePageText()
    }

    private fun updatePageText() {
        val count = source?.pageCount ?: 0
        pageText.text = getString(R.string.page_indicator, currentIndex + 1, count)
    }

    private fun saveHistory() {
        val f = file ?: return
        val count = source?.pageCount ?: return
        library.addHistory(
            HistoryItem(f.absolutePath, f.name, currentIndex, count, System.currentTimeMillis())
        )
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_keep_on)?.isChecked = keepOn
        menu.findItem(R.id.menu_night)?.isChecked = nightMode
        menu.findItem(R.id.menu_fullscreen)?.isChecked = prefs.fullscreen
        menu.findItem(R.id.menu_dir_ltr)?.isChecked = readingDir == "ltr"
        menu.findItem(R.id.menu_dir_rtl)?.isChecked = readingDir == "rtl"
        menu.findItem(R.id.menu_mode_paged)?.isChecked = !verticalMode
        menu.findItem(R.id.menu_mode_vertical)?.isChecked = verticalMode
        menu.findItem(R.id.menu_two_page)?.apply {
            isChecked = twoPage
            isEnabled = !verticalMode
        }
        menu.findItem(R.id.menu_fit_screen)?.isChecked = fitMode == ZoomImageView.FitMode.SCREEN
        menu.findItem(R.id.menu_fit_width)?.isChecked = fitMode == ZoomImageView.FitMode.WIDTH
        menu.findItem(R.id.menu_fit_height)?.isChecked = fitMode == ZoomImageView.FitMode.HEIGHT
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_jump -> showJumpDialog()
            R.id.menu_fit_screen -> setFit(ZoomImageView.FitMode.SCREEN)
            R.id.menu_fit_width -> setFit(ZoomImageView.FitMode.WIDTH)
            R.id.menu_fit_height -> setFit(ZoomImageView.FitMode.HEIGHT)
            R.id.menu_rotate -> cycleOrientation()
            R.id.menu_bookmark -> addBookmark()
            R.id.menu_bookmark_list -> showBookmarks()
            R.id.menu_pages -> openPages()
            R.id.menu_crop -> startCrop()
            R.id.menu_two_page -> toggleTwoPage()
            R.id.menu_dir_ltr -> setReadingDir("ltr")
            R.id.menu_dir_rtl -> setReadingDir("rtl")
            R.id.menu_mode_paged -> switchMode(false)
            R.id.menu_mode_vertical -> switchMode(true)
            R.id.menu_orient_default ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            R.id.menu_orient_portrait ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            R.id.menu_orient_landscape ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            R.id.menu_keep_on -> {
                keepOn = !keepOn
                applyKeepScreenOn()
                invalidateOptionsMenu()
            }
            R.id.menu_night -> setNight(!nightMode)
            R.id.menu_fullscreen -> setFullscreen(!prefs.fullscreen)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setFit(mode: ZoomImageView.FitMode) {
        fitMode = mode
        adapter?.fitMode = mode
        invalidateOptionsMenu()
    }

    private fun setReadingDir(dir: String) {
        readingDir = dir
        attach()
    }

    private fun toggleTwoPage() {
        if (verticalMode) return
        twoPage = !twoPage
        prefs.twoPage = twoPage
        attach()
    }

    private fun cycleOrientation() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun applyKeepScreenOn() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setNight(enabled: Boolean) {
        nightMode = enabled
        nightOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun setFullscreen(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        invalidateOptionsMenu()
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val visibility = if (barsVisible) View.VISIBLE else View.GONE
        toolbar.visibility = visibility
        bottomBar.visibility = visibility
    }

    private fun addBookmark() {
        val f = file ?: return
        val path = f.absolutePath
        if (library.isBookmarked(path, currentIndex)) {
            library.removeBookmark(path, currentIndex)
            toast(R.string.bookmark_removed)
        } else {
            library.addBookmark(Bookmark(path, f.name, currentIndex, "", System.currentTimeMillis()))
            toast(R.string.bookmark_saved)
        }
    }

    private fun showBookmarks() {
        val f = file ?: return
        val marks = library.bookmarksFor(f.absolutePath)
        if (marks.isEmpty()) {
            toast(R.string.no_bookmarks)
            return
        }
        val count = source?.pageCount ?: 0
        val labels = marks.map { getString(R.string.page_indicator, it.page + 1, count) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmark_list)
            .setItems(labels) { _, which -> goTo(marks[which].page) }
            .setNeutralButton(R.string.clear_history) { _, _ ->
                marks.forEach { library.removeBookmark(f.absolutePath, it.page) }
            }
            .show()
    }

    private fun showJumpDialog() {
        val count = source?.pageCount ?: return
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.jump_hint)
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                goTo((n - 1).coerceIn(0, count - 1))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ crop / pages

    private fun openPages() {
        val f = file ?: return
        pagesLauncher.launch(
            Intent(this, PagesActivity::class.java)
                .putExtra(PagesActivity.EXTRA_PATH, f.absolutePath)
                .putExtra(PagesActivity.EXTRA_PAGE, currentIndex)
        )
    }

    private fun startCrop() {
        val src = source ?: return
        val page = currentIndex
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    src.loadPage(page, 2400, 3200)
                } catch (t: Throwable) {
                    android.util.Log.w("ViewerActivity", "Failed to load page for crop $page", t)
                    null
                }
            }
            val outFile = if (bitmap == null) null else withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(cacheDir, "crop_src.png")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    out
                }.getOrNull()
            }
            progress.visibility = View.GONE
            if (outFile == null) {
                toast(R.string.error_load)
                return@launch
            }
            cropLauncher.launch(
                Intent(this@ViewerActivity, CropActivity::class.java)
                    .putExtra(CropActivity.EXTRA_PATH, outFile.absolutePath)
            )
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_PAGE = "extra_page"
    }
}
