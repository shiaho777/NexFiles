/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.arsc

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.databinding.ArscViewerFragmentBinding
import me.zhanghai.android.files.databinding.DexStringItemBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.showToast
import java.io.IOException

/**
 * Viewer + editor for `resources.arsc`. Displays a flat list of all resource entries, grouped by
 * `type/key`. Long-pressing an entry opens an edit dialog where the user can modify its string
 * value. On save, the modified table is re-encoded via [ArscEncoder] and written back.
 *
 * Editing supports:
 *  - **String resources** (most common use case: changing app name, URLs, displayed text)
 *  - **Simple typed values** (int/bool/color — the data is preserved, only the string representation
 *    is shown)
 *  - **Bag (complex) entries** — displayed read-only (editing bag internals is out of scope for P2)
 */
class ArscViewerFragment : Fragment(),
    ArscEditValueDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var binding: ArscViewerFragmentBinding
    private var arscTable: ArscTable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = ArscViewerFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = ArscEntryAdapter { row -> onEntryClicked(row) }
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            binding.recyclerView.isVisible = false
            binding.emptyView.isVisible = false
            try {
                val table = withContext(Dispatchers.IO) {
                    val size = args.path.size()
                    if (size > MAX_FILE_SIZE) throw IOException("File too large ($size bytes)")
                    val bytes = args.path.newInputStream().use { it.readBytes() }
                    ArscParser.parse(bytes)
                }
                arscTable = table
                refreshList()
            } catch (e: Exception) {
                binding.errorText.text = e.toString()
                binding.errorText.isVisible = true
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    private fun refreshList() {
        val table = arscTable ?: return
        val entries = flattenEntries(table)
        val adapter = binding.recyclerView.adapter as ArscEntryAdapter
        adapter.replace(entries)
        binding.recyclerView.isVisible = entries.isNotEmpty()
        binding.emptyView.isVisible = entries.isEmpty()
    }

    private fun onEntryClicked(row: ArscEntryRow) {
        // For string-type entries, open the edit dialog. Others just copy.
        val table = arscTable ?: return
        val entry = findEntry(table, row) ?: return
        if (entry.rawDataType == 0x03 && entry.rawBagItems == null) {
            ArscEditValueDialogFragment.show(row, entry.value, this)
        } else {
            showToast(R.string.arsc_viewer_cannot_edit_type)
        }
    }

    // -- ArscEditValueDialogFragment.Listener --

    override fun onEntryValueChanged(row: ArscEntryRow, newValue: String) {
        val table = arscTable ?: return
        // ArscType/ArscTable are immutable data classes; rebuild the whole table with the new value.
        arscTable = rebuildTableWithValue(table, row, newValue)
        save()
    }

    private fun rebuildTableWithValue(table: ArscTable, row: ArscEntryRow, newValue: String): ArscTable {
        val newPackages = table.packages.map { pkg ->
            val newTypes = pkg.types.map { type ->
                val newEntries = type.entries.map { entry ->
                    if ("${type.name}/${entry.key}" == row.qualifiedKey) {
                        entry.copy(value = newValue)
                    } else entry
                }
                ArscType(type.name, newEntries)
            }
            ArscPackage(pkg.id, pkg.name, newTypes)
        }
        return ArscTable(newPackages)
    }

    private fun save() {
        val table = arscTable ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ArscEncoder.encode(table)
                }
                FileJobService.write(args.path, bytes, requireContext()) { successful ->
                    binding.progress.isVisible = false
                    if (successful) {
                        showToast(R.string.arsc_viewer_saved)
                        refreshList()
                    } else {
                        showToast(R.string.arsc_viewer_save_failed)
                    }
                }
            } catch (e: Exception) {
                binding.progress.isVisible = false
                showToast(R.string.arsc_viewer_save_failed)
            }
        }
    }

    private fun findEntry(table: ArscTable, row: ArscEntryRow): ArscEntry? {
        for (pkg in table.packages) {
            for (type in pkg.types) {
                for (entry in type.entries) {
                    if ("${type.name}/${entry.key}" == row.qualifiedKey) return entry
                }
            }
        }
        return null
    }

    private fun flattenEntries(table: ArscTable): List<ArscEntryRow> {
        val rows = mutableListOf<ArscEntryRow>()
        for (pkg in table.packages) {
            for (type in pkg.types) {
                for (entry in type.entries) {
                    rows.add(ArscEntryRow("${type.name}/${entry.key}", entry.value, pkg.name))
                }
            }
        }
        return rows.sortedBy { it.qualifiedKey }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs

    companion object {
        private const val MAX_FILE_SIZE = 16 * 1024 * 1024
    }
}

/** Display row: a fully-qualified resource key with its value and owning package. */
@kotlinx.parcelize.Parcelize
data class ArscEntryRow(val qualifiedKey: String, val value: String, val packageName: String) :
    android.os.Parcelable

/**
 * Adapter reusing the dex_string_item layout (two-line: key + value). Long-press copies the value;
 * click opens the edit dialog for string entries.
 */
class ArscEntryAdapter(
    private val onClick: (ArscEntryRow) -> Unit
) : SimpleAdapter<ArscEntryRow, ArscEntryAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).qualifiedKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(DexStringItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        holder.binding.valueText.text = row.qualifiedKey
        holder.binding.metaText.isVisible = true
        holder.binding.metaText.text = row.value
        holder.binding.root.setOnClickListener { onClick(getItem(holder.bindingAdapterPosition)) }
        holder.binding.root.setOnLongClickListener {
            clipboardManager.copyText(row.value, holder.binding.root.context)
            true
        }
    }

    class ViewHolder(val binding: DexStringItemBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)
}
