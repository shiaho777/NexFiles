/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileaction

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.util.showToast

/**
 * Lists installed apps and lets the user copy any app's base APK out to a chosen location, the way
 * MT's "提取安装包" works. We reuse the existing copy file job (the APK on disk is just a regular
 * file under /data/app), so there is no new copy logic here — only the picker UI.
 *
 * Split APKs are not handled in this iteration; the base APK is what most users mean by "the APK".
 */
class ExtractApkActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView

    private val saveApkLauncher =
        registerForActivityResult(FileListActivity.CreateFileContract(), ::onSaveApkResult)

    private var pendingSourcePath: java8.nio.file.Path? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.extract_apk_activity)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.extract_apk_title)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val apps = loadInstalledApps()
        recyclerView.adapter = AppAdapter(apps) { info ->
            val sourceDir = info.sourceDir
            if (sourceDir.isNullOrEmpty()) {
                showToast(R.string.extract_apk_no_source)
                return@AppAdapter
            }
            pendingSourcePath = Paths.get(sourceDir)
            val mimeType = MimeType.APK
            val fileName = info.packageName + ".apk"
            saveApkLauncher.launch(Triple(mimeType, fileName, null))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun onSaveApkResult(target: java8.nio.file.Path?) {
        val source = pendingSourcePath
        pendingSourcePath = null
        if (source == null || target == null) {
            return
        }
        FileJobService.copy(listOf(source), target.parent, this)
    }

    private fun loadInstalledApps(): List<ApplicationInfo> =
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { !it.sourceDir.isNullOrEmpty() }
            .sortedBy {
                it.loadLabel(packageManager).toString().lowercase()
            }

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
                LayoutInflater.from(parent.context).inflate(R.layout.extract_apk_item, parent, false)
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
