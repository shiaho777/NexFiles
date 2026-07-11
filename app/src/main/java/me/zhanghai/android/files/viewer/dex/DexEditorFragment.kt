/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.databinding.DexEditorFragmentBinding
import me.zhanghai.android.files.databinding.DexReplaceStringDialogBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.primaryText
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import org.jf.dexlib2.iface.ClassDef

class DexEditorFragment : Fragment(),
    DexRenameDialogFragment.Listener,
    DexEditMethodDialogFragment.Listener {
    private val args by args<Args>()

    private val viewModel by viewModels { { DexEditorViewModel(args.path) } }

    private lateinit var binding: DexEditorFragmentBinding

    private var showingStrings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DexEditorFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = DexClassAdapter { cls -> showClassDetail(cls) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.modelState.collect { state -> onModelStateChanged(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.writeFileState.collect { state -> onWriteStateChanged(state) }
            }
        }
    }

    private fun onModelStateChanged(state: DataState<DexEditorModel>) {
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

    private fun refreshList(model: DexEditorModel) {
        if (showingStrings) showStrings(model) else showClasses(model)
        // Update the toggle's title to reflect what the other view would show.
        activity?.invalidateOptionsMenu()
    }

    private fun showClasses(model: DexEditorModel) {
        ensureAdapter(false)
        val adapter = binding.recyclerView.adapter as DexClassAdapter
        adapter.replace(model.classes)
        binding.emptyView.isVisible = model.classCount == 0
    }

    private fun showStrings(model: DexEditorModel) {
        ensureAdapter(true)
        val adapter = binding.recyclerView.adapter as DexStringAdapter
        adapter.replace(model.strings)
        binding.emptyView.isVisible = model.stringCount == 0
    }

    private fun ensureAdapter(strings: Boolean) {
        val current = binding.recyclerView.adapter
        val matches = if (strings) current is DexStringAdapter else current is DexClassAdapter
        if (matches) return
        binding.recyclerView.adapter = if (strings) DexStringAdapter() else DexClassAdapter { cls ->
            showClassDetail(cls)
        }
    }

    private fun showClassDetail(cls: ClassDef) {
        val summary = buildString {
            appendLine("type: ${cls.type}")
            cls.superclass?.let { appendLine("super: $it") }
            val interfaces = cls.interfaces.toList()
            if (interfaces.isNotEmpty()) {
                appendLine("implements: ${interfaces.joinToString(", ")}")
            }
            appendLine("fields: ${cls.fields.count()}")
            appendLine("methods: ${cls.methods.count()}")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(humanizeType(cls.type))
            .setMessage(summary.trim())
            .setPositiveButton(R.string.smali_editor_title) { _, _ ->
                openSmaliEditor(cls)
            }
            .setNeutralButton(R.string.dex_rename_title) { _, _ ->
                DexRenameDialogFragment.showClass(cls.type, this)
            }
            .setNegativeButton(R.string.copy) { _, _ ->
                clipboardManager.copyText(cls.type, requireContext())
            }
            .show()
    }

    private fun openSmaliEditor(cls: ClassDef) {
        val intent = android.content.Intent(requireContext(), SmaliEditorActivity::class.java)
            .apply {
                extraPath = args.path
                putExtra(SmaliEditorActivity.EXTRA_CLASS_TYPE, cls.type)
            }
        startActivitySafe(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.dex_editor, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        // The toggle item's title flips between "Strings" and "Classes" depending on which view
        // is currently shown; re-evaluated on every invalidateOptionsMenu() call.
        menu.findItem(R.id.action_toggle_view)?.apply {
            val model = (viewModel.modelState.value as? DataState.Success)?.data
            setTitle(if (showingStrings) R.string.dex_editor_view_classes
                else R.string.dex_editor_view_strings)
            isVisible = model != null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_view -> {
                showingStrings = !showingStrings
                (viewModel.modelState.value as? DataState.Success)?.data?.let { refreshList(it) }
                true
            }
            R.id.action_replace_string -> {
                showReplaceStringDialog()
                true
            }
            R.id.action_save -> {
                triggerSave()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showReplaceStringDialog() {
        if (viewModel.modelState.value !is DataState.Success) return
        val dialogBinding = DexReplaceStringDialogBinding.inflate(requireContext().layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dex_editor_replace_string_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dex_editor_replace_string_apply) { _, _ ->
                val old = dialogBinding.currentText.text.toString()
                val new = dialogBinding.newValueText.text.toString()
                if (old.isEmpty()) {
                    showToast(R.string.dex_editor_replace_string_empty_old)
                    return@setPositiveButton
                }
                viewModel.replaceString(old, new)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Pre-fill the "current value" field with the clipboard contents when it's a plain
        // string, since the common workflow is "I just copied the value I want to replace".
        val text = runCatching { clipboardManager.primaryText }.getOrNull()
        if (!text.isNullOrEmpty()) {
            dialogBinding.currentText.text = text
        }
    }

    private fun triggerSave() {
        if (!viewModel.isDirty) {
            showToast(R.string.dex_editor_no_changes)
            return
        }
        // Write straight back to the source path. A dedicated "save as" picker can be added
        // later; for now the common reverse-engineering workflow is in-place edits.
        viewModel.writeFile(viewModel.file, requireContext())
    }

    private fun onWriteStateChanged(state: ActionState<Path, Unit>) {
        when (state) {
            is ActionState.Success -> {
                showToast(R.string.dex_editor_saved)
                viewModel.finishWritingFile()
            }
            is ActionState.Error -> {
                showToast(R.string.dex_editor_save_failed)
                viewModel.finishWritingFile()
            }
            else -> {}
        }
    }

    // -- DexRenameDialogFragment.Listener --

    override fun onRename(args: DexRenameDialogFragment.Args, newValue: String) {
        val count = when (args.kind) {
            DexRenameDialogFragment.Kind.CLASS -> viewModel.renameClass(args.currentValue, newValue)
            DexRenameDialogFragment.Kind.METHOD -> viewModel.renameMethod(
                args.definingClass, args.memberName, args.parameters, args.returnType, newValue
            )
            DexRenameDialogFragment.Kind.FIELD -> viewModel.renameField(
                args.definingClass, args.memberName, args.fieldType, newValue
            )
        }
        showToast(getString(R.string.dex_rename_success, count))
    }

    // -- DexEditMethodDialogFragment.Listener --

    override fun onMethodSignatureChanged(
        definingClass: String, name: String,
        oldParameters: List<String>, oldReturnType: String,
        newParameters: List<String>, newReturnType: String
    ) {
        val count = viewModel.changeMethodSignature(
            definingClass, name, oldParameters, oldReturnType, newParameters, newReturnType
        )
        showToast(getString(R.string.dex_rename_success, count))
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

/**
 * Adapter for the class list. Each row shows the de-obfuscated class type and a count of its
 * members. Long-press copies the raw type descriptor.
 */
class DexClassAdapter(
    private val onClick: (ClassDef) -> Unit
) : SimpleAdapter<ClassDef, DexClassAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).type.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            me.zhanghai.android.files.databinding.DexClassItemBinding.inflate(
                parent.context.layoutInflater, parent, false
            )
        ) { onClick(getItem(it.bindingAdapterPosition)) }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val cls = getItem(position)
        binding.nameText.text = humanizeType(cls.type)
        binding.metaText.text = "${cls.fields.count()} fields · ${cls.methods.count()} methods"
        binding.root.setOnLongClickListener {
            clipboardManager.copyText(cls.type, binding.root.context)
            true
        }
    }

    class ViewHolder(
        val binding: me.zhanghai.android.files.databinding.DexClassItemBinding,
        onClick: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onClick(this) }
        }
    }
}

/**
 * Adapter for the string pool. Long-press copies the value.
 */
class DexStringAdapter :
    SimpleAdapter<String, DexStringAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            me.zhanghai.android.files.databinding.DexStringItemBinding.inflate(
                parent.context.layoutInflater, parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val value = getItem(position)
        binding.valueText.text = value
        binding.metaText.isVisible = false
        binding.root.setOnLongClickListener {
            clipboardManager.copyText(value, binding.root.context)
            true
        }
    }

    class ViewHolder(
        val binding: me.zhanghai.android.files.databinding.DexStringItemBinding
    ) : RecyclerView.ViewHolder(binding.root)
}

/**
 * Converts a DEX type descriptor (e.g. `Lcom/foo/Bar;`) into a human-readable form
 * (`com.foo.Bar`) for display. Non-reference types (I, Z, [I, etc.) are returned verbatim.
 */
fun humanizeType(descriptor: String): String {
    if (descriptor.isEmpty()) return descriptor
    if (descriptor[0] != 'L' || !descriptor.endsWith(';')) return descriptor
    return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
}
