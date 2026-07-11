/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.SignatureItemBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

/**
 * Adapter for the list of signers in [SignatureListDialogFragment].
 *
 * Each row shows the signing scheme (V1/V2/V3) as a badge, the certificate subject extracted
 * from its X.509 DN, and the SHA-256 fingerprint of the signer certificate. Tapping a row
 * opens the full certificate detail dialog.
 */
class SignatureListAdapter(
    private val onClick: (ApkSignerInfo) -> Unit
) : SimpleAdapter<ApkSignerInfo, SignatureListAdapter.ViewHolder>() {

    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long =
        getItem(position).signerCertSha256.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            SignatureItemBinding.inflate(parent.context.layoutInflater, parent, false)
        ) { onClick(getItem(it.bindingAdapterPosition)) }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val signer = getItem(position)
        binding.schemeBadgeText.text = signer.scheme.shortLabel
        binding.subjectText.text = signer.certificateSubjectSummary()
        binding.sha256Text.text = signer.signerCertSha256
    }

    class ViewHolder(
        val binding: SignatureItemBinding,
        onClick: (ViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onClick(this) }
        }
    }

    companion object {
        /** Short scheme labels shown on the badge, e.g. "V2", "V3.1". */
        private val ApkSigningScheme.shortLabel: String
            get() = when (this) {
                ApkSigningScheme.V1_JAR -> "V1"
                ApkSigningScheme.V2_ANDROID -> "V2"
                ApkSigningScheme.V3_ANDROID -> "V3"
                ApkSigningScheme.V31_ANDROID -> "V3.1"
                ApkSigningScheme.V4_INCREMENTAL -> "V4"
            }

        /** Derive a human-readable subject from the signer certificate without full parsing. */
        private fun ApkSignerInfo.certificateSubjectSummary(): String {
            val der = certificateDerList.firstOrNull() ?: return "(no certificate)"
            return try {
                val details = CertificateDetails.fromDer(der)
                extractCommonName(details.subjectName) ?: details.subjectName
            } catch (e: Exception) {
                "(unparseable certificate)"
            }
        }

        private fun extractCommonName(dn: String): String? {
            // RFC 2253 DNs look like "CN=Foo, O=Bar"; extract the CN value.
            val cnKey = "CN="
            val idx = dn.indexOf(cnKey, ignoreCase = true)
            if (idx < 0) return null
            val start = idx + cnKey.length
            val end = dn.indexOf(',', start)
            return if (end < 0) dn.substring(start) else dn.substring(start, end)
        }
    }
}
