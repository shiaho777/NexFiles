/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.util.sha256Hex

/**
 * Low-level reader for the APK Signing Block and v1 META-INF signature entries.
 *
 * The APK Signing Block sits immediately before the ZIP central directory:
 *
 * ```
 *  [content of ZIP entries]
 *  [APK Signing Block]            <-- size_of_block uint64 | id-value pairs | size_of_block uint64 | magic uint128
 *  [ZIP Central Directory]
 *  [ZIP End of Central Directory] <-- offset_to_central_dir points here
 * ```
 *
 * Each id-value pair is `length uint32 | id uint32 | value bytes[]`.
 * We walk the block and pick out the IDs declared in [ApkSigningScheme].
 *
 * This is the on-disk-truth path: it does not depend on [android.content.pm.PackageManager],
 * so it works identically across all API levels and reveals the full chain, not just the
 * "current" certificate that PackageManager returns for backwards compatibility.
 *
 * The reader operates on a [SeekableByteChannel], so it works for any path the project's
 * filesystem providers can open (local files, SAF documents, archives, etc.).
 *
 * @see <a href="https://source.android.com/security/apksigning/v2">APK Signature Scheme v2</a>
 */
object ApkSigningBlockReader {

    private const val EOCD_SIGNATURE = 0x06054B50
    private const val EOCD_MAX_COMMENT = 0xFFFF
    private const val CD_SIGNATURE = 0x02014B50
    private const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204B5041L // "APK Sig "
    private const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206B636F6C42L // "Block 42"
    private const val APK_SIG_BLOCK_MIN_SIZE = 32L

    /**
     * Parse every signature scheme present in [channel]. The channel is consumed and left
     * positioned at an unspecified offset; the caller is responsible for closing it.
     *
     * @param filePathForV1 optional backing file path used only to read the v1 PKCS#7 block
     *        through [ZipFile]; null when the APK has no v1 signature or no on-disk path.
     * @throws IOException if the file is not a valid APK or the signing block is malformed.
     */
    fun read(channel: SeekableByteChannel, filePathForV1: String? = null): ApkSignatureInfo {
        channel.use {
            val fileSize = it.size()
            val eocdOffset = findEndOfCentralDirectory(it, fileSize)
            val cdOffset = readEocdCentralDirOffset(it, eocdOffset)
            val signingBlock = readSigningBlock(it, cdOffset)
            val v1Entries = findV1SignatureEntries(it, cdOffset, eocdOffset)
            val hasV1 = v1Entries.sf != null && v1Entries.sig != null

            val schemes = mutableListOf<ApkSigningScheme>()
            val signers = mutableListOf<ApkSignerInfo>()

            if (hasV1) {
                schemes += ApkSigningScheme.V1_JAR
                signers += readV1Signers(filePathForV1, v1Entries)
            }

            if (signingBlock != null) {
                for (scheme in listOf(
                    ApkSigningScheme.V2_ANDROID,
                    ApkSigningScheme.V3_ANDROID,
                    ApkSigningScheme.V31_ANDROID
                )) {
                    val pair = signingBlock.pairs[scheme.blockId]
                    if (pair != null) {
                        schemes += scheme
                        signers += readV2PlusSigners(pair.value, scheme)
                    }
                }
            }

            return ApkSignatureInfo(
                schemes = schemes,
                signers = signers,
                hasV1ManifestSignature = hasV1,
                apkSigningBlockPresent = signingBlock != null
            )
        }
    }

    // -----------------------------------------------------------------------------------
    //  Channel-reading primitives: little-endian reads at absolute offsets.
    // -----------------------------------------------------------------------------------

    private fun readUint32Le(channel: SeekableByteChannel, offset: Long): Long {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(offset)
        channel.readFully(buf)
        return buf.getInt(0).toLong() and 0xFFFFFFFFL
    }

    private fun readUint64Le(channel: SeekableByteChannel, offset: Long): Long {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(offset)
        channel.readFully(buf)
        return buf.getLong(0)
    }

    private fun readBytes(channel: SeekableByteChannel, offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        channel.position(offset)
        channel.readFully(buf)
        return buf.array()
    }

