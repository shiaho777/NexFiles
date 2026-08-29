/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import java.nio.charset.Charset

class TextEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: TextEditorFragmentBinding

    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var isSettingText = false

    // In-editor find state. Matches are stored as start/end index pairs into the editable text;
    // currentIndex points at the highlighted one. We re-scan on every query change but not on
    // every keystroke inside the editor (editing collapses the find session).
    private val findMatches = mutableListOf<IntRange>()
    private var findCurrentIndex = -1

    // Picks a destination path via the file list, then writes the current editor text to it.
    private val saveAsLauncher =
        registerForActivityResult(FileListActivity.CreateFileContract(), ::onSaveAsResult)

    // Holds the editor text between launching the save-as picker and receiving its result, since
    // the picker only returns the chosen path.
    private var pendingSaveAsText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        lifecycleScope.launchWhenStarted {
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    ConfirmCloseDialogFragment.show(this@TextEditorFragment)
                }
            }
            launch {
                viewModel.isTextChanged.collect {
                    onBackPressedCallback.isEnabled = viewModel.isTextChanged.value
                }
            }
            addOnBackPressedCallback(onBackPressedCallback)

            launch { viewModel.encoding.collect { onEncodingChanged(it) } }
            launch { viewModel.textState.collect { onTextStateChanged(it) } }
            launch { viewModel.isTextChanged.collect { onIsTextChangedChanged(it) } }
            launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TextEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            // TODO: Show a toast.
            finish()
            return
        }
        this.argsFile = argsFile

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }

        // TODO: Move reload-prevent here so that we can also handle save-as, etc. Or maybe just get
        //  rid of the mPathLiveData in TextEditorViewModel.
        ThemedFastScroller.create(binding.scrollView)
        // Manually save and restore state in view model to avoid TransactionTooLargeException.
        binding.textEdit.isSaveEnabled = false
        val textEditSavedState = viewModel.removeEditTextSavedState()
        if (textEditSavedState != null) {
            binding.textEdit.onRestoreInstanceState(textEditSavedState)
        }
        binding.textEdit.doAfterTextChanged {
            if (isSettingText) {
                return@doAfterTextChanged
            }
            // Might happen if the animation is running and user is quick enough.
            if (viewModel.textState.value !is DataState.Success) {
                return@doAfterTextChanged
            }
            viewModel.isTextChanged.value = true
            // Editing invalidates any in-flight find session; clear it so stale spans are removed
            // before the next search.
            if (findMatches.isNotEmpty()) {
                clearFind()
            }
        }

        setUpFindBar()

        // TODO: Request storage permission if not granted.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        viewModel.setEditTextSavedState(binding.textEdit.onSaveInstanceState())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateSaveMenuItem()
        updateEncodingMenuItems()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_find -> {
                showFindBar()
                true
            }
            R.id.action_save_as -> {
                saveAs()
                true
            }
            R.id.action_reload -> {
                onReload()
                true
            }
            Menu.FIRST -> {
                viewModel.encoding.value = Charset.forName(item.titleCondensed!!.toString())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    fun onSupportNavigateUp(): Boolean {
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    private fun onEncodingChanged(encoding: Charset) {
        updateEncodingMenuItems()
    }

    private fun updateEncodingMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val charsetName = viewModel.encoding.value.name()
        val charsetItem = menuBinding.encodingSubMenu.children
            .find { it.titleCondensed == charsetName }!!
        charsetItem.isChecked = true
    }

    private fun onTextStateChanged(state: DataState<String>) {
        updateTitle()
        when (state) {
            is DataState.Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeOutUnsafe()
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeInUnsafe()
                if (!viewModel.isTextChanged.value) {
                    setText(state.data)
                }
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = state.throwable.toString()
                binding.textEdit.fadeOutUnsafe()
            }
        }
    }

    private fun setText(text: String?) {
        isSettingText = true
        binding.textEdit.setText(text)
        isSettingText = false
        viewModel.isTextChanged.value = false
    }

    private fun onIsTextChangedChanged(changed: Boolean) {
        updateTitle()
    }

    private fun updateTitle() {
        val fileName = viewModel.file.value.fileName.toString()
        val changed = viewModel.isTextChanged.value
        requireActivity().title = getString(
            if (changed) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        viewModel.isTextChanged.value = false
        viewModel.reload()
    }

    private fun save() {
        val text = binding.textEdit.text.toString()
        viewModel.writeFile(argsFile, text, requireContext())
    }

    private fun saveAs() {
        // Carry the current editor text (including unsaved edits) into the new file, rather than
        // copying the on-disk original.
        val text = binding.textEdit.text.toString()
        val fileName = viewModel.file.value.fileName.toString()
        val mimeType = me.zhanghai.android.files.file.MimeType.TEXT_PLAIN
        saveAsLauncher.launch(Triple(mimeType, fileName, argsFile.parent))
        pendingSaveAsText = text
    }

    private fun onSaveAsResult(target: Path?) {
        val text = pendingSaveAsText
        pendingSaveAsText = null
        if (target == null || text == null) {
            return
        }
        viewModel.writeFile(target, text, requireContext())
    }

    private fun setUpFindBar() {
        val findBar = binding.findBar
        findBar.findEdit.doAfterTextChanged { performFind() }
        findBar.findPreviousButton.setOnClickListener { findPrevious() }
        findBar.findNextButton.setOnClickListener { findNext() }
        findBar.findCloseButton.setOnClickListener { hideFindBar() }
        findBar.findEdit.setOnEditorActionListener { _, _, _ ->
            findNext()
            true
        }
    }

    private fun showFindBar() {
        binding.findBar.root.isVisible = true
        binding.findBar.findEdit.requestFocus()
    }

    private fun hideFindBar() {
        binding.findBar.root.isVisible = false
        clearFind()
    }

    private fun clearFind() {
        findMatches.clear()
        findCurrentIndex = -1
        binding.findBar.findCountText.text = null
        binding.findBar.findCountText.isVisible = false
        // Strip any highlight span we added; the underlying text is untouched.
        val editable = binding.textEdit.text ?: return
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        for (span in spans) {
            editable.removeSpan(span)
        }
    }

    private fun performFind() {
        val query = binding.findBar.findEdit.text.toString()
        val editable = binding.textEdit.text ?: return
        clearFind()
        if (query.isEmpty()) {
            return
        }
        var index = 0
        while (true) {
            val found = editable.indexOf(query, index, ignoreCase = true)
            if (found < 0) {
                break
            }
            findMatches.add(found until found + query.length)
            index = found + query.length
        }
        if (findMatches.isEmpty()) {
            binding.findBar.findCountText.text = getString(R.string.text_editor_find_no_results)
            binding.findBar.findCountText.isVisible = true
            return
        }
        // Start from the current caret position, so repeated finds march forward naturally.
        val caret = binding.textEdit.selectionStart.coerceAtLeast(0)
        findCurrentIndex = findMatches.indexOfFirst { it.first >= caret }.let {
            if (it < 0) 0 else it
        }
        applyCurrentMatchHighlight()
    }

    private fun findNext() {
        if (findMatches.isEmpty()) {
            performFind()
            return
        }
        findCurrentIndex = (findCurrentIndex + 1) % findMatches.size
        applyCurrentMatchHighlight()
    }

    private fun findPrevious() {
        if (findMatches.isEmpty()) {
            performFind()
            return
        }
        findCurrentIndex = if (findCurrentIndex <= 0) findMatches.lastIndex else findCurrentIndex - 1
        applyCurrentMatchHighlight()
    }

    private fun applyCurrentMatchHighlight() {
        if (findCurrentIndex !in findMatches.indices) {
            return
        }
        val editable = binding.textEdit.text ?: return
        // Remove only the spans we own (background highlights), leaving selection untouched.
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        for (span in spans) {
            editable.removeSpan(span)
        }
        val range = findMatches[findCurrentIndex]
        editable.setSpan(
            BackgroundColorSpan(highlightColor), range.first, range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // Move the caret and scroll so the match is visible.
        binding.textEdit.requestFocus()
        binding.textEdit.setSelection(range.first, range.last + 1)
        val layout = binding.textEdit.layout
        if (layout != null) {
            val line = layout.getLineForOffset(range.first)
            val lineTop = layout.getLineTop(line)
            binding.scrollView.smoothScrollTo(0, lineTop)
        }
        binding.findBar.findCountText.text = getString(
            R.string.text_editor_find_count_format, findCurrentIndex + 1, findMatches.size
        )
        binding.findBar.findCountText.isVisible = true
    }

    private val highlightColor: Int by lazy {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.colorControlHighlight, tv, true
        )
        if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT) tv.data else 0x6633B5E5
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                viewModel.isTextChanged.value = false
            }
            // The error will be toasted by service so we should never show it in UI.
            is ActionState.Error -> viewModel.finishWritingFile()
        }
    }

    private fun updateSaveMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.saveItem.isEnabled = viewModel.writeFileState.value.isReady
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val menu: Menu,
        val saveItem: MenuItem,
        val encodingSubMenu: SubMenu
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.text_editor, menu)
                val encodingSubMenu = menu.findItem(R.id.action_encoding).subMenu!!
                for ((charsetName, charset) in Charset.availableCharsets()) {
                    // HACK: Use titleCondensed to store charset name.
                    encodingSubMenu.add(Menu.NONE, Menu.FIRST, Menu.NONE, charset.displayName())
                        .titleCondensed = charsetName
                }
                encodingSubMenu.setGroupCheckable(Menu.NONE, true, true)
                return MenuBinding(menu, menu.findItem(R.id.action_save), encodingSubMenu)
            }
        }
    }
}
