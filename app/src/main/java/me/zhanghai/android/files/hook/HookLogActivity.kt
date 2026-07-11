/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.hook.sandbox.SandboxConnection
import me.zhanghai.android.files.hook.sandbox.setHookLogListener
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Real-time hook activity log viewer.
 *
 * Shows every hooked-method invocation reported by the sandbox's LOG_CALLS rule (and any other
 * hook activity the sandbox dispatches), streamed live over Binder. This is the analytical
 * payoff of the whole hook feature: the user sees exactly which methods the target calls, with
 * what arguments, as the target's code runs under their hooks.
 *
 * The activity holds its own [SandboxConnection] (the sandbox session is shared — all
 * hook-related activities connect to the same :sandbox process) and registers a log listener on
 * resume, unregistering on pause to avoid leaking callbacks.
 */
class HookLogActivity : AppCompatActivity() {
    private lateinit var adapter: LogAdapter
    private var connection: SandboxConnection? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.hook_log_activity)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.hook_log_title)
        }

        adapter = LogAdapter()
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        connection = SandboxConnection(this)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { connection!!.connect() }.onFailure { return@launch }
            connection!!.setHookLogListener { _, level, tag, message ->
                runOnUiThread {
                    adapter.add(LogEntry(System.currentTimeMillis(), level, tag, message))
                    findViewById<View>(R.id.emptyView).visibility = View.GONE
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            runCatching { connection?.setHookLogListener(null) }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------------------------
    //  Log entry model + adapter
    // ---------------------------------------------------------------------------------

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private val entries = mutableListOf<LogEntry>()
        private val maxEntries = 500

        fun add(entry: LogEntry) {
            entries.add(entry)
            // Cap the buffer so a chatty target doesn't exhaust memory.
            if (entries.size > maxEntries) {
                val dropCount = entries.size - maxEntries
                entries.subList(0, dropCount).clear()
                notifyItemRangeRemoved(0, dropCount)
                notifyItemInserted(entries.size - 1)
            } else {
                notifyItemInserted(entries.size - 1)
            }
            // Auto-scroll to the newest entry.
            findViewById<RecyclerView>(R.id.recyclerView).scrollToPosition(entries.size - 1)
        }

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.hook_log_item, parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.timeText.text = timeFormat.format(entry.timestamp)
            holder.levelText.text = entry.level
            holder.messageText.text = "[${entry.tag}] ${entry.message}"
            // Color the level tag by severity for quick scanning.
            val colorRes = when (entry.level) {
                "ERROR" -> android.R.color.holo_red_dark
                "WARN" -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_green_dark
            }
            holder.levelText.setTextColor(
                ContextCompat.getColor(holder.itemView.context, colorRes)
            )
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val timeText: TextView = itemView.findViewById(R.id.timeText)
            val levelText: TextView = itemView.findViewById(R.id.levelText)
            val messageText: TextView = itemView.findViewById(R.id.messageText)
        }
    }
}
