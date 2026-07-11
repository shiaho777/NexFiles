/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SignatureListDialogBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/**
 * Lists all signers discovered in the APK (across every scheme), letting the user drill into
 * the full X.509 certificate details for each one.
 *
 * The signer list is computed up-front by [ApkSigningBlockReader] and passed in as args, so
 * this dialog stays purely presentational and never re-opens the APK.
 */
class SignatureListDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: SignatureListDialogBinding

    private lateinit var adapter: SignatureListAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .apply {
                setTitle(R.string.file_properties_apk_signatures_title)

                binding = SignatureListDialogBinding.inflate(context.layoutInflater)
                binding.recyclerView.layoutManager = LinearLayoutManager(context)
                adapter = SignatureListAdapter { signer ->
                    CertificateDetailDialogFragment.show(signer, this@SignatureListDialogFragment)
                }
                binding.recyclerView.adapter = adapter
                adapter.replace(args.signers)
                setView(binding.root)
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()

    @Parcelize
    class Args(val signers: List<ApkSignerInfo>) : ParcelableArgs

    companion object {
        fun show(signers: List<ApkSignerInfo>, fragment: Fragment) {
            SignatureListDialogFragment().putArgs(Args(signers)).show(fragment)
        }
    }
}
