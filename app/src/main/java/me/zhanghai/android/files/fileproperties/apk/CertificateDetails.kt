/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.util.sha1Hex
import me.zhanghai.android.files.util.sha256Hex
import me.zhanghai.android.files.util.sha512Hex
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A rendering-friendly view of a single X.509 certificate, derived from its DER bytes.
 *
 * We pre-compute every field the detail dialog needs (issuer / subject DN, serial, validity,
 * public-key algorithm and size, signature algorithm, and all three SHA fingerprints) so the
 * UI layer never has to touch [java.security.cert] directly. The DER bytes are retained so the
 * caller can additionally export them or re-verify them.
 */
@Parcelize
data class CertificateDetails(
    val version: Int,
    val serialNumber: String,
    val signatureAlgorithm: String,
    val issuerName: String,
    val subjectName: String,
    val notBefore: String,
    val notAfter: String,
    val publicKeyAlgorithm: String,
    val publicKeyBits: Int,
    val publicKeyEncoded: String,
    val sha1Fingerprint: String,
    val sha256Fingerprint: String,
    val sha512Fingerprint: String,
    val derBytes: ByteArray
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CertificateDetails) return false
        return sha256Fingerprint == other.sha256Fingerprint
    }

    override fun hashCode(): Int = sha256Fingerprint.hashCode()

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

        private val CERTIFICATE_FACTORY = CertificateFactory.getInstance("X.509")

        fun fromDer(derBytes: ByteArray): CertificateDetails {
            val cert = CERTIFICATE_FACTORY.generateCertificate(ByteArrayInputStream(derBytes))
                as X509Certificate
            return fromCertificate(cert, derBytes)
        }

        fun fromCertificate(cert: X509Certificate, derBytes: ByteArray): CertificateDetails {
            val publicKey = cert.publicKey
            val keyBits = estimateKeyBits(publicKey.algorithm, publicKey)
            return CertificateDetails(
                version = cert.version,
                serialNumber = formatSerial(cert.serialNumber),
                signatureAlgorithm = cert.sigAlgName,
                issuerName = cert.issuerX500Principal.name,
                subjectName = cert.subjectX500Principal.name,
                notBefore = DATE_FORMAT.format(cert.notBefore),
                notAfter = DATE_FORMAT.format(cert.notAfter),
                publicKeyAlgorithm = publicKey.algorithm,
                publicKeyBits = keyBits,
                publicKeyEncoded = publicKey.encoded.toHexColons(),
                sha1Fingerprint = derBytes.sha1Hex(),
                sha256Fingerprint = derBytes.sha256Hex(),
                sha512Fingerprint = derBytes.sha512Hex(),
                derBytes = derBytes
            )
        }

        private fun estimateKeyBits(algorithm: String, key: java.security.PublicKey): Int {
            return when (algorithm) {
                "RSA" -> {
                    val modulus = (key as java.security.interfaces.RSAPublicKey).modulus
                    modulus.bitLength()
                }
                "EC" -> (key as java.security.interfaces.ECPublicKey).params.order.bitLength()
                "DSA" -> (key as java.security.interfaces.DSAPublicKey).params.p.bitLength()
                else -> key.encoded.size * 8
            }
        }

        private fun formatSerial(serial: BigInteger): String {
            // Show as a colon-separated hex string, like OpenSSL does, falling back to decimal
            // for tiny serials that fit comfortably.
            val hex = serial.toString(16).uppercase(Locale.US)
            return if (serial.bitLength() <= 32) {
                serial.toString()
            } else {
                hex.chunked(2).joinToString(":")
            }
        }

        private fun ByteArray.toHexColons(): String =
            joinToString(":") { "%02X".format(it) }
    }
}