    private fun SeekableByteChannel.readFully(buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val read = read(buf)
            if (read < 0) throw IOException("Unexpected end of APK while reading signing block")
        }
    }

    // -----------------------------------------------------------------------------------
    //  Step 1: locate End-of-Central-Directory record.
    // -----------------------------------------------------------------------------------

    private fun findEndOfCentralDirectory(channel: SeekableByteChannel, fileLength: Long): Long {
        val maxEocdScan = minOf(fileLength, (EOCD_MAX_COMMENT + 22).toLong())
        val scanStart = fileLength - maxEocdScan
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        var offset = fileLength - 22
        while (offset >= scanStart) {
            buf.clear()
            channel.position(offset)
            channel.readFully(buf)
            if (buf.getInt(0) == EOCD_SIGNATURE) {
                return offset
            }
            offset--
        }
        throw IOException("Not an APK: End-of-Central-Directory record not found")
    }

    private fun readEocdCentralDirOffset(channel: SeekableByteChannel, eocdOffset: Long): Long =
        readUint32Le(channel, eocdOffset + 16)

    // -----------------------------------------------------------------------------------
    //  Step 2: read the APK Signing Block (if present) immediately before the CD.
    // -----------------------------------------------------------------------------------

    private data class SigningBlock(val pairs: Map<Int, PairValue>)
    private data class PairValue(val value: ByteArray)

    private fun readSigningBlock(channel: SeekableByteChannel, cdOffset: Long): SigningBlock? {
        if (cdOffset < APK_SIG_BLOCK_MIN_SIZE) return null
        // The 24-byte footer: size_of_block uint64 | magic uint128.
        val sizeOfBlock = readUint64Le(channel, cdOffset - 24)
        val magicLo = readUint64Le(channel, cdOffset - 16)
        val magicHi = readUint64Le(channel, cdOffset - 8)
        if (magicLo != APK_SIG_BLOCK_MAGIC_LO || magicHi != APK_SIG_BLOCK_MAGIC_HI) return null

        val blockStart = cdOffset - sizeOfBlock - 8
        if (blockStart < 0) return null

        // The leading size_of_block uint64 is skipped; pairs run from blockStart+8 up to the
        // 24-byte footer that ends at cdOffset.
        val pairs = HashMap<Int, PairValue>()
        val pairsEnd = cdOffset - 24
        var cursor = blockStart + 8
        while (cursor + 12 <= pairsEnd) {
            val length = readUint32Le(channel, cursor)
            if (length < 4 || cursor + 8 + length > pairsEnd) break
            val id = readUint32Le(channel, cursor + 4).toInt()
            val valueLength = (length - 4).toInt()
            val value = readBytes(channel, cursor + 8, valueLength)
            // Duplicate IDs are permitted (multiple signers of the same scheme); the first
            // non-empty block already contains all signers of that scheme, so we keep it.
            pairs.putIfAbsent(id, PairValue(value))
            cursor += 8 + length
        }
        return SigningBlock(pairs)
    }

    // -----------------------------------------------------------------------------------
    //  Step 3: walk the central directory looking for v1 META-INF signature entries.
    // -----------------------------------------------------------------------------------

    private data class V1Entries(val sf: String?, val sig: String?)

    private fun findV1SignatureEntries(
        channel: SeekableByteChannel,
        cdOffset: Long,
        eocdOffset: Long
    ): V1Entries {
        // Each central-directory header is at least 46 bytes; the entry name lives at offset 46.
        // We only need the first signature file (and its associated .SF) to extract a chain.
        var sf: String? = null
        var sig: String? = null
        var cursor = cdOffset
        while (cursor + 46 <= eocdOffset) {
            val signature = readUint32Le(channel, cursor).toInt()
            if (signature != CD_SIGNATURE) break
            val nameLen = readUint32Le(channel, cursor + 28).toInt() and 0xFFFF
            val extraLen = readUint32Le(channel, cursor + 30).toInt() and 0xFFFF
            val commentLen = readUint32Le(channel, cursor + 32).toInt() and 0xFFFF
            val nameBytes = readBytes(channel, cursor + 46, nameLen)
            val name = String(nameBytes, Charsets.UTF_8)
            val upper = name.uppercase()
            if (upper.startsWith("META-INF/")) {
                when {
                    upper.endsWith(".SF") && sf == null -> sf = name
                    upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") -> {
                        if (sig == null) sig = name
                    }
                }
            }
            cursor += 46 + nameLen + extraLen + commentLen
        }
        return V1Entries(sf, sig)
    }

    private fun readV1Signers(filePath: String?, entries: V1Entries): List<ApkSignerInfo> {
        // The v1 PKCS#7 block lives inside the .RSA/.DSA/.EC entry; the certificate inside it
        // is extracted with the JDK CertificateFactory (which dispatches to BouncyCastle).
        val sigName = entries.sig ?: return emptyList()
        val path = filePath ?: return emptyList()
        return try {
            ZipFile(path).use { zip ->
                val entry = zip.getEntry(sigName) ?: return emptyList()
                zip.getInputStream(entry).use { input ->
                    val cf = CertificateFactory.getInstance("X.509")
                    val certs = cf.generateCertificates(input)
                        .filterIsInstance<X509Certificate>()
                    if (certs.isEmpty()) emptyList()
                    else listOf(certs.toSignerInfo(ApkSigningScheme.V1_JAR))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -----------------------------------------------------------------------------------
    //  Step 4: decode v2/v3 signers from the binary block.
    //  See https://source.android.com/security/apksigning/v2#signature-scheme-block.
    // -----------------------------------------------------------------------------------

    private fun readV2PlusSigners(blockValue: ByteArray, scheme: ApkSigningScheme): List<ApkSignerInfo> {
        // length-prefixed sequence of length-prefixed signers.
        return try {
            val buf = ByteBuffer.wrap(blockValue).order(ByteOrder.LITTLE_ENDIAN)
            val sequence = buf.sliceLengthPrefixed()
            val signers = mutableListOf<ApkSignerInfo>()
            val digestAlgorithms = mutableSetOf<String>()
            while (sequence.hasRemaining()) {
                val signer = sequence.sliceLengthPrefixed()
                // signed-data: digests | certificates | additional-attributes (each length-prefixed)
                val signedData = signer.sliceLengthPrefixed()
                val digests = signedData.sliceLengthPrefixed()
                while (digests.hasRemaining()) {
                    val digest = digests.sliceLengthPrefixed()
                    if (digest.remaining() >= 4) {
                        val algId = digest.int
                        val algName = v2PlusDigestName(algId)
                        if (algName != null) digestAlgorithms += algName
                        digest.position(digest.limit())
                    }
                }
                val certificates = signedData.sliceLengthPrefixed()
                val certDerList = mutableListOf<ByteArray>()
                while (certificates.hasRemaining()) {
                    val cert = certificates.sliceLengthPrefixed()
                    val der = ByteArray(cert.remaining())
                    cert.get(der)
                    certDerList += der
                }
                if (certDerList.isNotEmpty()) {
                    val signerCertSha256 = certDerList.first().sha256Hex()
                    signers += ApkSignerInfo(
                        scheme = scheme,
                        certificateDerList = certDerList.toList(),
                        signerCertSha256 = signerCertSha256,
                        digestAlgorithms = digestAlgorithms.toList()
                    )
                }
            }
            signers
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun ByteBuffer.sliceLengthPrefixed(): ByteBuffer {
        require(remaining() >= 4) { "Truncated length-prefixed field" }
        val length = int and 0x7FFFFFFF
        require(length <= remaining()) { "Declared length $length exceeds buffer" }
        val slice = slice().order(order())
        slice.limit(length)
        position(position() + length)
        return slice
    }

    // Digest algorithm IDs as defined in APK Signature Scheme v2/v3.
    private fun v2PlusDigestName(algorithmId: Int): String? = when (algorithmId) {
        0x0101, 0x0201, 0x0301, 0x0421 -> "SHA-256"
        0x0102, 0x0202, 0x0302, 0x0422 -> "SHA-512"
        else -> null
    }

    // -----------------------------------------------------------------------------------
    //  Shared: turn an X.509 certificate list into an [ApkSignerInfo].
    // -----------------------------------------------------------------------------------

    private fun List<X509Certificate>.toSignerInfo(
        scheme: ApkSigningScheme,
        digestAlgorithms: List<String> = emptyList()
    ): ApkSignerInfo {
        val derList = map { it.encoded }
        val signerCertSha256 = derList.first().sha256Hex()
        return ApkSignerInfo(
            scheme = scheme,
            certificateDerList = derList,
            signerCertSha256 = signerCertSha256,
            digestAlgorithms = digestAlgorithms
        )
    }
}
