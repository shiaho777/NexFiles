/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DexRenameDialogBinding
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.util.ParcelableArgs

/**
 * Dialog for narrowing the currently displayed search results by an additional name filter.
 * The filter is applied in-memory via [FileListViewModel.refine] against the cached base result
 * set — no directory re-traversal.
 *
 * Passing an empty string restores the full result set.
 */
class SearchInResultsDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DexRenameDialogBinding.inflate(requireContext().layoutInflater)
        binding.currentLayout.hint = getString(R.string.search_in_results_hint)
        binding.currentText.setText(args.currentRefineQuery)
        binding.newValueLayout.hint = getString(R.string.search_in_results_hint)
        binding.newValueText.setText(args.currentRefineQuery)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(getString(R.string.search_in_results_count_format, args.resultCount))
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val query = binding.newValueText.text?.toString().orEmpty()
                listener.onSearchInResults(query)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @Parcelize
    class Args(
        val resultCount: Int,
        val currentRefineQuery: String
    ) : ParcelableArgs

    interface Listener {
        fun onSearchInResults(query: String)
    }

    companion object {
        fun show(resultCount: Int, currentRefineQuery: String, fragment: Fragment) {
            SearchInResultsDialogFragment()
                .putArgs(Args(resultCount, currentRefineQuery))
                .show(fragment)
        }
    }
}
