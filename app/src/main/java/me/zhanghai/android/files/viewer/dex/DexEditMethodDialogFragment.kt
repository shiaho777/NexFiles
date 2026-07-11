/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DexEditMethodDialogBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.setTextWithSelection
import me.zhanghai.android.files.util.show

/**
 * Dialog for editing a method's signature (parameters and return type). Parameters are entered as
 * a raw DEX type descriptor string (e.g. `Ljava/lang/String;I` for `String, int`); the return
 * type is a single descriptor (e.g. `V` for void).
 *
 * The caller parses the entered strings into a `List<String>` via [parseTypeDescriptors] and
 * passes them to [DexEditorModel.changeMethodSignature].
 */
class DexEditMethodDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DexEditMethodDialogBinding.inflate(requireContext().layoutInflater)
        binding.parametersText.setTextWithSelection(args.parameters.joinToString(""))
        binding.returnTypeText.setTextWithSelection(args.returnType)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.dex_edit_method_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newParams = parseTypeDescriptors(binding.parametersText.text.toString())
                val newReturn = binding.returnTypeText.text.toString().trim()
                listener.onMethodSignatureChanged(
                    args.definingClass, args.name, args.parameters, args.returnType,
                    newParams, newReturn
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @Parcelize
    class Args(
        val definingClass: String,
        val name: String,
        val parameters: List<String>,
        val returnType: String
    ) : ParcelableArgs

    interface Listener {
        fun onMethodSignatureChanged(
            definingClass: String, name: String,
            oldParameters: List<String>, oldReturnType: String,
            newParameters: List<String>, newReturnType: String
        )
    }

    companion object {
        fun show(
            definingClass: String, name: String, parameters: List<String>, returnType: String,
            fragment: Fragment
        ) {
            DexEditMethodDialogFragment().putArgs(
                Args(definingClass, name, parameters, returnType)
            ).show(fragment)
        }
    }
}

/**
 * Parses a concatenated string of DEX type descriptors into a list of individual descriptors.
 * Each descriptor is one of: a primitive (single char like `I`, `Z`, `V`), an array (`[` prefix),
 * or a class (`L...;`). This is the inverse of what the user sees in the edit dialog.
 */
internal fun parseTypeDescriptors(concatenated: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < concatenated.length) {
        val c = concatenated[i]
        when {
            c == 'L' -> {
                val end = concatenated.indexOf(';', i)
                if (end < 0) { result.add(concatenated.substring(i)); break }
                result.add(concatenated.substring(i, end + 1))
                i = end + 1
            }
            c == '[' -> {
                // Array: consume all leading '[' then the element type.
                var j = i
                while (j < concatenated.length && concatenated[j] == '[') j++
                if (j < concatenated.length && concatenated[j] == 'L') {
                    val end = concatenated.indexOf(';', j)
                    if (end < 0) { result.add(concatenated.substring(i)); break }
                    result.add(concatenated.substring(i, end + 1))
                    i = end + 1
                } else {
                    result.add(concatenated.substring(i, j + 1))
                    i = j + 1
                }
            }
            else -> {
                result.add(c.toString())
                i++
            }
        }
    }
    return result
}
