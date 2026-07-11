/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Parsed signature information of an APK, covering all three APK Signature Schemes.
 *
 * V1 (jarsigner) is detected by the presence of META-INF/*.SF | *.RSA | *.DSA | *.EC entries.
 * V2 / V3 / V3.1 live in the APK Signing Block immediately before the ZIP central directory,
 * keyed by their respective magic IDs (see [ApkSigningScheme]).
 *
 * The certificate bytes are retained as raw DER so the detail dialog can re-derive every
 * fingerprint (SHA-1 / SHA-256 / SHA-512) and every X.509 field without re-opening the file.
 */
@Parcelize
data class ApkSignatureInfo(
    /** Set of schemes actually found in this APK, in scheme-id order. */
    val schemes: List<ApkSigningScheme>,
    /** One signer block per scheme that has multiple signers; flattened across schemes. */
    val signers: List<ApkSignerInfo>,
    /** True if META-INF contains a v1 signature file (.SF + .{RSA|DSA|EC}). */
    val hasV1ManifestSignature: Boolean,
    /** Raw bytes of the central-directory-anchored comment, if any. */
    val apkSigningBlockPresent: Boolean
) : Parcelable

@Parcelize
data class ApkSignerInfo(
    /** Which scheme produced this signer. */
    val scheme: ApkSigningScheme,
    /** The certificate chain in DER form, signer certificate first. */
    val certificateDerList: List<ByteArray>,
    /** SHA-256 of the signer certificate (the first one), for the list header. */
    val signerCertSha256: String,
    /** Optional digests the signer attests to, scheme-specific. */
    val digestAlgorithms: List<String> = emptyList()
) : Parcelable {
    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + signerCertSha256.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApkSignerInfo) return false
        return scheme == other.scheme && signerCertSha256 == other.signerCertSha256 &&
            certificateDerList.size == other.certificateDerList.size &&
            certificateDerList.zip(other.certificateDerList).all { it.first.contentEquals(it.second) }
    }
}

/**
 * APK Signature Scheme identifiers, matching the block IDs used in the APK Signing Block.
 *
 * @see ApkSigningBlockReader for the on-disk binary layout.
 */
@Parcelize
enum class ApkSigningScheme(val blockId: Int) : Parcelable {
    V1_JAR(0),
    V2_ANDROID(0x7109871A),
    V3_ANDROID(0xF05368C0),
    V31_ANDROID(0x1B93AD61),
    V4_INCREMENTAL(0x42726577);

    val isV2Plus: Boolean get() = this != V1_JAR
}
