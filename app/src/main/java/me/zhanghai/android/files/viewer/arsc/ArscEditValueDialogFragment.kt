/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.arsc

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

/**
 * Dialog for editing a resource entry's string value. Reuses the dex_rename_dialog layout (which
 * has a read-only "current" field and an editable "new value" field), since the UI needs are
 * identical: show the old value, let the user type a new one.
 */
class ArscEditValueDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DexRenameDialogBinding.inflate(requireContext().layoutInflater)
        binding.currentText.setText(args.currentValue)
        binding.currentLayout.hint = getString(R.string.arsc_edit_current_value)
        binding.newValueLayout.hint = getString(R.string.arsc_edit_new_value)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(args.row.qualifiedKey)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValue = binding.newValueText.text?.toString().orEmpty()
                if (newValue.isNotEmpty()) {
                    listener.onEntryValueChanged(args.row, newValue)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @Parcelize
    class Args(val row: ArscEntryRow, val currentValue: String) : ParcelableArgs

    interface Listener {
        fun onEntryValueChanged(row: ArscEntryRow, newValue: String)
    }

    companion object {
        fun show(row: ArscEntryRow, currentValue: String, fragment: Fragment) {
            ArscEditValueDialogFragment().putArgs(Args(row, currentValue)).show(fragment)
        }
    }
}
