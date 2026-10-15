package com.infinity.comicreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.util.FileUtils
import java.text.DateFormat
import java.util.Date

class BookAdapter(
    var grid: Boolean,
    private val showStar: Boolean,
    private val onClick: (BookEntry) -> Unit,
    private val onLongClick: (BookEntry) -> Unit,
    private val onStarClick: (BookEntry) -> Unit
) : RecyclerView.Adapter<BookAdapter.BookHolder>() {

    private val items = ArrayList<BookEntry>()

    fun submit(list: List<BookEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val star: ImageView? = view.findViewById(R.id.star)
    }

    override fun getItemViewType(position: Int): Int = if (grid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookHolder {
        val layout = if (grid) R.layout.item_book_grid else R.layout.item_book_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return BookHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: BookHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(iconFor(item))
        holder.title.text = item.name

        val context = holder.itemView.context
        holder.subtitle.text = when {
            item.isDir && item.childCount >= 0 -> "${item.childCount} items"
            item.isDir -> ""
            else -> FileUtils.formatSize(context, item.size)
        }

        if (item.isDir && item.childCount < 0) {
            holder.subtitle.text = ""
        }

        holder.star?.let { star ->
            if (showStar) {
                star.visibility = View.VISIBLE
                star.alpha = 1f
            } else {
                // Favorites screen: tapping the star removes the entry.
                star.visibility = View.VISIBLE
                star.alpha = 1f
            }
            star.setOnClickListener { onStarClick(item) }
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    private fun iconFor(item: BookEntry): Int = when {
        item.isDir -> R.drawable.ic_folder
        item.type == EntryType.PDF || item.type == EntryType.ARCHIVE -> R.drawable.ic_book
        item.type == EntryType.IMAGE -> R.drawable.ic_file
        else -> R.drawable.ic_file
    }

    fun formatDate(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}
