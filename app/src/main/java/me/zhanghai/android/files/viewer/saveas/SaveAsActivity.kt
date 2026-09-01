/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.saveas

import android.os.Bundle
import android.os.Environment
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.saveAsUris
import me.zhanghai.android.files.util.showToast

/**
 * Handles ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE "save to the device" flows. A single
 * file opens the existing create-file dialog pre-filled with its name; multiple files open a
 * directory picker and are all copied into the chosen directory.
 */
class SaveAsActivity : AppActivity() {
    private val createFileLauncher =
        registerForActivityResult(FileListActivity.CreateFileContract(), ::onCreateFileResult)

    private val pickDirectoryLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract(), ::onPickDirectoryResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val uris = intent.saveAsUris
        if (uris.isNullOrEmpty()) {
            showToast(R.string.save_as_error)
            finish()
            return
        }
        val initialPath =
            Paths.get(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            )
        if (uris.size == 1) {
            val uri = uris.first()
            val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
            val title = SaveAsNames.forUri(this, uri)
            createFileLauncher.launch(Triple(mimeType, title, initialPath))
        } else {
            pickDirectoryLauncher.launch(initialPath)
        }
    }

    private fun onCreateFileResult(result: Path?) {
        val uri = intent.saveAsUris?.singleOrNull()
        if (result == null || uri == null) {
            finish()
            return
        }
        val source = SaveAsNames.toPath(this, uri)
        if (source == null) {
            showToast(R.string.save_as_error)
            finish()
            return
        }
        FileJobService.save(source, result, this)
        finish()
    }

    private fun onPickDirectoryResult(result: Path?) {
        val uris = intent.saveAsUris
        if (result == null || uris.isNullOrEmpty()) {
            finish()
            return
        }
        val sources = uris.map { uri -> uri to SaveAsNames.forUri(this, uri) }
        FileJobService.saveUris(sources, result, this)
        finish()
    }
}
