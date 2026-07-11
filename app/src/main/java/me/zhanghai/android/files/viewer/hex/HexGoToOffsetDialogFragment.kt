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
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.util.ParcelableArgs

/**
 * Dialog for jumping to a specific byte offset in the hex viewer. The user enters a hex offset;
 * the host fragment scrolls the list to the line containing that offset.
 *
 * @see HexViewerFragment.jumpToOffset
 */
class HexGoToOffsetDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = me.zhanghai.android.files.databinding.DexRenameDialogBinding.inflate(
            requireContext().layoutInflater
        )
        // Reuse the rename dialog layout: "current" hint shows max offset, "new" is the target.
        binding.currentLayout.hint = getString(R.string.hex_goto_current_hint)
        binding.currentText.setText(String.format("0 — %X", args.fileSize - 1))
        binding.newValueLayout.hint = getString(R.string.hex_goto_new_hint)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.hex_viewer_go_to_offset)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hexStr = binding.newValueText.text?.toString()?.trim()?.removePrefix("0x")
                    ?.removePrefix("0X").orEmpty()
                val offset = hexStr.toLongOrNull(16)
                if (offset != null && offset in 0 until args.fileSize) {
                    listener.onGoToOffset(offset)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @Parcelize
    class Args(val fileSize: Long) : ParcelableArgs

    interface Listener {
        fun onGoToOffset(offset: Long)
    }

    companion object {
        fun show(fileSize: Long, fragment: Fragment) {
            HexGoToOffsetDialogFragment()
                .putArgs(Args(fileSize))
                .show(fragment)
        }
    }
}
