/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
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
import me.zhanghai.android.files.databinding.SmaliEditorFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.viewer.text.ConfirmCloseDialogFragment
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Full-screen smali source editor for a single DEX class. The flow is:
 *  1. Load the DEX file, find the target [classType], disassemble it via [DexSmaliBridge].
 *  2. Show the smali text in an editable [EditText] (monospace, horizontal scroll).
 *  3. On save: reassemble the edited text, swap the new ClassDef into the DEX, serialize, and
 *     write back through [FileJobService.write] (same path as the DEX editor's save).
 *
 * The assembled class replaces the original by type descriptor. If the user changed the `.class`
 * directive's type in the smali, the old class stays and a new one is added — that's the same
 * semantics as smali/smali round-tripping in apktool.
 *
 * **Unsaved-changes guard**: if the editor text differs from the original, the system back button
 * is intercepted to show a confirmation dialog, preventing accidental loss of edits. The same
 * check gates the toolbar navigate-up action.
 *
 * **Syntax error feedback**: when assembly fails, the exception message (which includes error
 * counts from the ANTLR lexer/parser/walker) is shown in the error text area so the user can see
 * what went wrong, not just a generic "failed" toast.
 */
class SmaliEditorFragment : Fragment(), ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var binding: SmaliEditorFragmentBinding

    private var dexModel: DexEditorModel? = null
    /** The original smali text as loaded from the DEX, used to detect unsaved changes. */
    private var originalSmaliText: String = ""

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = SmaliEditorFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Intercept back press when there are unsaved changes.
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                ConfirmCloseDialogFragment.show(this@SmaliEditorFragment)
            }
        }
        addOnBackPressedCallback(onBackPressedCallback)
        // Update the back-press guard whenever the editor text changes.
        binding.textEdit.doAfterTextChanged { updateBackPressState() }
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.scrollView.isVisible = false
            binding.errorText.isVisible = false
            try {
                val smaliText = withContext(Dispatchers.IO) {
                    val size = args.path.size()
                    if (size > MAX_FILE_SIZE) throw IOException("DEX file too large ($size bytes)")
                    args.path.newInputStream().use { input ->
                        val model = DexEditorModel.read(input)
                        dexModel = model
                        val classDef = model.classes.find { it.type == args.classType }
                            ?: throw IOException("Class ${args.classType} not found in DEX")
                        DexSmaliBridge.disassembleClass(classDef)
                    }
                }
                originalSmaliText = smaliText
                binding.textEdit.setText(smaliText)
                binding.scrollView.isVisible = true
            } catch (e: Exception) {
                binding.errorText.text = e.toString()
                binding.errorText.isVisible = true
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    /** True when the editor text differs from the originally loaded smali. */
    private val hasUnsavedChanges: Boolean
        get() = originalSmaliText.isNotEmpty() &&
            binding.textEdit.text.toString() != originalSmaliText

    private fun updateBackPressState() {
        onBackPressedCallback.isEnabled = hasUnsavedChanges
    }

    /**
     * Called from the host activity's save menu item. Reassembles the current text, replaces the
     * class in the model, and writes the whole DEX back.
     */
    fun save() {
        val model = dexModel ?: run {
            showToast(R.string.smali_editor_not_loaded)
            return
        }
        val text = binding.textEdit.text.toString()
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            try {
                val dexBytes = withContext(Dispatchers.IO) {
                    val newClass = DexSmaliBridge.assembleClass(text, model.opcodes)
                    model.replaceClass(newClass)
                    val out = ByteArrayOutputStream()
                    model.write(out)
                    out.toByteArray()
                }
                writeDexFile(args.path, dexBytes, requireContext())
            } catch (e: Exception) {
                binding.progress.isVisible = false
                // Show the error details (includes ANTLR error counts) in the error text area so
                // the user can diagnose syntax issues, rather than just a generic toast.
                binding.errorText.text = e.message ?: e.toString()
                binding.errorText.isVisible = true
                showToast(R.string.smali_editor_assemble_failed)
            }
        }
    }

    private fun writeDexFile(path: Path, bytes: ByteArray, context: Context) {
        FileJobService.write(path, bytes, context) { successful ->
            binding.progress.isVisible = false
            if (successful) {
                showToast(R.string.smali_editor_saved)
                // After a successful save, the editor text becomes the new "original".
                originalSmaliText = binding.textEdit.text.toString()
                updateBackPressState()
            } else {
                showToast(R.string.smali_editor_save_failed)
            }
        }
    }

    // -- ConfirmCloseDialogFragment.Listener --

    override fun finish() {
        requireActivity().finish()
    }

    /**
     * Called by the host activity on toolbar navigate-up. If there are unsaved changes, show the
     * confirmation dialog instead of finishing; otherwise let the activity finish normally.
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
    class Args(
        val path: @WriteWith<ParcelableParceler> Path,
        val classType: String
    ) : ParcelableArgs

    companion object {
        private const val MAX_FILE_SIZE = 16 * 1024 * 1024
    }
}
