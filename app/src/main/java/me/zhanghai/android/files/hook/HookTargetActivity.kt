/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hook

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.hook.sandbox.SandboxConnection
import me.zhanghai.android.files.hook.sandbox.initHookEngine
import me.zhanghai.android.files.hook.sandbox.loadPackage
import me.zhanghai.android.files.util.showToast

/**
 * Lists installed apps and loads the chosen one into the rootless sandbox for hooking.
 *
 * This is the user-facing entry point of the rootless hook feature. Picking an app loads its
 * APK into the sandbox process via [SandboxConnection], initializes lsplant there, and then
 * offers to start the target's Application — at which point the user's queued hooks (if any)
 * are live and intercepting the target's method calls.
 *
 * No root, no ptrace, no SELinux fight: the target's code runs inside our sandbox process,
 * where lsplant has unrestricted ArtMethod access.
 */
class HookTargetActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private var connection: SandboxConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.hook_target_activity)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.hook_target_title)
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val apps = loadHookableApps()
        recyclerView.adapter = AppAdapter(apps) { info -> onAppSelected(info) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun onAppSelected(info: ApplicationInfo) {
        // Confirm before loading — this binds a sandbox process and loads the APK's dex.
        MaterialAlertDialogBuilder(this)
            .setTitle(info.loadLabel(packageManager))
            .setMessage(getString(R.string.hook_target_confirm_message, info.packageName))
            .setPositiveButton(R.string.hook_target_load) { _, _ ->
                loadIntoSandbox(info.packageName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadIntoSandbox(packageName: String) {
        // Tear down any prior session before starting a new one.
        connection?.close()
        val conn = SandboxConnection(this)
        connection = conn
        lifecycleScope.launch {
            val loadResult = conn.loadPackage(packageName)
            loadResult.onFailure {
                showToast(getString(R.string.hook_target_load_failed, it.message ?: ""))
                return@launch
            }
            val initResult = conn.initHookEngine()
            initResult.onFailure {
                showToast(getString(R.string.hook_target_init_failed, it.message ?: ""))
                return@launch
            }
            // Loading + lsplant init succeeded. Open the hook configuration screen so the user
            // can browse the target's classes, pick methods, and attach hook rules — then start
            // the analysis run with those hooks live.
            val intent = Intent(this@HookTargetActivity, HookConfigActivity::class.java)
                .putExtra(HookConfigActivity.EXTRA_PACKAGE_NAME, packageName)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection?.close()
        connection = null
    }

    private fun loadHookableApps(): List<ApplicationInfo> =
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { !it.sourceDir.isNullOrEmpty() && it.uid != android.os.Process.myUid() }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    private class AppAdapter(
        private val apps: List<ApplicationInfo>,
        private val onClick: (ApplicationInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
            val titleText: TextView = itemView.findViewById(R.id.titleText)
            val subtitleText: TextView = itemView.findViewById(R.id.subtitleText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.hook_target_item, parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val info = apps[position]
            val pm = holder.itemView.context.packageManager
            holder.iconImage.load(AppIconPackageName(info.packageName))
            holder.titleText.text = info.loadLabel(pm)
            holder.subtitleText.text = info.packageName
            holder.itemView.setOnClickListener { onClick(info) }
        }

        override fun getItemCount(): Int = apps.size
    }
}
