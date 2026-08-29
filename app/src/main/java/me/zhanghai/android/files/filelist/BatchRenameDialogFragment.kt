/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.BatchRenameDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.showToast

/**
 * Multi-file rename dialog. The user types a template (see [BatchRenameTemplate] for syntax); the
 * preview updates live so there are no surprises after applying. On OK we resolve each file's new
 * name through the template and hand the pairs to [FileJobService.batchRename], which reuses the
 * existing rename conflict/overwrite flow file-by-file.
 */
class BatchRenameDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private var template: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = BatchRenameDialogBinding.inflate(requireContext().layoutInflater)
        val files = args.files.toList().sortedBy { it.name }
        val names = files.map { it.name }
        binding.templateEdit.setText(template)
        binding.templateEdit.doAfterTextChanged { text ->
            template = text?.toString().orEmpty()
            binding.previewText.text = buildPreview(names, template)
        }
        binding.previewText.text = buildPreview(names, template)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.batch_rename_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyRename(files, template)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
    }

    private fun buildPreview(names: List<String>, template: String): String {
        if (template.isEmpty()) {
            return names.joinToString("\n") { it }
        }
        val newNames = BatchRenameTemplate.apply(names, template)
        return names.zip(newNames).joinToString("\n") { (old, new) ->
            "$old → $new"
        }
    }

    private fun applyRename(files: List<FileItem>, template: String) {
        if (template.isEmpty()) {
            showToast(R.string.batch_rename_empty_template)
            return
        }
        val names = files.map { it.name }
        val newNames = BatchRenameTemplate.apply(names, template)
        val renamePairs = files.zip(newNames).map { (file, newName) -> file.path to newName }
        // Skip no-op renames (name unchanged) to avoid the conflict dialog firing for them.
        val effectivePairs = renamePairs.filter { (path, newName) ->
            path.fileName?.toString() != newName
        }
        if (effectivePairs.isEmpty()) {
            showToast(R.string.batch_rename_no_change)
            return
        }
        FileJobService.batchRename(effectivePairs, requireContext())
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    companion object {
        fun show(files: FileItemSet, fragment: androidx.fragment.app.Fragment) {
            BatchRenameDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }
}
