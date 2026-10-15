package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
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
 * Works both as the adapter for a horizontal ViewPager2 (paged mode) and a
 * vertical RecyclerView (webtoon mode). When [twoPage] is set each item shows a
 * landscape spread of two pages.
 *
 * The adapter is always created for a fixed item layout, so callers must build a
 * new instance when toggling [twoPage] or [rtl].
 */
class PageAdapter(
    private val source: PageSource,
    private val onPageTap: () -> Unit,
    private val doubleTapZoom: Boolean,
    private val twoPage: Boolean = false,
    private val rtl: Boolean = false
) : RecyclerView.Adapter<PageAdapter.PageHolder>() {

    var fitMode: ZoomImageView.FitMode = ZoomImageView.FitMode.SCREEN
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = object : LruCache<String, Bitmap>(6) {}

    inner class PageHolder(val container: View, val images: List<ZoomImageView>) :
        RecyclerView.ViewHolder(container)

    override fun getItemCount(): Int =
        if (twoPage) (source.pageCount + 1) / 2 else source.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        if (twoPage) {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val left = makeImage(parent)
            val right = makeImage(parent)
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return PageHolder(row, listOf(left, right))
        } else {
            val image = makeImage(parent).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageHolder(image, listOf(image))
        }
    }

    private fun makeImage(parent: ViewGroup): ZoomImageView =
        ZoomImageView(parent.context).apply {
            doubleTapEnabled = doubleTapZoom
            onTap = { onPageTap() }
        }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        if (twoPage) {
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
        val key = pageIndex.toString()
        val cached = cache.get(key)
        if (cached != null) {
            image.setImageBitmap(cached)
            image.setFitMode(fitMode)
            return
        }
        image.setImageDrawable(null)

        val reqW = (image.width.takeIf { it > 0 } ?: 1080) * 2
        val reqH = (image.height.takeIf { it > 0 } ?: 1920) * 2
        val holderPosition = holder.bindingAdapterPosition

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    source.loadPage(pageIndex, reqW, reqH)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load page $pageIndex", t)
                    null
                }
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
                if (holder.bindingAdapterPosition == holderPosition) {
                    image.setImageBitmap(bitmap)
                    image.setFitMode(fitMode)
                }
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
