/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SearchFilterDialogBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.RemoteCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getArgs
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.readParcelable

/**
 * Edits the attribute filters for the current search (type / size / time / recursive / regex).
 * The query itself stays in the search view; this dialog only governs the side filters, and on
 * apply hands a fresh [SearchFilterOptions] back to the caller, which folds it into the next
 * traversal via [FileListViewModel.setSearchFilter].
 */
class SearchFilterDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: SearchFilterDialogBinding

    private var isListenerNotified = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val hierarchyState = SparseArray<Parcelable>()
            .apply { binding.root.saveHierarchyState(this) }
        outState.putState(State(hierarchyState))
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = SearchFilterDialogBinding.inflate(context.layoutInflater)
        val filter = args.filter
        bindSpinners(context, filter)
        binding.recursiveCheckBox.isChecked = filter.isRecursive
        binding.regexCheckBox.isChecked = filter.isRegex
        return MaterialAlertDialogBuilder(context, theme)
            .setTitle(R.string.search_filter_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk() }
                }
            }
    }

    private fun onOk() {
        val type = currentType()
        val sizeRange = currentSizeRange()
        val timeRange = currentTimeRange()
        val filter = SearchFilterOptions(
            isRecursive = binding.recursiveCheckBox.isChecked,
            isRegex = binding.regexCheckBox.isChecked,
            mimeType = type,
            minSize = sizeRange.first,
            maxSize = sizeRange.second,
            startTime = timeRange.first,
            endTime = timeRange.second
        )
        args.listener(filter)
        isListenerNotified = true
        finish()
    }

    private fun bindSpinners(context: Context, filter: SearchFilterOptions) {
        // Type
        val typeItems = SearchFilterType.values()
        val typeAdapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item,
            typeItems.map { getString(it.titleRes) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.typeSpinner.adapter = typeAdapter
        binding.typeSpinner.setSelection(indexOfType(filter.mimeType), false)

        // Size
        val sizeItems = SearchFilterSize.values()
        val sizeAdapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item,
            sizeItems.map { getString(it.titleRes) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.sizeSpinner.adapter = sizeAdapter
        binding.sizeSpinner.setSelection(indexOfSize(filter), false)

        // Time
        val timeItems = SearchFilterTime.values()
        val timeAdapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item,
            timeItems.map { getString(it.titleRes) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.timeSpinner.adapter = timeAdapter
        binding.timeSpinner.setSelection(indexOfTime(filter), false)
    }

    private fun currentType(): MimeType? =
        SearchFilterType.values()[binding.typeSpinner.selectedItemPosition].mimeType

    private fun currentSizeRange(): Pair<Long?, Long?> =
        SearchFilterSize.values()[binding.sizeSpinner.selectedItemPosition].range

    private fun currentTimeRange(): Pair<Long?, Long?> {
        val item = SearchFilterTime.values()[binding.timeSpinner.selectedItemPosition]
        val now = System.currentTimeMillis()
        return when (item) {
            SearchFilterTime.ANY -> null to null
            SearchFilterTime.TODAY -> (now - DAY_MILLIS) to null
            SearchFilterTime.WEEK -> (now - WEEK_MILLIS) to null
            SearchFilterTime.MONTH -> (now - MONTH_MILLIS) to null
            SearchFilterTime.YEAR -> (now - YEAR_MILLIS) to null
        }
    }

    private fun indexOfType(mimeType: MimeType?): Int =
        SearchFilterType.values().indexOfFirst { it.mimeType == mimeType }.coerceAtLeast(0)

    private fun indexOfSize(filter: SearchFilterOptions): Int =
        SearchFilterSize.values().indexOfFirst {
            it.range.first == filter.minSize && it.range.second == filter.maxSize
        }.coerceAtLeast(0)

    private fun indexOfTime(filter: SearchFilterOptions): Int {
        // Time ranges are relative; we can only restore "ANY" reliably (a stored absolute window
        // no longer matches a relative bucket after time passes). Default to ANY otherwise.
        return if (filter.startTime == null && filter.endTime == null) 0 else 0
    }

    @Parcelize
    class Args(
        val filter: SearchFilterOptions,
        val listener: @WriteWith<ListenerParceler>()
        (SearchFilterOptions) -> Unit
    ) : ParcelableArgs {
        object ListenerParceler : Parceler<(SearchFilterOptions) -> Unit> {
            override fun create(parcel: Parcel): (SearchFilterOptions) -> Unit =
                parcel.readParcelable<RemoteCallback>()!!.let {
                    { options ->
                        it.sendResult(Bundle().putArgs(ListenerArgs(options)))
                    }
                }

            override fun ((SearchFilterOptions) -> Unit).write(parcel: Parcel, flags: Int) {
                parcel.writeParcelable(
                    RemoteCallback {
                        val args = it.getArgs<ListenerArgs>()
                        this(args.options)
                    }, flags
                )
            }

            @Parcelize
            private class ListenerArgs(
                val options: SearchFilterOptions
            ) : ParcelableArgs
        }
    }

    @Parcelize
    private class State(
        val hierarchyState: SparseArray<Parcelable>
    ) : ParcelableState

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val WEEK_MILLIS = 7L * DAY_MILLIS
        private const val MONTH_MILLIS = 30L * DAY_MILLIS
        private const val YEAR_MILLIS = 365L * DAY_MILLIS
    }
}
