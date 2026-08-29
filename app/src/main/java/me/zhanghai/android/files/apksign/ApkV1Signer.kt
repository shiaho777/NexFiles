/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * Produces a v1 (JAR) signature by generating the three META-INF files Android's v1 verifier
 * expects: MANIFEST.MF (per-entry SHA-256 digests), CERT.SF (the manifest digested again), and
 * CERT.RSA (a PKCS#7/CMS SignedData over CERT.SF).
 *
 * We read the entries of the *output* APK (which at this point contains the real content but no
 * signing block) and write the three signature entries back into it. The output APK must be a
 * writable ZIP we can reopen.
 *
 * The signature uses RSASSA-PKCS1-v1_5 with SHA-256 — the standard v1 algorithm. We deliberately
 * skip META-INF entries (both the signature files themselves and any existing ones from a prior
 * sign), matching what jarsigner/apksigner do.
 */
internal object ApkV1Signer {

    private const val META_INF = "META-INF/"
    private const val MANIFEST_NAME = "META-INF/MANIFEST.MF"
    private const val CERT_SF_NAME = "META-INF/CERT.SF"
    private const val CERT_RSA_NAME = "META-INF/CERT.RSA"
    private const val MANIFEST_VERSION_ATTR = "Manifest-Version"
    private const val CREATED_BY_ATTR = "Created-By"

    /**
     * Generates the three v1 signature entries and returns them so the caller can add them to the
     * output ZIP. Returns null if the APK has no entries to sign.
     */
    fun generateV1Entries(
        zipFile: ZipFile,
        privateKey: PrivateKey,
        certificate: X509Certificate,
        createdBy: String
    ): List<V1Entry> {
        // --- 1. Build MANIFEST.MF ---
        val manifest = StringBuilder()
        manifest.append("$MANIFEST_VERSION_ATTR: 1.0\r\n")
        manifest.append("$CREATED_BY_ATTR: $createdBy\r\n")
        manifest.append("\r\n")

        val sha256 = MessageDigest.getInstance("SHA-256")
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            // Skip META-INF manifest/signature files; they're either the ones we're generating or
            // leftovers from a prior sign that the caller should have already stripped.
            if (name.startsWith(META_INF, ignoreCase = true) &&
                (name.uppercase().endsWith(".MF") ||
                    name.uppercase().endsWith(".SF") ||
                    name.uppercase().endsWith(".RSA") ||
                    name.uppercase().endsWith(".DSA") ||
                    name.uppercase().endsWith(".EC"))
            ) {
                continue
            }
            val digest = sha256.digest(zipFile.getInputStream(entry).readBytes())
            val base64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
            manifest.append("Name: $name\r\n")
            manifest.append("SHA-256-Digest: $base64\r\n")
            manifest.append("\r\n")
        }

        val manifestBytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)

        // --- 2. Build CERT.SF (the manifest, re-digested) ---
        val sf = StringBuilder()
        sf.append("$MANIFEST_VERSION_ATTR: 1.0\r\n")
        sf.append("$CREATED_BY_ATTR: $createdBy\r\n")
        // Digest of the entire MANIFEST.MF
        val manifestMainDigest = sha256.digest(manifestBytes)
        sf.append("SHA-256-Digest-Manifest: " +
            android.util.Base64.encodeToString(manifestMainDigest, android.util.Base64.NO_WRAP) + "\r\n")
        sf.append("\r\n")

        // Per-entry digests in CERT.SF: digest of each "Name: ...\r\n...Digest: ...\r\n\r\n" block
        // from MANIFEST.MF. We re-scan the manifest to extract each entry section.
        for (entrySection in extractEntrySections(manifestBytes)) {
            val sectionDigest = sha256.digest(entrySection)
            sf.append("Name: " + extractSectionName(entrySection) + "\r\n")
            sf.append("SHA-256-Digest: " +
                android.util.Base64.encodeToString(sectionDigest, android.util.Base64.NO_WRAP) + "\r\n")
            sf.append("\r\n")
        }

        val sfBytes = sf.toString().toByteArray(StandardCharsets.UTF_8)

        // --- 3. Build CERT.RSA (PKCS#7/CMS SignedData over CERT.SF) ---
        val rsaBytes = createPkcs7Signature(sfBytes, privateKey, certificate)

        return listOf(
            V1Entry(MANIFEST_NAME, manifestBytes),
            V1Entry(CERT_SF_NAME, sfBytes),
            V1Entry(CERT_RSA_NAME, rsaBytes)
        )
    }

    /**
     * Creates a PKCS#7 CMS SignedData structure over [data] using BouncyCastle, signed with
     * SHA-256withRSA (PKCS#1 v1.5) — the v1 scheme's canonical algorithm.
     */
    private fun createPkcs7Signature(
        data: ByteArray,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): ByteArray {
        val signerInfoGenerator = JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        ).build(
            JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(privateKey),
            certificate
        )
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfoGenerator)
        generator.addCertificates(JcaCertStore(listOf(certificate)))
        val signedData = generator.generate(CMSProcessableByteArray(data), false)
        return signedData.encoded
    }

    /**
     * Splits MANIFEST.MF bytes into per-entry sections (everything between the main attributes
     * and EOF, split on blank lines that separate entries).
     */
    private fun extractEntrySections(manifestBytes: ByteArray): List<ByteArray> {
        val sections = mutableListOf<ByteArray>()
        val text = String(manifestBytes, StandardCharsets.UTF_8)
        // After the main attribute block (terminated by a blank line), each entry is a block
        // ending with \r\n\r\n.
        val blankLine = "\r\n\r\n"
        val firstBlank = text.indexOf(blankLine)
        if (firstBlank < 0) return sections
        val body = text.substring(firstBlank + blankLine.length)
        val parts = body.split(blankLine)
        for (part in parts) {
            if (part.isBlank()) continue
            // Re-add the trailing CRLFCRLF so the digest matches what jarsigner produces.
            sections.add((part + "\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        }
        return sections
    }

    /** Extracts the value of the "Name:" attribute from a manifest entry section. */
    private fun extractSectionName(section: ByteArray): String {
        val text = String(section, StandardCharsets.UTF_8)
        for (line in text.split("\r\n")) {
            if (line.startsWith("Name: ")) {
                return line.substring("Name: ".length)
            }
        }
        return ""
    }

    /** A v1 signature entry: name + raw bytes to write into the output ZIP. */
    data class V1Entry(val name: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is V1Entry) return false
            return name == other.name && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = 31 * name.hashCode() + data.contentHashCode()
    }
}
