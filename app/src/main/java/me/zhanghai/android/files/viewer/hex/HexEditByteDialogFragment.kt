/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/**
 * Dialog for editing a single byte in the hex viewer. Shows the current byte's offset and value,
 * and lets the user type a 2-digit hex replacement.
 *
 * The caller receives the new byte value via [Listener.onByteEdited] and is responsible for
 * updating the in-memory buffer and refreshing the affected [HexLine].
 */
class HexEditByteDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = me.zhanghai.android.files.databinding.DexRenameDialogBinding.inflate(
            requireContext().layoutInflater
        )
        // Reuse the rename dialog layout: "current" = current hex value, "new" = new hex value.
        binding.currentLayout.hint = getString(R.string.hex_edit_current_value)
        binding.currentText.setText(String.format("%02X", args.currentByte))
        binding.newValueLayout.hint = getString(R.string.hex_edit_new_value)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(getString(R.string.hex_edit_title_format, args.offset))
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hexStr = binding.newValueText.text?.toString()?.trim().orEmpty()
                val newByte = hexStr.toIntOrNull(16)
                if (newByte != null && newByte in 0..255 && hexStr.length <= 2) {
                    listener.onByteEdited(args.offset, newByte)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @Parcelize
    class Args(val offset: Long, val currentByte: Int) : ParcelableArgs

    interface Listener {
        fun onByteEdited(offset: Long, newByte: Int)
    }

    companion object {
        fun show(offset: Long, currentByte: Int, fragment: Fragment) {
            HexEditByteDialogFragment()
                .putArgs(Args(offset, currentByte))
                .show(fragment)
        }
    }
}
