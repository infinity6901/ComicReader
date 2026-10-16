package com.infinity.comicreader.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The reader.
 *
 * Page layout is one of three modes — paged, vertical or long strip — and every
 * reader option (mode, fit, filter, brightness, spacing, direction, two-page,
 * night filter, keep-on, fullscreen) is read from and written back to [Prefs], so
 * the choices survive chapter changes and app restarts.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library

    private lateinit var pager: ViewPager2
    private lateinit var vertical: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var pageText: TextView
    private lateinit var chapterText: TextView
    private lateinit var dim: View
    private lateinit var nightOverlay: View
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var endCard: View
    private lateinit var endSubtitle: TextView
    private lateinit var endNext: View
    private lateinit var endPrev: View
    private lateinit var btnPrevChapter: ImageButton
    private lateinit var btnNextChapter: ImageButton

    private var source: PageSource? = null
    private var adapter: PageAdapter? = null
    private var file: File? = null

    /** Sibling chapters (openable files in the same folder), natural-ordered. */
    private var chapters: List<File> = emptyList()

    /** Current page index (always a real page, never a spread index). */
    private var currentIndex = 0
    private var mode = MODE_PAGED
    private var twoPage = false
    private var readingDir = "ltr"
    private var filter = Filters.NONE
    private var brightness = 100
    private var spacingDp = 0
    private var nightFilter = false
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
        bindViews()
        loadPrefs()

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Page-change callbacks are registered once: they outlive adapter swaps.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (mode != MODE_PAGED) return
                currentIndex = itemToPage(position)
                seek.progress = position
                onPageChanged()
            }
        })
        vertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (mode == MODE_PAGED) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
                    ?: lm.findFirstVisibleItemPosition()
                if (pos >= 0 && itemToPage(pos) != currentIndex) {
                    currentIndex = itemToPage(pos)
                    seek.progress = pos
                    onPageChanged()
                }
                if (mode == MODE_LONGSTRIP) updateEndCard()
            }
        })

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) goTo(itemToPage(value))
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnPrevChapter = findViewById(R.id.btn_prev_chapter)
        btnNextChapter = findViewById(R.id.btn_next_chapter)
        btnPrevChapter.setOnClickListener { openPrevChapter() }
        btnNextChapter.setOnClickListener { openNextChapter() }
        findViewById<View>(R.id.btn_prev_page).setOnClickListener { goTo(currentIndex - 1) }
        findViewById<View>(R.id.btn_next_page).setOnClickListener { goTo(currentIndex + 1) }
        findViewById<View>(R.id.btn_pages).setOnClickListener { openPages() }

        endPrev.setOnClickListener { openPrevChapter() }
        endNext.setOnClickListener { openNextChapter() }
        findViewById<View>(R.id.end_close).setOnClickListener { hideEndCard() }

        open()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closeSource()
        hideEndCard()
        open()
    }

    override fun onStop() {
        super.onStop()
        saveHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeSource()
    }

    private fun bindViews() {
        pager = findViewById(R.id.viewer_pager)
        vertical = findViewById(R.id.viewer_vertical)
        toolbar = findViewById(R.id.viewer_toolbar)
        bottomBar = findViewById(R.id.viewer_bottom)
        seek = findViewById(R.id.viewer_seek)
        pageText = findViewById(R.id.viewer_page_text)
        chapterText = findViewById(R.id.viewer_chapter_text)
        dim = findViewById(R.id.viewer_dim)
        nightOverlay = findViewById(R.id.night_overlay)
        progress = findViewById(R.id.viewer_progress)
        endCard = findViewById(R.id.viewer_end)
        endSubtitle = findViewById(R.id.end_subtitle)
        endNext = findViewById(R.id.end_next)
        endPrev = findViewById(R.id.end_prev)
        vertical.layoutManager = LinearLayoutManager(this)
    }

    private fun loadPrefs() {
        mode = prefs.pageMode
        twoPage = prefs.twoPage
        readingDir = prefs.readingDir
        filter = prefs.filter
        brightness = prefs.brightness
        spacingDp = prefs.pageSpacing
        keepOn = prefs.keepScreenOn
        nightFilter = prefs.nightFilter
        applyKeepScreenOn()
        applyNight()
        applyBrightness()
        if (prefs.fullscreen) setFullscreen(true)
    }

    // ------------------------------------------------------------------ loading

    private fun open() {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        file = File(path)
        toolbar.title = chapterLabel(file!!)
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val (src, siblings) = withContext(Dispatchers.IO) {
                val s = runCatching { PageSources.open(file!!, prefs.charset) }.getOrNull()
                s to siblingChapters(file!!)
            }
            progress.visibility = View.GONE
            chapters = siblings
            if (src == null) {
                toast(R.string.error_open)
                finish()
                return@launch
            }
            if (src.pageCount <= 0) {
                runCatching { src.close() }
                toast(R.string.error_open)
                finish()
                return@launch
            }
            source = src
            setup(src)
        }
    }

    private fun closeSource() {
        adapter?.release()
        adapter = null
        runCatching { source?.close() }
        source = null
        pager.adapter = null
        vertical.adapter = null
    }

    private fun siblingChapters(f: File): List<File> {
        val dir = f.parentFile ?: return listOf(f)
        val files = dir.listFiles() ?: return listOf(f)
        val openable = files
            .filter { it.isFile && !it.name.startsWith(".") && Formats.isOpenable(it.name) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        return if (openable.any { it.absolutePath == f.absolutePath }) openable else openable + f
    }

    private fun setup(src: PageSource) {
        val requested = intent.getIntExtra(EXTRA_PAGE, -1)
        val start = when {
            requested >= 0 -> requested
            prefs.resume -> library.pageFor(file!!.absolutePath)
            else -> 0
        }
        currentIndex = start.coerceIn(0, src.pageCount - 1)
        if (mode != MODE_PAGED && prefs.fit == "screen") prefs.fit = "width"
        attach()
    }

    /**
     * Builds a fresh adapter for the current layout. A [RecyclerView.Adapter] has
     * a fixed item view type, so changing the mode, spread or reading direction
     * requires a new instance. These are only emitted from the UI thread.
     */
    private fun attach() {
        val src = source ?: return
        pager.adapter = null
        vertical.adapter = null
        adapter?.release()

        val a = PageAdapter(
            source = src,
            mode = adapterMode(),
            onTap = { fraction -> onTap(fraction) },
            doubleTapZoom = prefs.doubleTapZoom,
            twoPage = useSpread(),
            rtl = readingDir == "rtl",
            onVerticalDrag = if (brightnessGestureEnabled()) ({ dy -> onBrightnessDrag(dy) }) else null
        )
        a.fitMode = effectiveFit()
        a.filter = filter
        a.spacingPx = spacingPx()
        adapter = a

        val item = pageToItem(currentIndex)
        if (mode == MODE_PAGED) {
            pager.layoutDirection =
                if (readingDir == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            vertical.visibility = View.GONE
            pager.visibility = View.VISIBLE
            pager.adapter = a
            pager.setCurrentItem(item, false)
        } else {
            pager.visibility = View.GONE
            vertical.visibility = View.VISIBLE
            vertical.adapter = a
            vertical.scrollToPosition(item)
        }
        seek.max = (a.itemCount - 1).coerceAtLeast(0)
        seek.progress = item
        onPageChanged()
    }

    private fun adapterMode(): PageAdapter.Mode = when (mode) {
        MODE_VERTICAL -> PageAdapter.Mode.VERTICAL
        MODE_LONGSTRIP -> PageAdapter.Mode.LONG_STRIP
        else -> PageAdapter.Mode.PAGED
    }

    private fun useSpread() = twoPage && mode == MODE_PAGED

    private fun pageToItem(page: Int) = if (useSpread()) page / 2 else page

    private fun itemToPage(item: Int) = if (useSpread()) item * 2 else item

    private fun brightnessGestureEnabled() = prefs.brightnessGesture && mode == MODE_PAGED

    private fun spacingPx(): Int =
        (spacingDp * resources.displayMetrics.density).toInt()

    private fun effectiveFit(): ZoomImageView.FitMode {
        // "Fit screen" is meaningless for a continuous column, so treat it as
        // fit-to-width there.
        val name = if (mode != MODE_PAGED && prefs.fit == "screen") "width" else prefs.fit
        return when (name) {
            "width" -> ZoomImageView.FitMode.WIDTH
            "height" -> ZoomImageView.FitMode.HEIGHT
            "stretch" -> ZoomImageView.FitMode.STRETCH
            else -> ZoomImageView.FitMode.SCREEN
        }
    }

    // ------------------------------------------------------------------ paging

    private fun goTo(page: Int) {
        val count = source?.pageCount ?: return
        if (page < 0) {
            if (prefs.autoNext) openPrevChapter() else goTo(0)
            return
        }
        if (page >= count) {
            handleAdvance()
            return
        }
        currentIndex = page
        val item = pageToItem(currentIndex)
        if (mode == MODE_PAGED) pager.setCurrentItem(item, false) else vertical.scrollToPosition(item)
        seek.progress = item
        onPageChanged()
    }

    /** Called when the reader is told to move past the last page. */
    private fun handleAdvance() {
        val next = nextChapter()
        if (next != null && prefs.autoNext) {
            openChapter(next, 0)
        } else if (next != null) {
            showEndCard()
        } else {
            toast(R.string.finished_last_chapter)
        }
    }

    private fun onTap(fraction: Float) {
        if (!prefs.tapZones || mode == MODE_LONGSTRIP) {
            toggleBars()
            return
        }
        val rtl = readingDir == "rtl" && mode == MODE_PAGED
        val delta = when {
            fraction < 0.33f -> if (rtl) 1 else -1
            fraction > 0.67f -> if (rtl) -1 else 1
            else -> {
                toggleBars()
                return
            }
        }
        goTo(currentIndex + delta)
    }

    private fun onPageChanged() {
        val count = source?.pageCount ?: 0
        pageText.text = getString(R.string.page_indicator, currentIndex + 1, count)
        chapterText.text = modeLabel()
        updateChapterButtons()
        updateEndCard()
        if (count > 0 && currentIndex >= count - 1) {
            file?.absolutePath?.let { library.setRead(it, true) }
        }
        invalidateOptionsMenu()
    }

    private fun modeLabel(): String = when (mode) {
        MODE_VERTICAL -> getString(R.string.page_mode_vertical)
        MODE_LONGSTRIP -> getString(R.string.page_mode_longstrip)
        else -> getString(R.string.page_mode_paged)
    }

    private fun updateEndCard() {
        val src = source ?: return
        val atEnd = when (mode) {
            MODE_LONGSTRIP -> !vertical.canScrollVertically(1)
            else -> currentIndex >= src.pageCount - 1
        }
        if (atEnd) showEndCard() else hideEndCard()
    }

    private fun showEndCard() {
        val f = file ?: return
        endCard.visibility = View.VISIBLE
        endSubtitle.text = getString(R.string.chapter_end_msg, chapterLabel(f))
        endNext.visibility = if (nextChapter() != null) View.VISIBLE else View.GONE
        endPrev.visibility = if (prevChapter() != null) View.VISIBLE else View.GONE
    }

    private fun hideEndCard() {
        endCard.visibility = View.GONE
    }

    private fun updateChapterButtons() {
        val hasNext = nextChapter() != null
        val hasPrev = prevChapter() != null
        btnNextChapter.isEnabled = hasNext
        btnNextChapter.alpha = if (hasNext) 1f else 0.3f
        btnPrevChapter.isEnabled = hasPrev
        btnPrevChapter.alpha = if (hasPrev) 1f else 0.3f
    }

    private fun saveHistory() {
        val f = file ?: return
        val count = source?.pageCount ?: return
        library.addHistory(
            HistoryItem(f.absolutePath, f.name, currentIndex, count, System.currentTimeMillis())
        )
    }

    // ------------------------------------------------------------------ chapters

    private fun siblingIndex(): Int = chapters.indexOfFirst { it.absolutePath == file?.absolutePath }

    private fun nextChapter(): File? {
        val i = siblingIndex()
        return if (i >= 0 && i + 1 < chapters.size) chapters[i + 1] else null
    }

    private fun prevChapter(): File? {
        val i = siblingIndex()
        return if (i > 0) chapters[i - 1] else null
    }

    private fun openNextChapter() {
        val next = nextChapter()
        if (next == null) {
            toast(R.string.no_next_chapter)
            return
        }
        openChapter(next, 0)
    }

    private fun openPrevChapter() {
        val prev = prevChapter()
        if (prev == null) {
            toast(R.string.no_prev_chapter)
            return
        }
        openChapter(prev, 0)
    }

    private fun openChapter(target: File, page: Int) {
        saveHistory()
        val intent = Intent(this, ViewerActivity::class.java)
            .putExtra(EXTRA_PATH, target.absolutePath)
            .putExtra(EXTRA_PAGE, page)
        // singleTop + onNewIntent: the reader reloads in place, so the back stack
        // never grows one activity per chapter.
        startActivity(intent)
    }

    private fun chapterLabel(f: File): String {
        val name = f.name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ------------------------------------------------------------------ gestures

    private fun onBrightnessDrag(dy: Float) {
        val range = 420f * resources.displayMetrics.density
        val delta = (dy / range) * 85f
        setBrightness((brightness + delta).toInt().coerceIn(20, 100))
    }

    private fun setBrightness(value: Int) {
        brightness = value.coerceIn(20, 100)
        prefs.brightness = brightness
        applyBrightness()
    }

    private fun applyBrightness() {
        dim.alpha = ((100 - brightness) / 100f) * 0.82f
    }

    private fun applyNight() {
        nightOverlay.visibility = if (nightFilter) View.VISIBLE else View.GONE
    }

    private fun applyKeepScreenOn() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        prefs.fullscreen = enabled
        invalidateOptionsMenu()
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        animateBar(toolbar, barsVisible)
        animateBar(bottomBar, barsVisible)
    }

    private fun animateBar(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(150).start()
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    goTo(currentIndex + 1); return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    goTo(currentIndex - 1); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_bookmark)?.setIcon(
            if (file?.let { library.isBookmarked(it.absolutePath, currentIndex) } == true)
                R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
        )
        menu.findItem(R.id.menu_keep_on)?.isChecked = keepOn
        menu.findItem(R.id.menu_night)?.isChecked = nightFilter
        menu.findItem(R.id.menu_fullscreen)?.isChecked = prefs.fullscreen
        menu.findItem(R.id.menu_two_page)?.apply {
            isChecked = twoPage
            isEnabled = mode == MODE_PAGED
        }
        menu.findItem(R.id.menu_dir_ltr)?.isChecked = readingDir == "ltr"
        menu.findItem(R.id.menu_dir_rtl)?.isChecked = readingDir == "rtl"
        menu.findItem(R.id.menu_mode_paged)?.isChecked = mode == MODE_PAGED
        menu.findItem(R.id.menu_mode_vertical)?.isChecked = mode == MODE_VERTICAL
        menu.findItem(R.id.menu_mode_longstrip)?.isChecked = mode == MODE_LONGSTRIP
        val fit = effectiveFit()
        menu.findItem(R.id.menu_fit_screen)?.isChecked = fit == ZoomImageView.FitMode.SCREEN
        menu.findItem(R.id.menu_fit_width)?.isChecked = fit == ZoomImageView.FitMode.WIDTH
        menu.findItem(R.id.menu_fit_height)?.isChecked = fit == ZoomImageView.FitMode.HEIGHT
        menu.findItem(R.id.menu_fit_stretch)?.isChecked = fit == ZoomImageView.FitMode.STRETCH
        menu.findItem(R.id.menu_filter_none)?.isChecked = filter == Filters.NONE
        menu.findItem(R.id.menu_filter_invert)?.isChecked = filter == Filters.INVERT
        menu.findItem(R.id.menu_filter_grayscale)?.isChecked = filter == Filters.GRAYSCALE
        menu.findItem(R.id.menu_filter_contrast)?.isChecked = filter == Filters.CONTRAST
        menu.findItem(R.id.menu_filter_sepia)?.isChecked = filter == Filters.SEPIA
        menu.findItem(R.id.menu_prev_chapter)?.isEnabled = prevChapter() != null
        menu.findItem(R.id.menu_next_chapter)?.isEnabled = nextChapter() != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_tune -> showReaderSheet()
            R.id.menu_jump -> showJumpDialog()
            R.id.menu_fit_screen -> setFit("screen")
            R.id.menu_fit_width -> setFit("width")
            R.id.menu_fit_height -> setFit("height")
            R.id.menu_fit_stretch -> setFit("stretch")
            R.id.menu_rotate -> cycleOrientation()
            R.id.menu_bookmark -> toggleBookmark()
            R.id.menu_bookmark_list -> showBookmarks()
            R.id.menu_pages -> openPages()
            R.id.menu_crop -> startCrop()
            R.id.menu_two_page -> toggleTwoPage()
            R.id.menu_dir_ltr -> setReadingDir("ltr")
            R.id.menu_dir_rtl -> setReadingDir("rtl")
            R.id.menu_mode_paged -> setPageMode(MODE_PAGED)
            R.id.menu_mode_vertical -> setPageMode(MODE_VERTICAL)
            R.id.menu_mode_longstrip -> setPageMode(MODE_LONGSTRIP)
            R.id.menu_filter_none -> setFilter(Filters.NONE)
            R.id.menu_filter_invert -> setFilter(Filters.INVERT)
            R.id.menu_filter_grayscale -> setFilter(Filters.GRAYSCALE)
            R.id.menu_filter_contrast -> setFilter(Filters.CONTRAST)
            R.id.menu_filter_sepia -> setFilter(Filters.SEPIA)
            R.id.menu_brightness -> showBrightnessDialog()
            R.id.menu_page_spacing -> showSpacingDialog()
            R.id.menu_prev_chapter -> openPrevChapter()
            R.id.menu_next_chapter -> openNextChapter()
            R.id.menu_orient_default ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            R.id.menu_orient_portrait ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            R.id.menu_orient_landscape ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            R.id.menu_keep_on -> {
                keepOn = !keepOn
                prefs.keepScreenOn = keepOn
                applyKeepScreenOn()
                invalidateOptionsMenu()
            }
            R.id.menu_night -> {
                nightFilter = !nightFilter
                prefs.nightFilter = nightFilter
                applyNight()
                invalidateOptionsMenu()
            }
            R.id.menu_fullscreen -> setFullscreen(!prefs.fullscreen)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ reader options

    private fun setFit(name: String) {
        prefs.fit = name
        adapter?.fitMode = effectiveFit()
        invalidateOptionsMenu()
    }

    private fun setFilter(name: String) {
        filter = name
        prefs.filter = name
        adapter?.filter = name
        invalidateOptionsMenu()
    }

    private fun setReadingDir(dir: String) {
        readingDir = dir
        prefs.readingDir = dir
        attach()
    }

    private fun setPageMode(newMode: String) {
        if (newMode == mode) return
        mode = newMode
        prefs.pageMode = newMode
        if (mode != MODE_PAGED && prefs.fit == "screen") prefs.fit = "width"
        attach()
    }

    private fun setSpacingDp(dp: Int) {
        spacingDp = dp.coerceIn(0, 48)
        prefs.pageSpacing = spacingDp
        adapter?.spacingPx = spacingPx()
    }

    private fun toggleTwoPage() {
        if (mode != MODE_PAGED) {
            toast(R.string.menu_spread)
            return
        }
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

    private fun showReaderSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_reader, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)

        val modeGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.sheet_page_mode)
        when (mode) {
            MODE_VERTICAL -> modeGroup.check(R.id.btn_mode_vertical)
            MODE_LONGSTRIP -> modeGroup.check(R.id.btn_mode_longstrip)
            else -> modeGroup.check(R.id.btn_mode_paged)
        }
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_mode_vertical -> setPageMode(MODE_VERTICAL)
                R.id.btn_mode_longstrip -> setPageMode(MODE_LONGSTRIP)
                R.id.btn_mode_paged -> setPageMode(MODE_PAGED)
            }
        }

        val fitGroup = view.findViewById<ChipGroup>(R.id.sheet_fit)
        when (effectiveFit()) {
            ZoomImageView.FitMode.WIDTH -> fitGroup.check(R.id.chip_fit_width)
            ZoomImageView.FitMode.HEIGHT -> fitGroup.check(R.id.chip_fit_height)
            ZoomImageView.FitMode.STRETCH -> fitGroup.check(R.id.chip_fit_stretch)
            else -> fitGroup.check(R.id.chip_fit_screen)
        }
        fitGroup.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.chip_fit_width -> setFit("width")
                R.id.chip_fit_height -> setFit("height")
                R.id.chip_fit_stretch -> setFit("stretch")
                R.id.chip_fit_screen -> setFit("screen")
            }
        }

        val filterGroup = view.findViewById<ChipGroup>(R.id.sheet_filter)
        filterGroup.check(
            when (filter) {
                Filters.INVERT -> R.id.chip_filter_invert
                Filters.GRAYSCALE -> R.id.chip_filter_grayscale
                Filters.CONTRAST -> R.id.chip_filter_contrast
                else -> R.id.chip_filter_none
            }
        )
        filterGroup.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.chip_filter_invert -> setFilter(Filters.INVERT)
                R.id.chip_filter_grayscale -> setFilter(Filters.GRAYSCALE)
                R.id.chip_filter_contrast -> setFilter(Filters.CONTRAST)
                R.id.chip_filter_none -> setFilter(Filters.NONE)
            }
        }

        val dirGroup = view.findViewById<ChipGroup>(R.id.sheet_dir)
        dirGroup.check(if (readingDir == "rtl") R.id.chip_dir_rtl else R.id.chip_dir_ltr)
        dirGroup.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.chip_dir_rtl -> setReadingDir("rtl")
                R.id.chip_dir_ltr -> setReadingDir("ltr")
            }
        }

        val brightnessLabel = view.findViewById<TextView>(R.id.sheet_brightness_value)
        val brightnessSlider = view.findViewById<Slider>(R.id.sheet_brightness)
        brightnessLabel.text =
            getString(R.string.menu_brightness) + " · " + getString(R.string.percent_format, brightness)
        brightnessSlider.safeSet(brightness.toFloat())
        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setBrightness(value.toInt())
            brightnessLabel.text =
                getString(R.string.menu_brightness) + " · " + getString(R.string.percent_format, brightness)
        }

        val spacingLabel = view.findViewById<TextView>(R.id.sheet_spacing_value)
        val spacingSlider = view.findViewById<Slider>(R.id.sheet_spacing)
        spacingLabel.text = getString(R.string.menu_page_spacing) + " · $spacingDp dp"
        spacingSlider.safeSet(spacingDp.toFloat())
        spacingSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setSpacingDp(value.toInt())
            spacingLabel.text = getString(R.string.menu_page_spacing) + " · $spacingDp dp"
        }

        val twoPageSwitch = view.findViewById<MaterialSwitch>(R.id.sheet_two_page)
        twoPageSwitch.isChecked = twoPage
        twoPageSwitch.isEnabled = mode == MODE_PAGED
        twoPageSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != twoPage) toggleTwoPage()
        }

        val nightSwitch = view.findViewById<MaterialSwitch>(R.id.sheet_night)
        nightSwitch.isChecked = nightFilter
        nightSwitch.setOnCheckedChangeListener { _, checked ->
            nightFilter = checked
            prefs.nightFilter = checked
            applyNight()
            invalidateOptionsMenu()
        }

        val keepSwitch = view.findViewById<MaterialSwitch>(R.id.sheet_keep_on)
        keepSwitch.isChecked = keepOn
        keepSwitch.setOnCheckedChangeListener { _, checked ->
            keepOn = checked
            prefs.keepScreenOn = checked
            applyKeepScreenOn()
            invalidateOptionsMenu()
        }

        view.findViewById<View>(R.id.sheet_more_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        dialog.show()
    }

    /** Snaps a value onto the slider's step grid before assigning it. */
    private fun Slider.safeSet(value: Float) {
        val step = stepSize
        val snapped = if (step > 0f) valueFrom + Math.round((value - valueFrom) / step) * step else value
        this.value = snapped.coerceIn(valueFrom, valueTo)
    }

    private fun showBrightnessDialog() {
        val slider = Slider(this).apply {
            valueFrom = 20f
            valueTo = 100f
            stepSize = 5f
            value = (brightness / 5 * 5).toFloat().coerceIn(20f, 100f)
        }
        slider.addOnChangeListener { _, value, _ -> setBrightness(value.toInt()) }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(
                slider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_brightness)
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showSpacingDialog() {
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 48f
            stepSize = 4f
            value = (spacingDp / 4 * 4).toFloat().coerceIn(0f, 48f)
        }
        slider.addOnChangeListener { _, value, _ -> setSpacingDp(value.toInt()) }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(
                slider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_page_spacing)
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ bookmarks

    private fun toggleBookmark() {
        val f = file ?: return
        val path = f.absolutePath
        if (library.isBookmarked(path, currentIndex)) {
            library.removeBookmark(path, currentIndex)
            toast(R.string.bookmark_removed)
        } else {
            library.addBookmark(Bookmark(path, f.name, currentIndex, "", System.currentTimeMillis()))
            toast(R.string.bookmark_saved)
        }
        invalidateOptionsMenu()
    }

    private fun showBookmarks() {
        val f = file ?: return
        val marks = library.bookmarksFor(f.absolutePath)
        if (marks.isEmpty()) {
            toast(R.string.no_bookmarks_in_book)
            return
        }
        val count = source?.pageCount ?: 0
        val labels = marks.map { getString(R.string.page_indicator, it.page + 1, count) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmark_list)
            .setItems(labels) { _, which -> goTo(marks[which].page) }
            .setNeutralButton(R.string.clear) { _, _ ->
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

        private const val MODE_PAGED = "paged"
        private const val MODE_VERTICAL = "vertical"
        private const val MODE_LONGSTRIP = "longstrip"
    }
}
