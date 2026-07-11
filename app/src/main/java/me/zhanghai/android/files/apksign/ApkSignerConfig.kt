/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Configuration for signing an APK. Mirrors `ApkSigner.SignerConfig` from the SDK's apksig library
 * but uses only standard JCA types so it runs on Android.
 *
 * @property keyAlias human-readable name shown in the result dialog (e.g. "nexfiles").
 * @property privateKey the signing key.
 * @property certificates the certificate chain, signer cert first.
 * @property v1Enabled whether to produce a v1 (JAR) signature.
 * @property v2Enabled whether to produce a v2 signature.
 * @property v3Enabled whether to produce a v3 signature.
 */
data class ApkSignerConfig(
    val keyAlias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
    val v1Enabled: Boolean = true,
    val v2Enabled: Boolean = true,
    val v3Enabled: Boolean = true
) {
    init {
        require(certificates.isNotEmpty()) { "At least one certificate is required" }
    }
}
