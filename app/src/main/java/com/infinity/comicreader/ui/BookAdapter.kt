package com.infinity.comicreader.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Library rows. Each entry shows a cover thumbnail (page 1 for books, first image
 * for folders), its name, a size/pages subtitle, a progress badge and — only for
 * favourites — a star. Tapping a visible star removes the favourite; adding one
 * is done from the long-press menu.
 */
class BookAdapter(
    var grid: Boolean,
    private val prefs: Prefs,
    private val onClick: (BookEntry) -> Unit,
    private val onLongClick: (BookEntry) -> Unit,
    private val onStarClick: (BookEntry) -> Unit
) : RecyclerView.Adapter<BookAdapter.BookHolder>() {

    private val items = ArrayList<BookEntry>()
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

    /** Caps concurrent cover decodes; a row scrolled away before its turn is never decoded. */
    private val gate = Semaphore(THUMB_THREADS)

    /** Path to draw a focus ring around, used after "open file location". */
    var highlightPath: String? = null

    fun submit(list: List<BookEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView? = view as? MaterialCardView
        val thumb: ShapeableImageView = view.findViewById(R.id.thumb)
        val placeholder: ImageView = view.findViewById(R.id.placeholder)
        val badge: TextView = view.findViewById(R.id.badge)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val star: ImageView = view.findViewById(R.id.star)
        var job: Job? = null
    }

    override fun getItemViewType(position: Int): Int = if (grid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookHolder {
        val layout = if (viewType == 1) R.layout.item_book_grid else R.layout.item_book_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return BookHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: BookHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.job?.cancel()

        holder.title.text = item.name
        val showName = prefs.showFilenames
        holder.title.visibility = if (showName) View.VISIBLE else View.GONE
        val subtitle = item.subtitle ?: subtitleFor(context, item)
        holder.subtitle.text = subtitle
        holder.subtitle.visibility = if (showName && subtitle.isNotEmpty()) View.VISIBLE else View.GONE

        holder.star.visibility = if (item.favorite) View.VISIBLE else View.GONE
        holder.star.setOnClickListener { onStarClick(item) }

        bindBadge(holder.badge, item)
        applyHighlight(holder, item)

        val icon = iconFor(item)
        holder.placeholder.setImageResource(icon)
        holder.placeholder.imageTintList = ColorStateList.valueOf(tintFor(context, item))
        holder.thumb.setImageDrawable(null)
        holder.placeholder.visibility = View.VISIBLE

        if (prefs.thumbnails) {
            val appContext = context.applicationContext
            val charset = prefs.charset
            val reqW: Int
            val reqH: Int
            if (grid) {
                reqW = context.resources.getDimensionPixelSize(R.dimen.grid_thumb_h)
                reqH = reqW
            } else {
                reqW = context.resources.getDimensionPixelSize(R.dimen.list_thumb_w)
                reqH = context.resources.getDimensionPixelSize(R.dimen.list_thumb_h)
            }
            val target = position
            holder.job = scope.launch {
                val bitmap = withContext(AppDispatchers.io) {
                    gate.withPermit {
                        runCatching { Thumbs.load(appContext, item, charset, reqW, reqH) }.getOrNull()
                    }
                }
                if (bitmap != null && holder.bindingAdapterPosition == target) {
                    holder.thumb.setImageBitmap(bitmap)
                    holder.placeholder.visibility = View.GONE
                }
            }
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    override fun onViewRecycled(holder: BookHolder) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job = null
        holder.thumb.setImageDrawable(null)
    }

    fun release() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ helpers

    private fun applyHighlight(holder: BookHolder, item: BookEntry) {
        val card = holder.card ?: return
        val highlighted = item.path == highlightPath
        if (highlighted) {
            card.strokeWidth = (holder.itemView.resources.displayMetrics.density * 2).toInt()
            card.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.md_primary)
        } else {
            card.strokeWidth = 0
        }
    }

    private fun bindBadge(badge: TextView, item: BookEntry) {
        val context = badge.context
        val hasProgress = item.pages > 0 && item.progressPage > 0
        val folderDone = item.isDir && item.childCount > 0 && item.readCount >= item.childCount
        when {
            item.read || folderDone -> {
                badge.visibility = View.VISIBLE
                badge.text = context.getString(R.string.read_badge)
                setPill(badge, R.color.md_secondary, R.color.md_on_secondary)
            }
            hasProgress -> {
                badge.visibility = View.VISIBLE
                badge.text = context.getString(R.string.read_progress, item.progressPage + 1, item.pages)
                setPill(badge, R.color.md_primary, R.color.md_on_primary)
            }
            else -> badge.visibility = View.GONE
        }
    }

    private fun setPill(view: TextView, colorRes: Int, textColorRes: Int) {
        val context = view.context
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f * context.resources.displayMetrics.density
            setColor(ContextCompat.getColor(context, colorRes))
        }
        view.setTextColor(ContextCompat.getColor(context, textColorRes))
    }

    private fun subtitleFor(context: android.content.Context, item: BookEntry): String {
        if (item.isDir) {
            return when {
                item.childCount < 0 -> ""
                item.readCount > 0 -> context.getString(R.string.folder_read_summary, item.childCount, item.readCount)
                else -> context.getString(R.string.books_count, item.childCount)
            }
        }
        val size = FileUtils.formatSize(context, item.size)
        return if (item.pages > 0) {
            "$size · ${context.getString(R.string.pages_count, item.pages)}"
        } else {
            size
        }
    }

    private fun iconFor(item: BookEntry): Int = when {
        item.isDir -> R.drawable.ic_folder
        item.type == EntryType.PDF -> R.drawable.ic_picture_as_pdf
        item.type == EntryType.ARCHIVE -> R.drawable.ic_auto_stories
        item.type == EntryType.IMAGE -> R.drawable.ic_image
        else -> R.drawable.ic_file
    }

    private fun tintFor(context: android.content.Context, item: BookEntry): Int = ContextCompat.getColor(
        context,
        when {
            item.isDir -> R.color.md_primary
            item.type == EntryType.PDF -> R.color.md_error
            item.type == EntryType.ARCHIVE -> R.color.md_secondary
            item.type == EntryType.IMAGE -> R.color.md_tertiary
            else -> R.color.md_on_surface_variant
        }
    )

    private companion object {
        const val THUMB_THREADS = 3
    }
}
