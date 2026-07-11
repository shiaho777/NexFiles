/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.os.AsyncTask
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.showToast

/**
 * Browser for the local recycle bin ([RecycleBin]). Lists trashed entries with their original
 * location and deletion time, lets the user restore or permanently delete individual entries, and
 * offers an "empty all" action in the toolbar.
 *
 * All file operations run on the shared background pool so the list stays responsive; the list is
 * reloaded after every mutating action.
 */
class RecycleBinActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: Adapter
    private var entries: List<RecycleEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.recycle_bin_activity)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_recycle_bin_enabled_title)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = Adapter(entries) { entry -> confirmRestore(entry) }
        adapter.setOnDeleteListener { entry -> confirmDelete(entry) }
        recyclerView.adapter = adapter
        loadEntries()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.recycle_bin, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_empty)?.isVisible = entries.isNotEmpty()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        item.itemId == android.R.id.home -> { finish(); true }
        item.itemId == R.id.action_empty -> { confirmEmpty(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadEntries() {
        (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).execute {
            val loaded = RecycleBin.listEntries()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                entries = loaded
                adapter.update(loaded)
                emptyView.isVisible = loaded.isEmpty()
                invalidateOptionsMenu()
            }
        }
    }

    private fun confirmRestore(entry: RecycleEntry) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.recycle_bin_restore_confirm_format, displayName(entry)))
            .setPositiveButton(R.string.recycle_bin_restore) { _, _ -> doRestore(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doRestore(entry: RecycleEntry) {
        (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).execute {
            val restored = RecycleBin.restore(entry)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (restored != null) {
                    showToast(R.string.recycle_bin_restore_success)
                } else {
                    showToast(R.string.recycle_bin_restore_failed)
                }
                loadEntries()
            }
        }
    }

    private fun confirmDelete(entry: RecycleEntry) {
        AlertDialog.Builder(this)
            .setMessage(
                getString(R.string.recycle_bin_delete_confirm_format, displayName(entry))
            )
            .setPositiveButton(R.string.delete) { _, _ -> doDelete(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doDelete(entry: RecycleEntry) {
        (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).execute {
            val ok = RecycleBin.deleteEntry(entry)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                showToast(if (ok) R.string.delete else R.string.error)
                loadEntries()
            }
        }
    }

    private fun confirmEmpty() {
        AlertDialog.Builder(this)
            .setMessage(R.string.recycle_bin_empty_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> doEmpty() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doEmpty() {
        (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).execute {
            val ok = RecycleBin.empty()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                showToast(if (ok) R.string.delete else R.string.error)
                loadEntries()
            }
        }
    }

    private fun displayName(entry: RecycleEntry): String =
        entry.name.substringAfter("__", entry.name)

    private inner class Adapter(
        private var items: List<RecycleEntry>,
        private val onRestore: (RecycleEntry) -> Unit
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        private var onDelete: ((RecycleEntry) -> Unit)? = null

        fun setOnDeleteListener(listener: (RecycleEntry) -> Unit) {
            onDelete = listener
        }

        fun update(newItems: List<RecycleEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
            val titleText: TextView = itemView.findViewById(R.id.titleText)
            val subtitleText: TextView = itemView.findViewById(R.id.subtitleText)
            val restoreButton: ImageButton = itemView.findViewById(R.id.restoreButton)
            val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.recycle_bin_item, parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val ctx = holder.itemView.context
            val name = entry.name.substringAfter("__", entry.name)
            // Use a generic file icon; per-type icon probing would need the mime, which we don't
            // store in the sidecar. Keeping it simple avoids a file read per bind.
            holder.iconImage.setImageResource(R.drawable.file_icon_white_24dp)
            holder.titleText.text = name
            val time = SimpleDateFormat.getDateTimeInstance(
                SimpleDateFormat.MEDIUM, SimpleDateFormat.SHORT, Locale.getDefault()
            ).format(Date(entry.attributes.lastModifiedTime().toMillis()))
            val origin = entry.originalParent ?: ctx.getString(R.string.recycle_bin_origin_unknown)
            holder.subtitleText.text = ctx.getString(
                R.string.recycle_bin_item_subtitle_format, time, origin
            )
            holder.restoreButton.setOnClickListener { onRestore(entry) }
            holder.deleteButton.setOnClickListener { onDelete?.invoke(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
