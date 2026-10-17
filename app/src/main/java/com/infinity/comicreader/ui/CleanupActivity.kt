package com.infinity.comicreader.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.infinity.comicreader.R
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Housekeeping: finds duplicate files (same content, confirmed by a full checksum) or
 * lists the largest files below a folder, and lets you delete them.
 */
class CleanupActivity : AppCompatActivity() {

    /** One row: a section header (file == null) or a file. */
    private class Item(val header: String?, val file: File?, val size: Long)

    private lateinit var library: Library
    private lateinit var list: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var selectButton: MaterialButton
    private lateinit var deleteButton: MaterialButton

    private var mode = MODE_DUPES
    private val items = ArrayList<Item>()
    private val selected = LinkedHashSet<String>()
    private lateinit var adapter: CleanAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        setContentView(R.layout.activity_cleanup)

        mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_LARGE) MODE_LARGE else MODE_DUPES
        val root = File(
            intent.getStringExtra(EXTRA_ROOT) ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.cleanup_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(if (mode == MODE_DUPES) R.string.menu_dupes else R.string.menu_large)
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.cleanup_list)
        progress = findViewById(R.id.cleanup_progress)
        empty = findViewById(R.id.cleanup_empty)
        selectButton = findViewById(R.id.cleanup_select)
        deleteButton = findViewById(R.id.cleanup_delete)
        findViewById<TextView>(R.id.cleanup_summary).text = root.absolutePath

        adapter = CleanAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        selectButton.visibility = if (mode == MODE_DUPES) View.VISIBLE else View.GONE
        selectButton.setOnClickListener { selectExtras() }
        deleteButton.setOnClickListener { confirmDelete() }
        updateDeleteButton()

        scan(root)
    }

    // ------------------------------------------------------------------ scanning

    private fun scan(root: File) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(AppDispatchers.io) {
                if (mode == MODE_DUPES) findDuplicates(root) { isActive } else findLargest(root) { isActive }
            }
            items.clear()
            items.addAll(result)
            progress.visibility = View.GONE
            empty.setText(R.string.cleanup_nothing)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * Comic files below [root], without hidden folders or `Android/`. Symbolic links are
     * skipped, so a link loop cannot hang the scan and a link is never offered for deletion.
     */
    private fun walk(root: File, alive: () -> Boolean): Sequence<File> =
        root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { !it.name.startsWith(".") && it.name != "Android" && !Files.isSymbolicLink(it.toPath()) }
            .takeWhile { alive() }
            .filter { it.isFile && Formats.typeOf(it.name) != EntryType.OTHER && !Files.isSymbolicLink(it.toPath()) }

    private fun findLargest(root: File, alive: () -> Boolean): List<Item> =
        walk(root, alive).map { it to it.length() }
            .sortedByDescending { it.second }
            .take(LARGEST_COUNT)
            .map { Item(null, it.first, it.second) }
            .toList()

    private fun findDuplicates(root: File, alive: () -> Boolean): List<Item> {
        val bySize = walk(root, alive).filter { it.length() > 0 }.groupBy { it.length() }
            .filter { it.value.size > 1 }
        val groups = ArrayList<Pair<Long, List<File>>>()
        for ((size, files) in bySize) {
            if (!alive()) break
            // Cheap pre-check on head + tail, then a full checksum to be certain.
            val byQuick = files.groupBy { runCatching { quickHash(it) }.getOrNull() }
                .filter { it.key != null && it.value.size > 1 }
            for (candidates in byQuick.values) {
                val byFull = candidates.groupBy { runCatching { fullHash(it) }.getOrNull() }
                    .filter { it.key != null && it.value.size > 1 }
                byFull.values.forEach { same ->
                    groups.add(size to same.sortedWith(compareBy(NaturalOrder) { f -> f.absolutePath }))
                }
            }
        }
        // Biggest wasted space first.
        groups.sortByDescending { it.first * (it.second.size - 1) }
        val out = ArrayList<Item>()
        for ((size, files) in groups) {
            out.add(Item(getString(R.string.dupe_group, files.size), null, size))
            files.forEach { out.add(Item(null, it, size)) }
        }
        return out
    }

    private fun quickHash(f: File): String {
        val md = MessageDigest.getInstance(HASH)
        val buf = ByteArray(QUICK_BYTES)
        RandomAccessFile(f, "r").use { raf ->
            var n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
            if (raf.length() > buf.size * 2L) {
                raf.seek(raf.length() - buf.size)
                n = raf.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun fullHash(f: File): String {
        val md = MessageDigest.getInstance(HASH)
        val buf = ByteArray(1 shl 20)
        f.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ selection / delete

    /** Ticks every copy except the first in each group, so one copy of each is kept. */
    private fun selectExtras() {
        selected.clear()
        var first = true
        for (row in items) {
            val f = row.file
            if (row.header != null) {
                first = true
            } else if (f != null) {
                if (first) first = false else selected.add(f.absolutePath)
            }
        }
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun updateDeleteButton() {
        val bytes = items.filter { it.file != null && it.file.absolutePath in selected }.sumOf { it.size }
        deleteButton.isEnabled = selected.isNotEmpty()
        deleteButton.text = if (selected.isEmpty()) {
            getString(R.string.delete_selected)
        } else {
            getString(R.string.delete_selected_n, selected.size, Formatter.formatShortFileSize(this, bytes))
        }
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) return
        val paths = selected.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_selected_msg, paths.size))
            .setPositiveButton(R.string.delete) { _, _ -> deletePaths(paths) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deletePaths(paths: List<String>) {
        lifecycleScope.launch {
            val deleted = withContext(AppDispatchers.io) { paths.filter { File(it).delete() }.toSet() }
            deleted.forEach { library.removePathsUnder(it) }
            if (deleted.size < paths.size) {
                Toast.makeText(this@CleanupActivity, R.string.op_failed, Toast.LENGTH_SHORT).show()
            }
            selected.removeAll(deleted)
            items.removeAll { it.file != null && it.file.absolutePath in deleted }
            dropEmptyGroups()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            updateDeleteButton()
        }
    }

    /** Removes headers that no longer have at least two files below them. */
    private fun dropEmptyGroups() {
        if (mode != MODE_DUPES) return
        val kept = ArrayList<Item>()
        var i = 0
        while (i < items.size) {
            if (items[i].header != null) {
                var j = i + 1
                while (j < items.size && items[j].header == null) j++
                if (j - i - 1 >= 2) kept.addAll(items.subList(i, j))
                i = j
            } else {
                kept.add(items[i])
                i++
            }
        }
        items.clear()
        items.addAll(kept)
    }

    // ------------------------------------------------------------------ adapter

    private inner class CleanAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position].header != null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = if (viewType == 1) R.layout.item_clean_header else R.layout.item_clean
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val v = holder.itemView
            if (item.header != null) {
                v.findViewById<TextView>(R.id.title).text = item.header
                return
            }
            val f = item.file ?: return
            v.findViewById<TextView>(R.id.title).text = f.name
            v.findViewById<TextView>(R.id.subtitle).text = f.parent.orEmpty()
            v.findViewById<TextView>(R.id.size).text = Formatter.formatShortFileSize(this@CleanupActivity, item.size)
            val check = v.findViewById<CheckBox>(R.id.check)
            check.isChecked = f.absolutePath in selected
            v.setOnClickListener {
                val path = f.absolutePath
                if (!selected.add(path)) selected.remove(path)
                check.isChecked = path in selected
                updateDeleteButton()
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ROOT = "root"
        const val MODE_DUPES = "dupes"
        const val MODE_LARGE = "large"

        private const val HASH = "SHA-256"
        private const val QUICK_BYTES = 64 * 1024
        private const val MAX_DEPTH = 12
        private const val LARGEST_COUNT = 100
    }
}
