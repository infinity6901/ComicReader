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
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.PageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * A [RecyclerView.Adapter] has a fixed item view type, so callers build a fresh
 * instance whenever the mode, spread or reading direction changes.
 */
class PageAdapter(
    private val source: PageSource,
    private val mode: Mode,
    private val onTap: (Float) -> Unit,
    private val doubleTapZoom: Boolean,
    private val twoPage: Boolean = false,
    private val rtl: Boolean = false,
    private val onVerticalDrag: ((Float) -> Unit)? = null
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
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    var filter: String = Filters.NONE
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = object : LruCache<String, Bitmap>(6) {}
    private val aspects = HashMap<Int, Float>()
    private var learnedAspect = 1.45f
    private var recyclerWidth = 0
    private var fallbackWidth = 1080
    private var layoutListener: View.OnLayoutChangeListener? = null

    private val spread = twoPage && mode == Mode.PAGED

    inner class PageHolder(val container: View, val images: List<ZoomImageView>) :
        RecyclerView.ViewHolder(container)

    override fun getItemCount(): Int = if (spread) (source.pageCount + 1) / 2 else source.pageCount

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
        if (spread) {
            val firstPage = if (rtl) position * 2 + 1 else position * 2
            val secondPage = if (rtl) position * 2 else position * 2 + 1
            bindImage(holder, holder.images[0], firstPage)
            bindImage(holder, holder.images[1], secondPage)
        } else {
            bindImage(holder, holder.images[0], position)
        }
    }

    private fun bindImage(holder: PageHolder, image: ZoomImageView, pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= source.pageCount) {
            image.setImageDrawable(null)
            return
        }
        image.colorFilter = Filters.colorFilter(filter)

        if (mode == Mode.LONG_STRIP) {
            applyHeight(holder.container, longStripHeight(pageIndex))
            holder.container.setPadding(0, 0, 0, 0)
        } else if (holder.container is FrameLayout) {
            holder.container.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        }

        val key = pageIndex.toString()
        val cached = cache.get(key)
        if (cached != null) {
            image.setImageBitmap(cached)
            image.setFitMode(fitMode)
            return
        }
        image.setImageDrawable(null)

        val (reqW, reqH) = requestSize(image)
        val holderPosition = holder.bindingAdapterPosition
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    source.loadPage(pageIndex, reqW, reqH)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load page $pageIndex", t)
                    null
                }
            } ?: return@launch

            cache.put(key, bitmap)
            if (holder.bindingAdapterPosition != holderPosition) return@launch
            image.setImageBitmap(bitmap)
            image.setFitMode(fitMode)

            if (mode == Mode.LONG_STRIP && bitmap.width > 0) {
                val real = bitmap.height.toFloat() / bitmap.width.toFloat()
                aspects[pageIndex] = real
                if (pageIndex == 0) learnedAspect = real
                applyHeight(holder.container, longStripHeight(pageIndex))
            }
        }
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
        scope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching { source.pageSize(0) }.getOrNull()
            }
            if (size != null && size[0] > 0) {
                learnedAspect = size[1].toFloat() / size[0].toFloat()
                aspects[0] = learnedAspect
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        holder.images.forEach { it.setImageDrawable(null) }
    }

    fun release() {
        scope.cancel()
        cache.evictAll()
    }

    companion object {
        private const val TAG = "PageAdapter"
    }
}
