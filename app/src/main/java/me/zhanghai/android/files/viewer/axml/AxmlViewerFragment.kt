/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.axml

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AxmlViewerFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.viewer.text.ConfirmCloseDialogFragment
import java.io.IOException

/**
 * Viewer + editor for Android binary XML (AndroidManifest.xml, compiled layouts, etc.). Two modes:
 *
 *  - **View** (default): parses the binary format and shows the decoded XML in a read-only
 *    monospace [TextView].
 *  - **Edit**: toggled from the toolbar menu, shows the editable [EditText] (laid out alongside
 *    the read-only view in [axml_viewer_fragment.xml] — we toggle visibility rather than swapping
 *    views at runtime, which preserves scroll position and is much simpler). On save, the edited
 *    text is re-parsed into a DOM tree and re-encoded as binary AXML via [BinaryXmlEncoder],
 *    then written back through [FileJobService.write].
 *
 * Binary XML detection is by the file header: the first uint16 is 0x0003 (RES_XML_TYPE). The
 * fragment handles both local files and archive-internal paths (e.g. APK's AndroidManifest.xml)
 * transparently, since [Path.newInputStream] works on both.
 */
class AxmlViewerFragment : Fragment(), ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var binding: AxmlViewerFragmentBinding

    private var isEditing = false
    private var currentXmlText: String = ""

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AxmlViewerFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Intercept back press when editing and there are unsaved changes.
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                ConfirmCloseDialogFragment.show(this@AxmlViewerFragment)
            }
        }
        addOnBackPressedCallback(onBackPressedCallback)
        binding.textEdit.doAfterTextChanged { updateBackPressState() }
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.axml_viewer, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        // Toggle the edit/save item visibility depending on mode.
        menu.findItem(R.id.action_edit)?.isVisible = !isEditing
        menu.findItem(R.id.action_save)?.isVisible = isEditing
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit -> { enterEditMode(); true }
            R.id.action_save -> { save(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            binding.scrollView.isVisible = false
            try {
                val xml = withContext(Dispatchers.IO) {
                    val size = args.path.size()
                    if (size > MAX_FILE_SIZE) throw IOException("File too large ($size bytes)")
                    val bytes = args.path.newInputStream().use { it.readBytes() }
                    val node = BinaryXmlParser.parse(bytes)
                    BinaryXmlPrinter.print(node)
                }
                currentXmlText = xml
                binding.textView.text = xml
                binding.scrollView.isVisible = true
            } catch (e: Exception) {
                binding.errorText.text = e.toString()
                binding.errorText.isVisible = true
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    /**
     * Switches to edit mode: hides the read-only [TextView] and reveals the editable [EditText],
     * pre-populated with the current XML text. The two views live in the same [FrameLayout]
     * inside the [ScrollView] host, so the scroll position is preserved naturally.
     */
    private fun enterEditMode() {
        isEditing = true
        binding.textView.isVisible = false
        binding.textEdit.setText(currentXmlText)
        binding.textEdit.isVisible = true
        binding.textEdit.requestFocus()
        requireActivity().invalidateOptionsMenu()
    }

    /** True when in edit mode and the editor text differs from the last loaded/saved XML. */
    private val hasUnsavedChanges: Boolean
        get() = isEditing && binding.textEdit.text.toString() != currentXmlText

    private fun updateBackPressState() {
        onBackPressedCallback.isEnabled = hasUnsavedChanges
    }

    private fun save() {
        val textToSave = binding.textEdit.text.toString()
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val node = TextXmlParser.parse(textToSave)
                    BinaryXmlEncoder.encode(node)
                }
                writeAxmlFile(args.path, bytes, requireContext())
            } catch (e: Exception) {
                binding.progress.isVisible = false
                binding.errorText.text = e.message ?: e.toString()
                binding.errorText.isVisible = true
                showToast(R.string.axml_viewer_save_failed)
            }
        }
    }

    private fun writeAxmlFile(path: Path, bytes: ByteArray, context: Context) {
        FileJobService.write(path, bytes, context) { successful ->
            binding.progress.isVisible = false
            if (successful) {
                showToast(R.string.axml_viewer_saved)
                // Exit edit mode and reload the re-encoded file to reflect the saved state.
                exitEditMode()
                load()
            } else {
                showToast(R.string.axml_viewer_save_failed)
            }
            requireActivity().invalidateOptionsMenu()
        }
    }

    /** Returns to read-only mode: hides the [EditText], shows the [TextView]. */
    private fun exitEditMode() {
        isEditing = false
        binding.textEdit.isVisible = false
        binding.textView.isVisible = true
        onBackPressedCallback.isEnabled = false
    }

    // -- ConfirmCloseDialogFragment.Listener --

    override fun finish() {
        requireActivity().finish()
    }

    /**
     * Called by the host activity on toolbar navigate-up. If editing with unsaved changes, show
     * the confirmation dialog instead of finishing.
     */
    fun onSupportNavigateUp(): Boolean {
        updateBackPressState()
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs

    companion object {
        private const val MAX_FILE_SIZE = 4 * 1024 * 1024 // 4 MiB; manifests are tiny.
    }
}
