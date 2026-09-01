/*
 * Copyright (c) NexFiles contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.kpm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.databinding.KpmItemBinding
import me.zhanghai.android.files.databinding.KpmViewerFragmentBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.viewModels

/**
 * Structural viewer for kernel modules (.ko / .kpm). Cycles between an overview+sections list,
 * the kernel symbol table, and the module's info/strings — the three views that answer "what
 * does this module touch".
 */
class KpmViewerFragment : Fragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { KpmViewerViewModel(args.path) } }

    private lateinit var binding: KpmViewerFragmentBinding

    private var viewIndex = VIEW_OVERVIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            viewIndex = savedInstanceState.getInt(KEY_VIEW_INDEX, VIEW_OVERVIEW)
        }
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = KpmViewerFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = OverviewAdapter()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.modelState.collect { state -> onModelStateChanged(state) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_VIEW_INDEX, viewIndex)
    }

    private fun onModelStateChanged(state: DataState<KpmModel>) {
        val hasValue = state.data != null
        binding.progress.fadeToVisibilityUnsafe(state is DataState.Loading && !hasValue)
        binding.errorText.fadeToVisibilityUnsafe(state is DataState.Error && !hasValue)
        binding.recyclerView.fadeToVisibilityUnsafe(hasValue)
        if (state is DataState.Error) {
            binding.errorText.text = state.throwable.toString()
        }
        val model = state.data ?: return
        refreshList(model)
    }

    private fun refreshList(model: KpmModel) {
        when (viewIndex) {
            VIEW_OVERVIEW -> showOverview(model)
            VIEW_SECTIONS -> showSections(model)
            VIEW_SYMBOLS -> showSymbols(model)
            VIEW_INFO -> showInfo(model)
        }
        activity?.invalidateOptionsMenu()
    }

    private fun ensureAdapter(kind: Int) {
        val current = binding.recyclerView.adapter
        val matches = when (kind) {
            VIEW_OVERVIEW -> current is OverviewAdapter
            VIEW_SECTIONS -> current is SectionAdapter
            VIEW_SYMBOLS -> current is SymbolAdapter
            else -> current is InfoAdapter
        }
        if (matches) return
        binding.recyclerView.adapter = when (kind) {
            VIEW_OVERVIEW -> OverviewAdapter()
            VIEW_SECTIONS -> SectionAdapter()
            VIEW_SYMBOLS -> SymbolAdapter()
            else -> InfoAdapter()
        }
    }

    private fun showOverview(model: KpmModel) {
        ensureAdapter(VIEW_OVERVIEW)
        val adapter = binding.recyclerView.adapter as OverviewAdapter
        val rows = buildList {
            add("Type" to model.elfTypeName)
            add("Architecture" to model.machineName)
            add("Format" to if (model.isKpmModule) "KernelPatch module (KPM)" else "ELF kernel module / object")
            add("Sections" to model.sections.size.toString())
            add("Symbols" to model.symbols.size.toString())
            add(
                "Undefined symbols" to
                    model.symbols.count { it.isUndefined }.toString()
            )
            val referenced = model.symbols.filter { it.isUndefined }.map { it.name }
            add("Referenced kernel symbols" to referenced.take(24).joinToString(", "))
        }
        adapter.replace(rows)
    }

    private fun showSections(model: KpmModel) {
        ensureAdapter(VIEW_SECTIONS)
        val adapter = binding.recyclerView.adapter as SectionAdapter
        adapter.replace(model.sections)
    }

    private fun showSymbols(model: KpmModel) {
        ensureAdapter(VIEW_SYMBOLS)
        val adapter = binding.recyclerView.adapter as SymbolAdapter
        adapter.replace(model.symbols)
    }

    private fun showInfo(model: KpmModel) {
        ensureAdapter(VIEW_INFO)
        val adapter = binding.recyclerView.adapter as InfoAdapter
        val rows = buildList {
            model.moduleInfo.forEach { (key, value) -> add("$key = $value") }
            model.kpmInfo?.let { kpm ->
                add("KPM magic: ${kpm.magic}")
                kpm.versionName?.let { add("KPM version: $it") }
                kpm.author?.let { add("KPM author: $it") }
            }
            addAll(model.strings)
        }
        adapter.replace(rows)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.kpm_viewer, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        // The toggle's title shows the next view in the cycle; only meaningful with a model.
        menu.findItem(R.id.action_toggle_view)?.apply {
            val model = (viewModel.modelState.value as? DataState.Success)?.data
            setTitle(nextViewTitle())
            isVisible = model != null
        }
    }

    private fun nextViewTitle(): Int = when (viewIndex) {
        VIEW_OVERVIEW -> R.string.kpm_viewer_view_sections
        VIEW_SECTIONS -> R.string.kpm_viewer_view_symbols
        else -> R.string.kpm_viewer_view_info
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_view -> {
                viewIndex = (viewIndex + 1) % VIEW_COUNT
                (viewModel.modelState.value as? DataState.Success)?.data?.let { refreshList(it) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Parcelize
    data class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs

    companion object {
        private const val KEY_VIEW_INDEX = "view_index"
        private const val VIEW_OVERVIEW = 0
        private const val VIEW_SECTIONS = 1
        private const val VIEW_SYMBOLS = 2
        private const val VIEW_INFO = 3
        private const val VIEW_COUNT = 4
    }
}

private typealias Row = Pair<String, String>

private class OverviewAdapter : SimpleAdapter<Row, RecyclerView.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = false

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        ViewHolder(KpmItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val binding = KpmItemBinding.bind(holder.itemView)
        val (name, value) = getItem(position)
        binding.nameText.text = name
        binding.metaText.text = value
    }

    class ViewHolder(val binding: KpmItemBinding) : RecyclerView.ViewHolder(binding.root)
}

private class SectionAdapter : SimpleAdapter<KpmModel.Section, RecyclerView.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long =
        getItem(position).let { "${it.name}:${it.offset}".hashCode().toLong() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        ViewHolder(KpmItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val binding = KpmItemBinding.bind(holder.itemView)
        val section = getItem(position)
        binding.nameText.text = section.name
        val tags = buildList {
            if (section.isKpmInfo) add("KPM info")
            if (section.isModInfo) add("modinfo")
            if (section.isVersions) add("versions")
        }
        binding.metaText.text = buildString {
            append("type=${section.type} size=${section.size}")
            if (tags.isNotEmpty()) append(" [").append(tags.joinToString(", ")).append("]")
        }
        holder.itemView.setOnLongClickListener {
            clipboardManager.copyText(section.name, holder.itemView.context)
            true
        }
    }

    class ViewHolder(val binding: KpmItemBinding) : RecyclerView.ViewHolder(binding.root)
}

private class SymbolAdapter : SimpleAdapter<KpmModel.Symbol, RecyclerView.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).name.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        ViewHolder(KpmItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val binding = KpmItemBinding.bind(holder.itemView)
        val symbol = getItem(position)
        binding.nameText.text = symbol.name
        binding.metaText.text = buildString {
            append(if (symbol.isUndefined) "imported" else "defined")
            append(" · bind=${symbol.bind} type=${symbol.type}")
            if (symbol.size > 0) append(" · size=${symbol.size}")
        }
        holder.itemView.setOnLongClickListener {
            clipboardManager.copyText(symbol.name, holder.itemView.context)
            true
        }
    }

    class ViewHolder(val binding: KpmItemBinding) : RecyclerView.ViewHolder(binding.root)
}

private class InfoAdapter : SimpleAdapter<String, RecyclerView.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        ViewHolder(KpmItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val binding = KpmItemBinding.bind(holder.itemView)
        val value = getItem(position)
        binding.nameText.text = value
        binding.metaText.isVisible = false
        holder.itemView.setOnLongClickListener {
            clipboardManager.copyText(value, holder.itemView.context)
            true
        }
    }

    class ViewHolder(val binding: KpmItemBinding) : RecyclerView.ViewHolder(binding.root)
}
