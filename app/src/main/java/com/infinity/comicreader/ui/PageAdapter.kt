package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders [PageSource] pages into zoomable images.
 *
 * Three layouts share one adapter:
 *  - [Mode.PAGED]     horizontal [androidx.viewpager2.widget.ViewPager2], one page
 *                     (or a two-page spread) at a time;
 *  - [Mode.VERTICAL]  vertical list, one full-height page per screen;
 *  - [Mode.LONG_STRIP] continuous vertical strip sized from each page's aspect
 *                     ratio with no gaps, as used by webtoons/manhwa.
 *
 * Decoding happens off the main thread, at most [DECODE_THREADS] pages at a time.
 * Results live in a byte-budgeted cache, a page that is requested twice shares one
 * decode, a decode nobody is waiting for any more is dropped before it starts, and
 * the next [preload] pages are decoded ahead of time. With [autoCrop] blank borders
 * are trimmed (never in long-strip mode, where the gaps belong to the art).
 *
 * A [RecyclerView.Adapter] has a fixed item view type, so callers build a fresh
 * instance whenever the mode, spread, reading direction or [autoCrop] changes.
 *
 * Vertical and long-strip layouts may hold more than one chapter ([append]), with
 * [dropTo]/[dropAfter] trimming chapters off either end; pages are addressed by
 * their absolute number in the strip, so the cache survives trims untouched.
 */
