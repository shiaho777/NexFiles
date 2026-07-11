/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileaction

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputLayout
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.NameDialogNameIncludeBinding
import me.zhanghai.android.files.databinding.SignApkDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.FileNameDialogFragment
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.setTextWithSelection

/**
 * Dialog for choosing APK signing schemes (v1/v2/v3) and the output file name. The signing key is
 * the built-in default for now; user-imported keystores will be added to the certificate section
 * in a follow-up.
 *
 * Extends [FileNameDialogFragment] so the output name gets the same validation (non-empty, valid
 * filename, not colliding) as "create file" and "create archive".
 */
class SignApkDialogFragment : FileNameDialogFragment() {
    private val args by args<Args>()

    override val binding: Binding
        get() = super.binding as Binding

    override val listener: Listener
        get() = super.listener as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        if (savedInstanceState == null) {
            // Default output name: "original-signed.apk".
            val originalName = args.file.name
            val baseName = originalName.substringBeforeLast(".apk", originalName)
            binding.nameEdit.setTextWithSelection("$baseName-signed.apk")
        }
        return dialog
    }

    override val titleRes: Int = R.string.sign_apk_title

    override fun onInflateBinding(inflater: LayoutInflater): NameDialogFragment.Binding =
        Binding.inflate(inflater)

    override val initialName: String? = null

    override fun isNameValid(name: String): Boolean {
        if (!super.isNameValid(name)) return false
        // Require at least one scheme checked.
        if (!binding.v1CheckBox.isChecked && !binding.v2CheckBox.isChecked &&
            !binding.v3CheckBox.isChecked
        ) {
            binding.nameLayout.error = getString(R.string.sign_apk_no_scheme_selected)
            return false
        }
        return true
    }

    override fun onOk(name: String) {
        listener.signApk(
            args.file, name,
            binding.v1CheckBox.isChecked,
            binding.v2CheckBox.isChecked,
            binding.v3CheckBox.isChecked
        )
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SignApkDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }

    @Parcelize
    class Args(val file: FileItem) : ParcelableArgs

    private class Binding private constructor(
        root: View,
        nameLayout: TextInputLayout,
        nameEdit: EditText,
        val v1CheckBox: CheckBox,
        val v2CheckBox: CheckBox,
        val v3CheckBox: CheckBox
    ) : NameDialogFragment.Binding(root, nameLayout, nameEdit) {
        companion object {
            fun inflate(inflater: LayoutInflater): Binding {
                val binding = SignApkDialogBinding.inflate(inflater)
                val bindingRoot = binding.root
                val nameBinding = NameDialogNameIncludeBinding.bind(bindingRoot)
                return Binding(
                    bindingRoot, nameBinding.nameLayout, nameBinding.nameEdit,
                    binding.v1CheckBox, binding.v2CheckBox, binding.v3CheckBox
                )
            }
        }
    }

    interface Listener : FileNameDialogFragment.Listener {
        fun signApk(file: FileItem, outputName: String, v1: Boolean, v2: Boolean, v3: Boolean)
    }
}
