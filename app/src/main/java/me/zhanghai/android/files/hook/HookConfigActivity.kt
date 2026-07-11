/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.hook.sandbox.HookRule
import me.zhanghai.android.files.hook.sandbox.SandboxConnection
import me.zhanghai.android.files.hook.sandbox.hookMethod
import me.zhanghai.android.files.hook.sandbox.listClassMethods
import me.zhanghai.android.files.hook.sandbox.searchClasses
import me.zhanghai.android.files.hook.sandbox.startTargetApplication
import me.zhanghai.android.files.util.showToast

/**
 * The hook configuration screen: search the target's classes, browse their methods, and attach
 * a hook rule to any method.
 *
 * This is the analytical core of the rootless hook feature. The user picks exactly which method
 * to intercept and what to do with it — return a constant, log calls, replace a string, or block
 * the call — then starts the target's analysis run with those hooks live.
 *
 * The activity receives the target package name from [HookTargetActivity] after a successful
 * sandbox load, and holds its own [SandboxConnection] to drive class/method enumeration and
 * hook installation over Binder.
 */
class HookConfigActivity : AppCompatActivity() {
    private lateinit var adapter: ListAdapter
    private var connection: SandboxConnection? = null
    private var searchJob: Job? = null
    private var viewMode = MODE_CLASSES
    private var currentClassName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.hook_config_activity)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            title = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: getString(R.string.hook_config_title)
        }

        adapter = ListAdapter(
            onClassClick = { className -> showMethods(className) },
            onMethodClick = { method -> showRuleDialog(method) }
        )
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<android.widget.EditText>(R.id.searchText).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (viewMode == MODE_CLASSES) {
                        searchJob?.cancel()
                        searchJob = lifecycleScope.launch {
                            delay(SEARCH_DEBOUNCE_MS)
                            searchClasses(s?.toString() ?: "")
                        }
                    }
                }
            }
        )

        connection = SandboxConnection(this)
        lifecycleScope.launch {
            runCatching { connection!!.connect() }.onFailure {
                showToast(R.string.hook_config_connect_failed)
                return@launch
            }
            // Show all classes initially (empty query = match everything).
            searchClasses("")
        }
    }

    private suspend fun searchClasses(query: String) {
        val conn = connection ?: return
        val progress = findViewById<View>(R.id.progress)
        val emptyView = findViewById<View>(R.id.emptyView)
        progress.isVisible = true
        emptyView.isVisible = false
        val results = runCatching { conn.searchClasses(query, 200) }.getOrDefault(emptyList())
        progress.isVisible = false
        if (results.isEmpty()) {
            adapter.replace(emptyList())
            emptyView.isVisible = true
        } else {
            adapter.replace(results.map { ListItem.Class(it) })
            emptyView.isVisible = false
        }
    }

    private fun showMethods(className: String) {
        viewMode = MODE_METHODS
        currentClassName = className
        supportActionBar!!.title = className.substringAfterLast('.')
        val conn = connection ?: return
        val progress = findViewById<View>(R.id.progress)
        val emptyView = findViewById<View>(R.id.emptyView)
        progress.isVisible = true
        emptyView.isVisible = false
        lifecycleScope.launch {
            val methods = runCatching { conn.listClassMethods(className) }.getOrDefault(emptyList())
            progress.isVisible = false
            if (methods.isEmpty()) {
                adapter.replace(emptyList())
                emptyView.isVisible = true
            } else {
                adapter.replace(methods.map { parseMethod(className, it) })
                emptyView.isVisible = false
            }
        }
    }

    private fun parseMethod(className: String, record: String): ListItem.Method {
        // Format from SandboxService: "name|paramType1,paramType2|returnType|isStatic"
        val parts = record.split("|")
        val name = parts.getOrNull(0) ?: record
        val params = parts.getOrNull(1)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        val returnType = parts.getOrNull(2) ?: "void"
        val isStatic = parts.getOrNull(3)?.toBoolean() ?: false
        return ListItem.Method(className, name, params.toTypedArray(), returnType, isStatic)
    }

    private fun showRuleDialog(method: ListItem.Method) {
        val ruleNames = arrayOf(
            getString(R.string.hook_rule_return_constant),
            getString(R.string.hook_rule_log_calls),
            getString(R.string.hook_rule_replace_string),
            getString(R.string.hook_rule_block_call)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(method.displayName())
            .setItems(ruleNames) { _, which ->
                val rule = when (which) {
                    0 -> HookRule.RETURN_CONSTANT
                    1 -> HookRule.LOG_CALLS
                    2 -> HookRule.REPLACE_STRING
                    3 -> HookRule.BLOCK_CALL
                    else -> return@setItems
                }
                applyHook(method, rule)
            }
            .show()
    }

    private fun applyHook(method: ListItem.Method, rule: HookRule) {
        // For REPLACE_STRING and RETURN_CONSTANT we'd prompt for the arg; for simplicity we use
        // an empty arg here (the rule handles it gracefully). A follow-up can add an input dialog.
        val arg = when (rule) {
            HookRule.REPLACE_STRING, HookRule.RETURN_CONSTANT -> {
                showArgInputDialog(method, rule)
                return
            }
            else -> ""
        }
        val conn = connection ?: return
        lifecycleScope.launch {
            val result = conn.hookMethod(
                method.className, method.name, method.paramTypes, rule, arg
            )
            if (result.success) {
                showToast(getString(R.string.hook_config_hooked, method.displayName()))
            } else {
                showToast(getString(R.string.hook_config_hook_failed, result.message ?: ""))
            }
        }
    }

    private fun showArgInputDialog(method: ListItem.Method, rule: HookRule) {
        val hint = when (rule) {
            HookRule.RETURN_CONSTANT -> getString(R.string.hook_rule_return_constant_hint)
            HookRule.REPLACE_STRING -> getString(R.string.hook_rule_replace_string_hint)
            else -> ""
        }
        val edit = android.widget.EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(method.displayName())
            .setView(edit)
            .setPositiveButton(R.string.hook_config_apply) { _, _ ->
                val arg = edit.text.toString()
                val conn = connection ?: return@setPositiveButton
                lifecycleScope.launch {
                    val result = conn.hookMethod(
                        method.className, method.name, method.paramTypes, rule, arg
                    )
                    if (result.success) {
                        showToast(getString(R.string.hook_config_hooked, method.displayName()))
                    } else {
                        showToast(getString(R.string.hook_config_hook_failed, result.message ?: ""))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_PRESETS, 0, R.string.hook_preset_menu)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_LOG, 0, R.string.hook_log_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_START_ANALYSIS, 0, R.string.hook_config_start_analysis)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_START_ANALYSIS -> {
                startAnalysis()
                true
            }
            MENU_LOG -> {
                startActivity(Intent(this, HookLogActivity::class.java))
                true
            }
            MENU_PRESETS -> {
                showPresetDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showPresetDialog() {
        val presets = me.zhanghai.android.files.hook.sandbox.HookPresets.ALL
        val titles = presets.map { getString(it.titleRes) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hook_preset_menu)
            .setItems(titles) { _, which ->
                val preset = presets[which]
                showPresetConfirm(preset)
            }
            .show()
    }

    private fun showPresetConfirm(preset: me.zhanghai.android.files.hook.sandbox.HookPresets.Preset) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(preset.titleRes))
            .setMessage(getString(preset.descriptionRes))
            .setPositiveButton(R.string.hook_config_apply) { _, _ ->
                applyPreset(preset)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPreset(preset: me.zhanghai.android.files.hook.sandbox.HookPresets.Preset) {
        val conn = connection ?: return
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0
            for (target in preset.targets) {
                val result = conn.hookMethod(
                    target.className, target.methodName, target.paramTypes,
                    target.rule, target.arg
                )
                if (result.success) successCount++ else failCount++
            }
            showToast(getString(
                R.string.hook_preset_applied, successCount, failCount
            ))
        }
    }

    private fun startAnalysis() {
        val conn = connection ?: return
        lifecycleScope.launch {
            val result = conn.startTargetApplication()
            result.onSuccess {
                showToast(R.string.hook_target_started)
            }.onFailure {
                showToast(getString(R.string.hook_target_start_failed, it.message ?: ""))
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (viewMode == MODE_METHODS) {
            // Back from method list to class list.
            viewMode = MODE_CLASSES
            currentClassName = null
            supportActionBar!!.title =
                intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: getString(R.string.hook_config_title)
            lifecycleScope.launch { searchClasses("") }
            return true
        }
        finish()
        return true
    }

    override fun onBackPressed() {
        onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        // We keep the connection alive — the sandbox session persists so the user can return to
        // add more hooks. It's torn down when HookTargetActivity calls destroy.
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "me.zhanghai.android.files.hook.package_name"
        private const val MODE_CLASSES = 0
        private const val MODE_METHODS = 1
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MENU_START_ANALYSIS = 1
        private const val MENU_LOG = 2
        private const val MENU_PRESETS = 3
    }

    // ---------------------------------------------------------------------------------
    //  List model + adapter
    // ---------------------------------------------------------------------------------

    sealed class ListItem {
        data class Class(val name: String) : ListItem()
        data class Method(
            val className: String,
            val name: String,
            val paramTypes: Array<String>,
            val returnType: String,
            val isStatic: Boolean
        ) : ListItem() {
            fun displayName(): String = buildString {
                if (isStatic) append("static ")
                append(returnType.substringAfterLast('.'))
                append(' ')
                append(name)
                append('(')
                append(paramTypes.joinToString(", ") { it.substringAfterLast('.') })
                append(')')
            }
        }
    }

    private class ListAdapter(
        private val onClassClick: (String) -> Unit,
        private val onMethodClick: (ListItem.Method) -> Unit
    ) : RecyclerView.Adapter<ListAdapter.ViewHolder>() {
        private val items = mutableListOf<ListItem>()

        fun replace(newItems: List<ListItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.hook_method_item, parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            when (item) {
                is ListItem.Class -> {
                    holder.nameText.text = item.name.substringAfterLast('.')
                    holder.signatureText.text = item.name
                    holder.itemView.setOnClickListener { onClassClick(item.name) }
                }
                is ListItem.Method -> {
                    holder.nameText.text = item.name
                    val params = item.paramTypes.joinToString(", ") {
                        it.substringAfterLast('.').substringAfterLast('$')
                    }
                    val modifier = if (item.isStatic) "static " else ""
                    holder.signatureText.text = "$modifier${item.returnType.substringAfterLast('.')}($params)"
                    holder.itemView.setOnClickListener { onMethodClick(item) }
                }
            }
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.nameText)
            val signatureText: TextView = itemView.findViewById(R.id.signatureText)
        }
    }
}
