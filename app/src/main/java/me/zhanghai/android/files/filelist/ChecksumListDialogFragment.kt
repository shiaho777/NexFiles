/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.AsyncTask
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.toHexString
import java.security.MessageDigest
import java.util.concurrent.ExecutorService

/**
 * Computes and displays checksums for a batch of selected files. Each result line is
 * `<filename>\t<MD5>\t<SHA-1>\t<SHA-256>` so the whole report can be copied and pasted into a
 * spreadsheet or diff tool; we keep the dialog deliberately text-based (no per-row widgets) to
 * stay light and make bulk copy trivial.
 *
 * Directories are skipped: a recursive directory hash would hide per-file mismatches, which is the
 * opposite of what users running a checksum check usually want.
 */
class ChecksumListDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = me.zhanghai.android.files.databinding.ChecksumListDialogBinding
            .inflate(requireContext().layoutInflater)
        binding.progress.isVisible = true
        binding.resultText.isVisible = false
        // Run on the shared pool so the computation is cancelable and off the main thread.
        (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).execute {
            val report = buildReport(args.files.map { it.path })
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.progress.isVisible = false
                binding.resultText.isVisible = true
                binding.resultText.text = report
            }
        }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_list_select_action_checksum)
            .setView(binding.root)
            .setPositiveButton(R.string.copy) { _, _ ->
                val text = binding.resultText.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    val clipboard = requireContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
                    showToast(R.string.preference_copied)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
    }

    private fun buildReport(paths: List<Path>): String {
        val builder = StringBuilder()
        // Header row documents the column order for whoever pastes it elsewhere.
        builder.append("file\tMD5\tSHA-1\tSHA-256\n")
        for (path in paths) {
            val name = path.fileName?.toString() ?: path.toString()
            try {
                val md5 = hash(path, "MD5")
                val sha1 = hash(path, "SHA-1")
                val sha256 = hash(path, "SHA-256")
                builder.append(name).append('\t').append(md5).append('\t').append(sha1)
                    .append('\t').append(sha256).append('\n')
            } catch (e: Exception) {
                builder.append(name).append('\t').append(getString(R.string.error)).append('\n')
            }
        }
        return builder.toString()
    }

    private fun hash(path: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        path.newInputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192

        fun show(files: FileItemSet, fragment: androidx.fragment.app.Fragment) {
            ChecksumListDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }
}