class PageAdapter(
    source: PageSource,
    private val mode: Mode,
    private val onTap: (Float) -> Unit,
    private val doubleTapZoom: Boolean,
    private val twoPage: Boolean = false,
    private val rtl: Boolean = false,
    private val onVerticalDrag: ((Float) -> Unit)? = null,
    private val autoCrop: Boolean = false,
    private val preload: Int = 2
) : RecyclerView.Adapter<PageAdapter.PageHolder>() {

    enum class Mode { PAGED, VERTICAL, LONG_STRIP }

    /** Margin between pages; ignored for long strips, which must be contiguous. */
    var spacingPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    var fitMode: ZoomImageView.FitMode = ZoomImageView.FitMode.SCREEN
        set(value) {
            if (field == value) return
            field = value
            // Paged pages are decoded for the active fit mode, so they must be redone.
            if (mode == Mode.PAGED) dropDecoded()
            notifyItemRangeChanged(0, itemCount)
        }

    var filter: String = Filters.NONE
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private class Pending(val deferred: Deferred<Bitmap?>) {
        var waiters = 0
        var prefetch = false
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val gate = Semaphore(DECODE_THREADS)
    private val cache = object : LruCache<Int, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val pending = HashMap<Int, Pending>()
    private val aspects = ConcurrentHashMap<Int, Float>()
    private var learnedAspect = 1.45f
    private var recyclerWidth = 0
    private var fallbackWidth = 1080
    private var lastReqW = 1080
    private var lastReqH = 1920
    private var layoutListener: View.OnLayoutChangeListener? = null

    private val spread = twoPage && mode == Mode.PAGED

    /** A chapter laid into the strip: [first] is its first page in the whole strip. */
    private class Segment(
        val source: PageSource,
        val first: Int,
        val count: Int,
        var loads: AtomicInteger = AtomicInteger(0),
        @Volatile var wanted: Boolean = true,
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    /**
     * The chapters currently in the strip, in reading order. A vertical or long-strip
     * reader with "auto next chapter" on appends the following chapter, so scrolling
     * simply continues into it instead of stopping at the chapter's end. [base] is
     * the strip page item 0 shows, which lets chapters already read past be trimmed
     * away without renumbering the decoded-page cache.
     *
     * This list owns its sources: a chapter stays open while its pages can still be
     * addressed by absolute index, and is closed once it is trimmed off the strip or
     * the adapter is released, whichever comes last. Closing a chapter the moment it
     * is scrolled past would race with decodes that are still in flight for it.
     */
    private val segments = ArrayList<Segment>()
    private val total get() = segments.sumOf { it.count }
    private val end get() = segments.lastOrNull()?.let { it.first + it.count } ?: 0
    private var base = 0
    private var released = false

    init {
        val first = if (spread) (source.pageCount + 1) / 2 else source.pageCount
        segments += Segment(source, 0, first)
    }

    inner class PageHolder(val container: View, val images: List<ZoomImageView>) :
        RecyclerView.ViewHolder(container) {
        /** The page each image is showing or waiting for, and the coroutine waiting for it. */
        val pages = IntArray(images.size) { NO_PAGE }
        val waiters = arrayOfNulls<Job>(images.size)
    }

    override fun getItemCount(): Int = (total - base).coerceAtLeast(0)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerWidth = recyclerView.width
        val listener = View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != recyclerWidth) {
                recyclerWidth = width
                if (mode == Mode.LONG_STRIP) notifyItemRangeChanged(0, itemCount)
            }
            if (v.width > 0) fallbackWidth = v.width
        }
        layoutListener = listener
        recyclerView.addOnLayoutChangeListener(listener)
        if (mode == Mode.LONG_STRIP) learnAspect()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        layoutListener?.let { recyclerView.removeOnLayoutChangeListener(it) }
        layoutListener = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val context = parent.context
        if (parent.width > 0) fallbackWidth = parent.width
        if (spread) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val left = makeImage(context)
            val right = makeImage(context)
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return PageHolder(row, listOf(left, right))
        }
        val container = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val image = makeImage(context)
        container.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return PageHolder(container, listOf(image))
    }

    private fun makeImage(context: Context): ZoomImageView = ZoomImageView(context).apply {
        doubleTapEnabled = doubleTapZoom
        onTap = { fraction -> this@PageAdapter.onTap(fraction) }
        onVerticalDrag = this@PageAdapter.onVerticalDrag
        colorFilter = Filters.colorFilter(this@PageAdapter.filter)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        // [position] is an item index; strip pages keep their chapter-relative number,
        // so shift by [base] (0 in paged mode, where nothing is ever appended).
        val page = position + base
        if (spread) {
            val firstPage = if (rtl) page * 2 + 1 else page * 2
            val secondPage = if (rtl) page * 2 else page * 2 + 1
            bindImage(holder, 0, firstPage)
            bindImage(holder, 1, secondPage)
            prefetchAfter(page * 2 + 1)
        } else {
            bindImage(holder, 0, page)
            prefetchAfter(page)
        }
    }

    /**
     * Lays the pages of [next] in behind the ones already shown, so a strip reader
     * rolls from this chapter's last page straight into the next one. Refused in
     * paged mode, whose pages are laid out two at a time and never rolled over.
     * Main thread only.
     */
    fun append(next: PageSource): Boolean {
        if (spread || next.pageCount <= 0 || released) return false
        segments += Segment(next, end, next.pageCount)
        val start = end - next.pageCount - base
        notifyItemRangeInserted(start, next.pageCount)
        return true
    }

    /**
     * Forgets every strip item before [absolute] (a chapter that was read past),
     * leaving item 0 to show that page. Keeps the decoded-page cache valid because
     * pages are addressed by their absolute number, not by item index.
     * Main thread only.
     */
    fun dropTo(absolute: Int) {
        val keep = absolute.coerceIn(base, end)
        if (keep == base) return
        forget { it.first + it.count <= keep }
        val removed = keep - base
        base = keep
        notifyItemRangeRemoved(0, removed)
    }

    /**
     * Undoes an append: throws away every chapter starting at [first] (used when
     * "auto next chapter" is switched off again mid-chapter). Main thread only.
     */
    fun dropAfter(first: Int) {
        if (first >= end) return
        val removed = end - first
        forget { it.first >= first }
        notifyItemRangeRemoved(first - base, removed)
    }

    /**
     * Drops the chapters [gone] picks, closing the ones leaving the strip. A chapter
     * stays open while its pages can still be addressed by absolute index, so the
     * bookkeeping above is all that runs while it is merely trimmed from the layout.
     */
    private fun forget(gone: (Segment) -> Boolean) {
        segments.filter(gone).forEach { retire(it) }
        segments.removeAll(gone)
    }

    /** Releases a chapter once it is no longer wanted and no decodes are in flight. */
    private fun retire(seg: Segment) {
        seg.wanted = false
        if (seg.loads.get() == 0) closeOnce(seg)
    }

    private fun closeOnce(seg: Segment) {
        if (seg.closed.compareAndSet(false, true)) {
            runCatching { seg.source.close() }
        }
    }

    /** Decrements the load count; closes the source if no more wanted and no more loads. */
    private fun releaseLoad(seg: Segment) {
        if (seg.loads.decrementAndGet() == 0 && !seg.wanted) closeOnce(seg)
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        for (i in holder.images.indices) {
            stopWaiting(holder, i)
            holder.images[i].setImageDrawable(null)
        }
    }

    // ------------------------------------------------------------------ binding

    private fun bindImage(holder: PageHolder, slot: Int, pageIndex: Int) {
        val image = holder.images[slot]
        stopWaiting(holder, slot)
        if (pageIndex < 0 || pageIndex >= total) {
            image.setImageDrawable(null)
            return
        }
        image.colorFilter = Filters.colorFilter(filter)
        prepareContainer(holder, pageIndex)

        val cached = cache.get(pageIndex)
        if (cached != null) {
            applyBitmap(image, cached)
            return
        }
        image.setImageDrawable(null)

        val (reqW, reqH) = requestSize(image)
        lastReqW = reqW
        lastReqH = reqH
        val entry = request(pageIndex, reqW, reqH)
        entry.waiters++
        holder.pages[slot] = pageIndex
        holder.waiters[slot] = scope.launch {
            val bitmap = entry.deferred.await() ?: return@launch
            applyBitmap(image, bitmap)
            updateLongStripAspect(holder, pageIndex, bitmap)
        }
    }

    /** Forgets the page [slot] was waiting for; abandons its decode if nobody else wants it. */
    private fun stopWaiting(holder: PageHolder, slot: Int) {
        holder.waiters[slot]?.cancel()
        holder.waiters[slot] = null
        val page = holder.pages[slot]
        holder.pages[slot] = NO_PAGE
        if (page == NO_PAGE) return
        val entry = pending[page] ?: return
        entry.waiters--
        if (entry.waiters <= 0 && !entry.prefetch && entry.deferred.isActive) {
            entry.deferred.cancel()
            pending.remove(page)
        }
    }

    /** Applies the per-mode container geometry (long-strip height, or page spacing). */
    private fun prepareContainer(holder: PageHolder, pageIndex: Int) {
        if (mode == Mode.LONG_STRIP) {
            applyHeight(holder.container, longStripHeight(pageIndex))
            holder.container.setPadding(0, 0, 0, 0)
        } else if (holder.container is FrameLayout) {
            holder.container.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        }
    }

    private fun applyBitmap(image: ZoomImageView, bitmap: Bitmap) {
        image.setImageBitmap(bitmap)
        image.setFitMode(fitMode)
    }

    // ------------------------------------------------------------------ decoding

    private fun cacheBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 6L).toInt().coerceAtLeast(MIN_CACHE_BYTES)

    private fun decode(source: PageSource, index: Int, reqW: Int, reqH: Int): Bitmap? = try {
        val raw = source.loadPage(index, reqW, reqH)
        if (autoCrop && mode != Mode.LONG_STRIP) BitmapUtils.autoCrop(raw) else raw
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load page $index", t)
        null
    }

    /** Starts (or joins) the background decode of [pageIndex]. Main thread only. */
    private fun request(pageIndex: Int, reqW: Int, reqH: Int): Pending {
        pending[pageIndex]?.let { return it }
        val seg = segments.lastOrNull { pageIndex >= it.first } ?: segments[0]
        // Keep the chapter open while its page is being decoded, even if the strip
        // rolls on past it in the meantime.
        seg.loads.incrementAndGet()
        val deferred = scope.async(AppDispatchers.io) {
            gate.withPermit {
                val bitmap = decode(seg.source, pageIndex - seg.first, reqW, reqH)
                if (bitmap != null && bitmap.width > 0) {
                    cache.put(pageIndex, bitmap)
                    aspects[pageIndex] = bitmap.height.toFloat() / bitmap.width.toFloat()
                }
                bitmap
            }
        }
        deferred.invokeOnCompletion { releaseLoad(seg) }
        val entry = Pending(deferred)
        pending[pageIndex] = entry
        deferred.invokeOnCompletion {
            // Runs on the decoder thread; hop back before touching [pending].
            scope.launch { if (pending[pageIndex] === entry) pending.remove(pageIndex) }
        }
        return entry
    }

    /** Decodes the [preload] pages after [lastShown] into the cache. */
    private fun prefetchAfter(lastShown: Int) {
        val ahead = if (mode == Mode.LONG_STRIP) minOf(preload, MAX_STRIP_PRELOAD) else preload
        for (k in 1..ahead) {
            val p = lastShown + k
            if (p >= total) break
            if (cache.get(p) == null && pending[p] == null) request(p, lastReqW, lastReqH).prefetch = true
        }
    }

    /** Throws away everything decoded so far (the requested size changed). */
    private fun dropDecoded() {
        // Cancelling can complete handlers inline, which edit [pending]: iterate a copy.
        pending.values.toList().forEach { it.deferred.cancel() }
        pending.clear()
        cache.evictAll()
    }

    // ------------------------------------------------------------------ long strip

    /** Learns each page's real aspect ratio once its bitmap arrives, for long strips. */
    private fun updateLongStripAspect(holder: PageHolder, pageIndex: Int, bitmap: Bitmap) {
        if (mode != Mode.LONG_STRIP || bitmap.width <= 0) return
        if (pageIndex == 0) learnedAspect = bitmap.height.toFloat() / bitmap.width.toFloat()
        applyHeight(holder.container, longStripHeight(pageIndex))
    }

    private fun applyHeight(container: View, height: Int) {
        val lp = container.layoutParams
        if (lp == null) {
            container.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        } else if (lp.height != height) {
            lp.height = height
            container.layoutParams = lp
        }
    }

    private fun longStripHeight(page: Int): Int {
        val width = if (recyclerWidth > 0) recyclerWidth else fallbackWidth
        val aspect = aspects[page] ?: learnedAspect
        return (width * aspect).toInt().coerceAtLeast(1)
    }

    private fun requestSize(image: ZoomImageView): Pair<Int, Int> {
        val viewW = (if (image.width > 0) image.width else fallbackWidth).coerceAtLeast(360)
        val viewH = (if (image.height > 0) image.height else 1920).coerceAtLeast(360)
        return when {
            mode == Mode.LONG_STRIP || mode == Mode.VERTICAL -> viewW to 1
            fitMode == ZoomImageView.FitMode.WIDTH -> viewW to 1
            fitMode == ZoomImageView.FitMode.HEIGHT -> 1 to viewH
            else -> viewW to viewH
        }
    }

    private fun learnAspect() {
        val seg = segments.firstOrNull() ?: return
        // Reading the page size touches the archive, so hold the chapter open
        // while the query is in flight.
        seg.loads.incrementAndGet()
        scope.launch {
            val size = withContext(AppDispatchers.io) {
                runCatching { seg.source.pageSize(0) }.getOrNull()
            }
            releaseLoad(seg)
            if (size != null && size[0] > 0) {
                learnedAspect = size[1].toFloat() / size[0].toFloat()
                aspects.putIfAbsent(0, learnedAspect)
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    fun release() {
        released = true
        scope.cancel()
        pending.clear()
        cache.evictAll()
        // Every chapter's handle is released here rather than earlier, so a decode
        // still in flight cannot meet a closed archive.
        segments.forEach { retire(it) }
        segments.clear()
        base = 0
    }

    companion object {
        private const val TAG = "PageAdapter"
        private const val NO_PAGE = -1
        private const val DECODE_THREADS = 2
        private const val MIN_CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_STRIP_PRELOAD = 2
    }
}
