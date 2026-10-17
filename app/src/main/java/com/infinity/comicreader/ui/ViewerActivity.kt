package com.infinity.comicreader.ui

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.core.content.FileProvider
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
import com.infinity.comicreader.data.SeriesSettings
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.BitSet

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
    private var fit = "screen"
    private var autoCrop = false

    /** True when this folder has its own remembered layout (mode, direction, fit, spread). */
    private var seriesActive = false

    private var openJob: Job? = null
    private var endDismissed = false
    private var markedRead = false

    // auto-scroll
    private val handler = Handler(Looper.getMainLooper())
    private var autoScrolling = false
    private var autoRemainder = 0f
    private var autoLastFrame = 0L

    // reading statistics for the current session
    private var sessionStart = 0L
    private val seenPages = BitSet()

    private var cropSource: File? = null

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(CropActivity.EXTRA_RESULT)?.let {
                    toast(getString(R.string.crop_saved, it))
                }
            }
            deleteCropSource()
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
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) stopAutoScroll()
            }

            override fun onPageSelected(position: Int) {
                if (mode != MODE_PAGED) return
                currentIndex = itemToPage(position)
                seek.progress = position
                onPageChanged()
            }
        })
        vertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Touching the list takes over from auto-scroll.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) stopAutoScroll()
            }

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

            override fun onStartTrackingTouch(sb: SeekBar) {
                // Intentionally empty: the page is updated in onProgressChanged only.
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                // Intentionally empty: the page is updated in onProgressChanged only.
            }
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
        findViewById<View>(R.id.end_close).setOnClickListener {
            endDismissed = true
            hideEndCard()
        }

        open()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopAutoScroll()
        flushStats()
        saveHistory()
        openJob?.cancel()
        closeSource()
        endDismissed = false
        hideEndCard()
        open()
    }

    override fun onStart() {
        super.onStart()
        sessionStart = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        super.onStop()
        stopAutoScroll()
        saveHistory()
        flushStats()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScroll()
        openJob?.cancel()
        closeSource()
        deleteCropSource()
    }

    /** Adds this session's reading time and newly seen pages to the statistics. */
    private fun flushStats() {
        if (sessionStart == 0L) return
        val seconds = ((SystemClock.elapsedRealtime() - sessionStart) / 1000L)
            .coerceIn(0L, MAX_SESSION_SECONDS)
        val pages = seenPages.cardinality()
        sessionStart = SystemClock.elapsedRealtime()
        seenPages.clear()
        if (source != null) library.addReading(seconds, pages)
    }

    private fun deleteCropSource() {
        cropSource?.let { runCatching { it.delete() } }
        cropSource = null
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
        fit = prefs.fit
        autoCrop = prefs.autoCrop
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

    /** Applies the folder's remembered layout, if it has one and the feature is on. */
    private fun applySeries(folder: String) {
        val saved = if (prefs.perSeries) library.seriesFor(folder) else null
        seriesActive = saved != null
        if (saved == null) return
        mode = saved.mode.takeIf { it == MODE_PAGED || it == MODE_VERTICAL || it == MODE_LONGSTRIP } ?: MODE_PAGED
        readingDir = if (saved.dir == "rtl") "rtl" else "ltr"
        fit = saved.fit
        twoPage = saved.twoPage
    }

    /**
     * Stores the layout options: with the folder's own settings when it remembers them,
     * otherwise as the global defaults.
     */
    private fun persistLayout() {
        val folder = file?.parentFile?.absolutePath
        if (seriesActive && folder != null) {
            library.setSeries(folder, SeriesSettings(mode, readingDir, fit, twoPage))
        } else {
            prefs.pageMode = mode
            prefs.readingDir = readingDir
            prefs.fit = fit
            prefs.twoPage = twoPage
        }
    }

    private fun toggleSeries() {
        val folder = file?.parentFile?.absolutePath ?: return
        seriesActive = !seriesActive
        if (seriesActive) {
            library.setSeries(folder, SeriesSettings(mode, readingDir, fit, twoPage))
            toast(R.string.series_saved)
        } else {
            library.setSeries(folder, null)
            toast(R.string.series_cleared)
        }
        invalidateOptionsMenu()
    }

    // ------------------------------------------------------------------ loading

    private fun open() {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val target = File(path)
        file = target
        toolbar.title = chapterLabel(target)
        progress.visibility = View.VISIBLE
        markedRead = false
        mode = prefs.pageMode
        readingDir = prefs.readingDir
        fit = prefs.fit
        twoPage = prefs.twoPage
        target.parentFile?.absolutePath?.let { applySeries(it) }

        openJob = lifecycleScope.launch {
            val (src, siblings) = withContext(AppDispatchers.io) {
                val s = runCatching { PageSources.open(target, prefs.charset) }.getOrNull()
                try {
                    ensureActive()
                } catch (e: CancellationException) {
                    // A newer open() replaced this one: do not leak the file handle.
                    runCatching { s?.close() }
                    throw e
                }
                s to siblingChapters(target)
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
        seenPages.clear()
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
            onVerticalDrag = if (brightnessGestureEnabled()) ({ dy -> onBrightnessDrag(dy) }) else null,
            autoCrop = autoCrop,
            preload = prefs.preload
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
        val name = if (mode != MODE_PAGED && fit == "screen") "width" else fit
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
        if (currentIndex in 0 until count) seenPages.set(currentIndex)
        if (!markedRead && count > 0 && currentIndex >= count - 1) {
            markedRead = true
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
        if (!atEnd) {
            endDismissed = false
            hideEndCard()
        } else if (!endDismissed) {
            showEndCard()
        }
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

    private fun setNight(enabled: Boolean) {
        if (nightFilter == enabled) return
        nightFilter = enabled
        prefs.nightFilter = enabled
        applyNight()
        invalidateOptionsMenu()
    }

    private fun toggleNight() = setNight(!nightFilter)

    private fun applyKeepScreenOn() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setKeepOn(enabled: Boolean) {
        if (keepOn == enabled) return
        keepOn = enabled
        prefs.keepScreenOn = enabled
        applyKeepScreenOn()
        invalidateOptionsMenu()
    }

    private fun toggleKeepOn() = setKeepOn(!keepOn)

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
        if (prefs.volumeKeys && isVolumeKey(keyCode)) {
            // Auto-repeat would skip pages while the key is held; one press, one page.
            if (event.repeatCount == 0) {
                stopAutoScroll()
                goTo(currentIndex + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Swallow the key-up too, otherwise the system still shows its volume UI. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

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
        // Items in a single-choice group must only ever be set to checked: calling
        // setChecked(false) on one of them re-checks it, which left the last item of
        // every group showing as selected no matter what was active.
        checkOnly(menu, if (readingDir == "rtl") R.id.menu_dir_rtl else R.id.menu_dir_ltr)
        checkOnly(
            menu,
            when (mode) {
                MODE_VERTICAL -> R.id.menu_mode_vertical
                MODE_LONGSTRIP -> R.id.menu_mode_longstrip
                else -> R.id.menu_mode_paged
            }
        )
        checkOnly(
            menu,
            when (effectiveFit()) {
                ZoomImageView.FitMode.WIDTH -> R.id.menu_fit_width
                ZoomImageView.FitMode.HEIGHT -> R.id.menu_fit_height
                ZoomImageView.FitMode.STRETCH -> R.id.menu_fit_stretch
                else -> R.id.menu_fit_screen
            }
        )
        checkOnly(
            menu,
            when (filter) {
                Filters.INVERT -> R.id.menu_filter_invert
                Filters.GRAYSCALE -> R.id.menu_filter_grayscale
                Filters.CONTRAST -> R.id.menu_filter_contrast
                Filters.SEPIA -> R.id.menu_filter_sepia
                else -> R.id.menu_filter_none
            }
        )
        checkOnly(
            menu,
            when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> R.id.menu_orient_portrait
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> R.id.menu_orient_landscape
                else -> R.id.menu_orient_default
            }
        )
        menu.findItem(R.id.menu_auto_scroll)?.isChecked = autoScrolling
        menu.findItem(R.id.menu_auto_crop)?.apply {
            isChecked = autoCrop
            isEnabled = mode != MODE_LONGSTRIP
        }
        menu.findItem(R.id.menu_series)?.apply {
            isVisible = prefs.perSeries
            isChecked = seriesActive
        }
        menu.findItem(R.id.menu_chapters)?.isEnabled = chapters.size > 1
        menu.findItem(R.id.menu_prev_chapter)?.isEnabled = prevChapter() != null
        menu.findItem(R.id.menu_next_chapter)?.isEnabled = nextChapter() != null
        return super.onPrepareOptionsMenu(menu)
    }

    private fun checkOnly(menu: Menu, id: Int) {
        menu.findItem(id)?.isChecked = true
    }

    /**
     * Menu items whose entire action is "assign a reader preference". They live in a
     * lookup instead of being spelled out in [onOptionsItemSelected], which keeps that
     * `when` comfortably under the branch limit enforced by static analysis.
     */
    private val valueMenus: Map<Int, () -> Unit> by lazy {
        mapOf(
            R.id.menu_fit_screen to { setFit("screen") },
            R.id.menu_fit_width to { setFit("width") },
            R.id.menu_fit_height to { setFit("height") },
            R.id.menu_fit_stretch to { setFit("stretch") },
            R.id.menu_filter_none to { setFilter(Filters.NONE) },
            R.id.menu_filter_invert to { setFilter(Filters.INVERT) },
            R.id.menu_filter_grayscale to { setFilter(Filters.GRAYSCALE) },
            R.id.menu_filter_contrast to { setFilter(Filters.CONTRAST) },
            R.id.menu_filter_sepia to { setFilter(Filters.SEPIA) },
            R.id.menu_mode_paged to { setPageMode(MODE_PAGED) },
            R.id.menu_mode_vertical to { setPageMode(MODE_VERTICAL) },
            R.id.menu_mode_longstrip to { setPageMode(MODE_LONGSTRIP) },
            R.id.menu_dir_ltr to { setReadingDir("ltr") },
            R.id.menu_dir_rtl to { setReadingDir("rtl") },
            R.id.menu_orient_default to { setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) },
            R.id.menu_orient_portrait to { setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) },
            R.id.menu_orient_landscape to { setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        val assign = valueMenus[id]
        if (assign != null) {
            assign()
            return true
        }
        return when (id) {
            R.id.menu_tune -> { showReaderSheet(); true }
            R.id.menu_chapters -> { showChapterList(); true }
            R.id.menu_auto_scroll -> { toggleAutoScroll(); true }
            R.id.menu_auto_speed -> { showAutoSpeedDialog(); true }
            R.id.menu_auto_crop -> { toggleAutoCrop(); true }
            R.id.menu_series -> { toggleSeries(); true }
            R.id.menu_share_page -> { exportPage(share = true); true }
            R.id.menu_save_page -> { exportPage(share = false); true }
            R.id.menu_jump -> { showJumpDialog(); true }
            R.id.menu_rotate -> { cycleOrientation(); true }
            R.id.menu_bookmark -> { toggleBookmark(); true }
            R.id.menu_bookmark_list -> { showBookmarks(); true }
            R.id.menu_pages -> { openPages(); true }
            R.id.menu_crop -> { startCrop(); true }
            R.id.menu_two_page -> { toggleTwoPage(); true }
            R.id.menu_brightness -> { showBrightnessDialog(); true }
            R.id.menu_page_spacing -> { showSpacingDialog(); true }
            R.id.menu_prev_chapter -> { openPrevChapter(); true }
            R.id.menu_next_chapter -> { openNextChapter(); true }
            R.id.menu_keep_on -> { toggleKeepOn(); true }
            R.id.menu_night -> { toggleNight(); true }
            R.id.menu_fullscreen -> { setFullscreen(!prefs.fullscreen); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------ reader options

    private fun setFit(name: String) {
        fit = name
        persistLayout()
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
        if (readingDir == dir) return
        readingDir = dir
        persistLayout()
        attach()
    }

    private fun setPageMode(newMode: String) {
        if (newMode == mode) return
        stopAutoScroll()
        mode = newMode
        persistLayout()
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
        persistLayout()
        attach()
    }

    private fun setOrientationLock(value: Int) {
        requestedOrientation = value
        invalidateOptionsMenu()
    }

    private fun cycleOrientation() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        setOrientationLock(
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        )
    }

    private fun showReaderSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_reader, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)

        bindModeSection(view)
        bindFitSection(view)
        bindFilterSection(view)
        bindDirectionSection(view)
        bindBrightnessSection(view)
        bindSpacingSection(view)
        bindToggleSection(view)

        view.findViewById<View>(R.id.sheet_more_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        dialog.show()
    }

    private fun bindModeSection(view: View) {
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.sheet_page_mode)
        group.check(
            when (mode) {
                MODE_VERTICAL -> R.id.btn_mode_vertical
                MODE_LONGSTRIP -> R.id.btn_mode_longstrip
                else -> R.id.btn_mode_paged
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_mode_vertical -> setPageMode(MODE_VERTICAL)
                R.id.btn_mode_longstrip -> setPageMode(MODE_LONGSTRIP)
                R.id.btn_mode_paged -> setPageMode(MODE_PAGED)
            }
        }
    }

    private fun bindFitSection(view: View) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_fit)
        group.check(
            when (effectiveFit()) {
                ZoomImageView.FitMode.WIDTH -> R.id.chip_fit_width
                ZoomImageView.FitMode.HEIGHT -> R.id.chip_fit_height
                ZoomImageView.FitMode.STRETCH -> R.id.chip_fit_stretch
                else -> R.id.chip_fit_screen
            }
        )
        group.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.chip_fit_width -> setFit("width")
                R.id.chip_fit_height -> setFit("height")
                R.id.chip_fit_stretch -> setFit("stretch")
                R.id.chip_fit_screen -> setFit("screen")
            }
        }
    }

    private fun bindFilterSection(view: View) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_filter)
        group.check(
            when (filter) {
                Filters.INVERT -> R.id.chip_filter_invert
                Filters.GRAYSCALE -> R.id.chip_filter_grayscale
                Filters.CONTRAST -> R.id.chip_filter_contrast
                Filters.SEPIA -> R.id.chip_filter_sepia
                else -> R.id.chip_filter_none
            }
        )
        group.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.chip_filter_invert -> setFilter(Filters.INVERT)
                R.id.chip_filter_grayscale -> setFilter(Filters.GRAYSCALE)
                R.id.chip_filter_contrast -> setFilter(Filters.CONTRAST)
                R.id.chip_filter_sepia -> setFilter(Filters.SEPIA)
                R.id.chip_filter_none -> setFilter(Filters.NONE)
            }
        }
    }

    private fun bindDirectionSection(view: View) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_dir)
        group.check(if (readingDir == "rtl") R.id.chip_dir_rtl else R.id.chip_dir_ltr)
        group.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.chip_dir_rtl -> setReadingDir("rtl")
                R.id.chip_dir_ltr -> setReadingDir("ltr")
            }
        }
    }

    private fun bindBrightnessSection(view: View) {
        val label = view.findViewById<TextView>(R.id.sheet_brightness_value)
        val slider = view.findViewById<Slider>(R.id.sheet_brightness)
        label.text = brightnessLabel()
        slider.safeSet(brightness.toFloat())
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setBrightness(value.toInt())
            label.text = brightnessLabel()
        }
    }

    private fun brightnessLabel(): String =
        getString(R.string.menu_brightness) + " · " + getString(R.string.percent_format, brightness)

    private fun bindSpacingSection(view: View) {
        val label = view.findViewById<TextView>(R.id.sheet_spacing_value)
        val slider = view.findViewById<Slider>(R.id.sheet_spacing)
        label.text = spacingLabel()
        slider.safeSet(spacingDp.toFloat())
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setSpacingDp(value.toInt())
            label.text = spacingLabel()
        }
    }

    private fun spacingLabel(): String =
        getString(R.string.menu_page_spacing) + " · $spacingDp dp"

    private fun bindToggleSection(view: View) {
        val twoPageSwitch = view.findViewById<MaterialSwitch>(R.id.sheet_two_page)
        twoPageSwitch.isChecked = twoPage
        twoPageSwitch.isEnabled = mode == MODE_PAGED
        twoPageSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != twoPage) toggleTwoPage()
        }

        val nightSwitch = view.findViewById<MaterialSwitch>(R.id.sheet_night)
        nightSwitch.isChecked = nightFilter
        nightSwitch.setOnCheckedChangeListener { _, checked -> setNight(checked) }

        val keepSwitch = view.findViewById<MaterialSwitch>(R.id.sheet_keep_on)
        keepSwitch.isChecked = keepOn
        keepSwitch.setOnCheckedChangeListener { _, checked -> setKeepOn(checked) }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_brightness)
            .setView(sliderContainer(slider))
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
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_page_spacing)
            .setView(sliderContainer(slider))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ auto-scroll

    private fun toggleAutoScroll() {
        if (autoScrolling) stopAutoScroll() else startAutoScroll()
    }

    private fun startAutoScroll() {
        if (source == null || autoScrolling) return
        autoScrolling = true
        autoRemainder = 0f
        autoLastFrame = SystemClock.uptimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (barsVisible) toggleBars()
        handler.post(autoTick)
        invalidateOptionsMenu()
    }

    private fun stopAutoScroll() {
        if (!autoScrolling) return
        autoScrolling = false
        handler.removeCallbacks(autoTick)
        applyKeepScreenOn()
        invalidateOptionsMenu()
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoScrolling) return
            if (mode == MODE_PAGED) {
                val count = source?.pageCount ?: return
                val step = if (useSpread()) 2 else 1
                if (currentIndex + step >= count) {
                    stopAutoScroll()
                    return
                }
                goTo(currentIndex + step)
                handler.postDelayed(this, PAGE_INTERVAL_MS_BASE / prefs.autoSpeed)
            } else {
                val now = SystemClock.uptimeMillis()
                val dt = (now - autoLastFrame).coerceAtMost(100L)
                autoLastFrame = now
                autoRemainder += prefs.autoSpeed * DP_PER_SECOND_PER_STEP *
                    resources.displayMetrics.density * dt / 1000f
                val px = autoRemainder.toInt()
                autoRemainder -= px
                if (px > 0) vertical.scrollBy(0, px)
                if (!vertical.canScrollVertically(1)) {
                    stopAutoScroll()
                    return
                }
                handler.postDelayed(this, FRAME_MS)
            }
        }
    }

    private fun showAutoSpeedDialog() {
        val slider = Slider(this).apply {
            valueFrom = 1f
            valueTo = 10f
            stepSize = 1f
            value = prefs.autoSpeed.toFloat()
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_speed_value, prefs.autoSpeed))
            .setPositiveButton(R.string.ok, null)
        val dialog = builder.create()
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.autoSpeed = value.toInt()
            dialog.setTitle(getString(R.string.auto_speed_value, prefs.autoSpeed))
        }
        dialog.setView(sliderContainer(slider))
        dialog.show()
    }

    private fun sliderContainer(slider: Slider): LinearLayout = LinearLayout(this).apply {
        setPadding(48, 24, 48, 0)
        addView(
            slider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun toggleAutoCrop() {
        autoCrop = !autoCrop
        prefs.autoCrop = autoCrop
        attach()
    }

    // ------------------------------------------------------------------ chapter list

    private fun showChapterList() {
        val list = chapters
        if (list.size < 2) return
        val read = library.readSet()
        val current = siblingIndex()
        val labels = list.mapIndexed { i, f ->
            val mark = when {
                i == current -> "▶ "
                f.absolutePath in read -> "✓ "
                else -> "     "
            }
            mark + chapterLabel(f)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chapters_title)
            .setItems(labels) { _, which -> if (which != current) openChapter(list[which], 0) }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
        if (current > 2) dialog.listView.setSelection(current - 2)
    }

    // ------------------------------------------------------------------ share / save page

    private fun fileSafeName(): String {
        val base = file?.let { chapterLabel(it) } ?: "page"
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + "_p" + (currentIndex + 1)
    }

    /** Renders the current page at full resolution and either shares or saves it. */
    private fun exportPage(share: Boolean) {
        val src = source ?: return
        val page = currentIndex
        val name = fileSafeName()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = withContext(AppDispatchers.io) {
                runCatching {
                    val bitmap = src.loadPage(page, EXPORT_W, EXPORT_H)
                    if (share) shareUri(bitmap, name) else {
                        savePicture(bitmap, name)
                        null
                    }
                }
            }
            progress.visibility = View.GONE
            val uri = outcome.getOrNull()
            when {
                outcome.isFailure -> toast(R.string.error_load)
                share && uri != null -> startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.menu_share_page)
                    )
                )
                else -> toast(R.string.page_saved)
            }
        }
    }

    /** Writes [bitmap] under cache/shared (exposed through the FileProvider) and returns its URI. */
    private fun shareUri(bitmap: Bitmap, name: String): android.net.Uri {
        val dir = File(cacheDir, SHARE_DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { runCatching { it.delete() } } // keep only the latest shared page
        val out = File(dir, "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(this, "$packageName.files", out)
    }

    private fun savePicture(bitmap: Bitmap, name: String) {
        val stamp = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${name}_$stamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComicReader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the picture")
            try {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(cacheDir, "pictures")
            dir.mkdirs()
            File(dir, "${name}_$stamp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
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
            val bitmap = withContext(AppDispatchers.io) {
                try {
                    src.loadPage(page, 2400, 3200)
                } catch (t: Throwable) {
                    null
                }
            }
            val outFile = if (bitmap == null) null else withContext(AppDispatchers.io) {
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
            cropSource = outFile
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

        private const val SHARE_DIR = "shared"
        private const val EXPORT_W = 2400
        private const val EXPORT_H = 3200
        private const val MAX_SESSION_SECONDS = 4L * 3600
        private const val PAGE_INTERVAL_MS_BASE = 13_000L
        private const val DP_PER_SECOND_PER_STEP = 30f
        private const val FRAME_MS = 16L

        private const val MODE_PAGED = "paged"
        private const val MODE_VERTICAL = "vertical"
        private const val MODE_LONGSTRIP = "longstrip"
    }
}
