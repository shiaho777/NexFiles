/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CertificateDetailDialogBinding
import me.zhanghai.android.files.databinding.FilePropertiesTabItemBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/**
 * Shows every field of a single signer's X.509 certificate: subject, issuer, serial, validity
 * window, public key algorithm and size, signature algorithm, and the SHA-1/256/512
 * fingerprints. Each row is a copyable [FilePropertiesTabItemBinding] so the user can lift any
 * value (notably the fingerprints) straight into a clipboard or comparison tool.
 */
class CertificateDetailDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: CertificateDetailDialogBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val details = CertificateDetails.fromDer(args.signer.certificateDerList.first())
        val scheme = args.signer.scheme
        binding = CertificateDetailDialogBinding.inflate(requireContext().layoutInflater)
        populateRows(details, scheme)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_properties_apk_certificate_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun populateRows(details: CertificateDetails, scheme: ApkSigningScheme) {
        val container = binding.linearLayout
        val inflater = container.context.layoutInflater
        val rows = buildList {
            add(R.string.file_properties_apk_certificate_scheme to scheme.longLabel)
            add(R.string.file_properties_apk_certificate_version to "v${details.version}")
            add(R.string.file_properties_apk_certificate_serial to details.serialNumber)
            add(R.string.file_properties_apk_certificate_signature_algorithm to details.signatureAlgorithm)
            add(R.string.file_properties_apk_certificate_subject to details.subjectName)
            add(R.string.file_properties_apk_certificate_issuer to details.issuerName)
            add(R.string.file_properties_apk_certificate_valid_from to details.notBefore)
            add(R.string.file_properties_apk_certificate_valid_to to details.notAfter)
            add(
                R.string.file_properties_apk_certificate_public_key
                    to getString(
                        R.string.file_properties_apk_certificate_public_key_format,
                        details.publicKeyAlgorithm, details.publicKeyBits
                    )
            )
            add(R.string.file_properties_apk_certificate_sha1 to details.sha1Fingerprint)
            add(R.string.file_properties_apk_certificate_sha256 to details.sha256Fingerprint)
            add(R.string.file_properties_apk_certificate_sha512 to details.sha512Fingerprint)
        }
        for ((labelRes, text) in rows) {
            val itemBinding = FilePropertiesTabItemBinding.inflate(
                inflater, container, true
            )
            itemBinding.textInputLayout.hint = getString(labelRes)
            itemBinding.textInputLayout.setDropDown(false)
            itemBinding.text.setText(text)
            itemBinding.text.setTextIsSelectable(true)
        }
    }

    @Parcelize
    class Args(val signer: ApkSignerInfo) : ParcelableArgs

    companion object {
        fun show(signer: ApkSignerInfo, fragment: Fragment) {
            CertificateDetailDialogFragment().putArgs(Args(signer)).show(fragment)
        }
    }
}

private val ApkSigningScheme.longLabel: String
    get() = when (this) {
        ApkSigningScheme.V1_JAR -> "V1 (JAR signing)"
        ApkSigningScheme.V2_ANDROID -> "V2 (APK Signature Scheme)"
        ApkSigningScheme.V3_ANDROID -> "V3 (Key Rotation)"
        ApkSigningScheme.V31_ANDROID -> "V3.1 (Lineage Signing)"
        ApkSigningScheme.V4_INCREMENTAL -> "V4 (Incremental)"
    }
