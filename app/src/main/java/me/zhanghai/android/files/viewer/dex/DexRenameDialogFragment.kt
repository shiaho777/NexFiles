/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DexRenameDialogBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.setTextWithSelection

/**
 * Generic rename dialog for DEX classes, methods, and fields. Shows the current name (read-only)
 * and lets the user type a new one. The caller receives the result via [Listener.onRename] and is
 * responsible for dispatching the appropriate [DexEditorModel] rename operation.
 *
 * For class renaming, [currentValue] is the full type descriptor (`Lcom/foo/Bar;`) and the user
 * is expected to enter a valid replacement descriptor.
 */
class DexRenameDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DexRenameDialogBinding.inflate(requireContext().layoutInflater)
        binding.currentText.setTextWithSelection(args.currentValue)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.dex_rename_title)
            .setView(binding.root)
            .setPositiveButton(R.string.dex_rename_apply) { _, _ ->
                val newValue = binding.newValueText.text?.toString().orEmpty()
                if (newValue.isNotEmpty() && newValue != args.currentValue) {
                    listener.onRename(args, newValue)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @Parcelize
    class Args(
        val kind: Kind,
        val currentValue: String,
        val definingClass: String,
        val memberName: String,
        val parameters: List<String>,
        val returnType: String,
        val fieldType: String
    ) : ParcelableArgs

    enum class Kind { CLASS, METHOD, FIELD }

    interface Listener {
        fun onRename(args: Args, newValue: String)
    }

    companion object {
        fun showClass(type: String, fragment: Fragment) {
            DexRenameDialogFragment().putArgs(
                Args(Kind.CLASS, type, type, "", emptyList(), "", "")
            ).show(fragment)
        }

        fun showMethod(
            definingClass: String, name: String, parameters: List<String>, returnType: String,
            fragment: Fragment
        ) {
            DexRenameDialogFragment().putArgs(
                Args(Kind.METHOD, name, definingClass, name, parameters, returnType, "")
            ).show(fragment)
        }

        fun showField(
            definingClass: String, name: String, type: String, fragment: Fragment
        ) {
            DexRenameDialogFragment().putArgs(
                Args(Kind.FIELD, name, definingClass, name, emptyList(), "", type)
            ).show(fragment)
        }
    }
}
