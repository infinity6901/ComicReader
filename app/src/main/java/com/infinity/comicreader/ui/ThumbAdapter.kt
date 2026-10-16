package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.PageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grid of downsampled page thumbnails used to jump to a page. */
class ThumbAdapter(
    private val source: PageSource,
    private val currentPage: Int,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<ThumbAdapter.Holder>() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = object : LruCache<Int, Bitmap>(100) {}

    class Holder(val root: View, val image: com.google.android.material.imageview.ShapeableImageView, val label: TextView) :
        RecyclerView.ViewHolder(root)

    override fun getItemCount(): Int = source.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thumb, parent, false)
        return Holder(
            view,
            view.findViewById(R.id.thumb_image),
            view.findViewById(R.id.thumb_label)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.label.text = (position + 1).toString()
        val context = holder.itemView.context
        if (position == currentPage) {
            holder.image.strokeWidth = context.resources.displayMetrics.density * 2f
            holder.image.strokeColor = androidx.core.content.ContextCompat.getColorStateList(context, R.color.md_primary)
        } else {
            holder.image.strokeWidth = 0f
        }
        holder.root.setOnClickListener { onPick(holder.bindingAdapterPosition) }

        val cached = cache.get(position)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }
        holder.image.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    source.loadPage(position, 240, 320)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load thumbnail $position", t)
                    null
                }
            }
            if (bitmap != null) {
                cache.put(position, bitmap)
                if (holder.bindingAdapterPosition == position) {
                    holder.image.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun release() {
        scope.cancel()
        cache.evictAll()
    }

    companion object {
        private const val TAG = "ThumbAdapter"
    }
}
