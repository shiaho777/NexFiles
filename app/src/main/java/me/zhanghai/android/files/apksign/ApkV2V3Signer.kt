/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * Implements APK Signature Scheme v2 and v3, producing the APK Signing Block that gets inserted
 * between the ZIP entries and the central directory.
 *
 * The scheme signs three "chunks" of the file, computed over the exact byte ranges that the
 * verifier hashes (matching apksig's `computeContentDigests`):
 *
 *  1. Contents of ZIP entries [0, cdOffset) — local file headers + file data.
 *  2. The ZIP Central Directory [cdOffset, eocdOffset).
 *  3. The ZIP End-of-Central-Directory [eocdOffset, EOF).
 *
 * The APK Signing Block itself is **not** covered by the digest — it sits between chunks 1 and 2
 * and is skipped entirely (no placeholder chunk). This matches the v2/v3 spec and the apksig
 * library behavior.
 *
 * Note on the EOCD: the spec says the EOCD's CD-offset field (at byte 16) must be treated as
 * containing the offset of the APK Signing Block. At the point we compute the digest (before
 * inserting the block), that field already equals `cdOffset` — which is exactly the signing
 * block's insertion point. So no patching is needed during signing. The verifier, working on the
 * already-signed APK, patches it from `cdOffset + blockSize` back to `cdOffset`.
 *
 * Each chunk is split into 1 MiB blocks, each SHA-256'd. The concatenation of those digests plus
 * a chunk-count uint32 is itself SHA-256'd to yield the "top-level" digest that the signature
 * covers. The three top-level digests are concatenated to form the content digest stored in the
 * signer's signed-data. This chunking lets the verifier use mmap without loading the whole file.
 *
 * The binary format is heavily length-prefixed (each field is preceded by a uint32 length,
 * except block pairs which use uint64). We build it bottom-up: signer → signed-data → block-pair
 * → signing block.
 *
 * v3 is structurally identical to v2 except: (a) the signer record carries minSdk/maxSdk, (b) the
 * block pair uses ID 0xF05368C0 instead of 0x7109871A, (c) there's no additional-attributes
 * payload needed for a single-key (non-rotated) v3 signature. We generate both pairs when both
 * are enabled, which is what apksigner does by default.
 *
 * @see <a href="https://source.android.com/security/apksigning/v2">APK Signature Scheme v2</a>
 * @see <a href="https://source.android.com/security/apksigning/v3">APK Signature Scheme v3</a>
 */
internal object ApkV2V3Signer {

    private const val CHUNK_SIZE_BYTES = 1024 * 1024 // 1 MiB
    private const val SIGNATURE_ALGORITHM_ID = 0x0101 // RSASSA-PSS with SHA-256
    private const val SIGNATURE_ALGORITHM_NAME = "SHA256withRSA/PSS"
    // PSS parameters matching apksig's defaults: SHA-256 for both the hash and the MGF1 hash,
    // salt length 32 bytes (matches the hash output).
    private const val PSS_SALT_LENGTH = 32

    // Block IDs for the APK Signing Block pairs.
    private const val V2_BLOCK_ID = 0x7109871A.toInt()
    private const val V3_BLOCK_ID = 0xF05368C0.toInt()

    private val APK_SIGNING_BLOCK_MAGIC =
        byteArrayOf( // "APK Sig Block 42"
            0x41, 0x50, 0x4B, 0x20, 0x53, 0x69, 0x67, 0x20,
            0x42, 0x6C, 0x6F, 0x63, 0x6B, 0x20, 0x34, 0x32
        )

    /**
     * Generates the complete APK Signing Block bytes for the given [file], including both v2 and v3
     * pairs as enabled by [config]. The caller is responsible for inserting these bytes before the
     * central directory and patching the EOCD's central-dir offset accordingly (done by [ApkSigner]).
     *
     * @param file the input APK, already stripped of any prior signing block (we need clean CD/EOCD
     *        offsets to compute the correct chunk boundaries).
     * @param eocdOffset absolute offset of the EOCD record.
     * @param cdOffset absolute offset of the central directory.
     * @param config signing key + scheme toggles.
     * @return the raw bytes of the signing block, ready to splice in.
     */
    fun generateSigningBlock(
        file: RandomAccessFile,
        eocdOffset: Long,
        cdOffset: Long,
        config: ApkSignerConfig
    ): ByteArray {
        // Compute the top-level digest over the four canonical chunks. This digest is identical
        // for v2 and v3 (they cover the same byte ranges), so we compute it once.
        val topLevelDigest = computeApkDigest(file, eocdOffset, cdOffset)

        val pairs = ByteBufferListBuilder()
        if (config.v2Enabled) {
            pairs.raw(buildBlockPair(V2_BLOCK_ID, config, topLevelDigest, isV3 = false))
        }
        if (config.v3Enabled) {
            pairs.raw(buildBlockPair(V3_BLOCK_ID, config, topLevelDigest, isV3 = true))
        }

        return assembleSigningBlock(pairs.toByteArray())
    }

    // ---------------------------------------------------------------------------------------
    //  Chunk digestion
    //
    //  The v2/v3 scheme digests the APK in three contiguous byte ranges (see the spec's
    //  "APK integrity protected by APK Signature Scheme v2"):
    //    1. Contents of ZIP entries  [0, cdOffset)
    //    2. ZIP Central Directory  [cdOffset, eocdOffset)
    //    3. ZIP End-of-Central-Directory  [eocdOffset, EOF)
    //
    //  The APK Signing Block (inserted between ranges 1 and 2) is NOT digested.
    //
    //  Each range is split into 1 MiB "chunks." The per-chunk digest is:
    //    SHA-256( 0xa5 | uint32_le(chunk_length) | chunk_bytes )
    //
    //  The top-level digest over a range is:
    //    SHA-256( 0x5a | uint32_le(chunk_count) | concat(per-chunk digests) )
    //
    //  The top-level digests of all three ranges are concatenated, and *that* concatenation is the
    //  value stored in the "digests" field of signed-data, and also what gets signed.
    // ---------------------------------------------------------------------------------------

    /**
     * Computes the top-level APK digest by digesting each of the three canonical ranges and
     * concatenating their top-level digests. The result is what the signature covers.
     *
     * The three ranges (per the v2/v3 spec and the apksig library's `computeContentDigests`) are:
     *  1. ZIP entries [0, cdOffset)  — local file headers + file data
     *  2. ZIP Central Directory [cdOffset, eocdOffset)
     *  3. ZIP End-of-Central-Directory [eocdOffset, EOF)
     *
     * The APK Signing Block itself is **not** digested — it sits between chunks 1 and 2 and is
     * skipped. There is no "placeholder chunk" for it; that was an error in an earlier version.
     *
     * Note: the EOCD's central-directory-offset field (at offset 16 within the EOCD) is treated
     * as-is. At the point this method is called (before the signing block is inserted), that
     * field already equals `cdOffset`, which is exactly the value the spec requires — the offset
     * of the APK Signing Block (= the insertion point). No patching is needed.
     */
    private fun computeApkDigest(
        file: RandomAccessFile,
        eocdOffset: Long,
        cdOffset: Long
    ): ByteArray {
        val allDigests = ByteBufferListBuilder()
        // Chunk 1: ZIP entries [0, cdOffset).
        allDigests.raw(digestRange(file, 0, cdOffset))
        // Chunk 2: Central Directory [cdOffset, eocdOffset).
        allDigests.raw(digestRange(file, cdOffset, eocdOffset))
        // Chunk 3: EOCD [eocdOffset, EOF).
        allDigests.raw(digestRange(file, eocdOffset, file.length()))
        return allDigests.toByteArray()
    }

    /**
     * Digests byte range [start, end) of [file]: splits it into 1 MiB chunks, computes the
     * per-chunk digest of each, then combines them into the range's top-level digest.
     */
    private fun digestRange(file: RandomAccessFile, start: Long, end: Long): ByteArray {
        val rangeSize = end - start
        val sha = MessageDigest.getInstance("SHA-256")
        // Top-level: 0x5a | uint32_le(chunk_count) | concat(chunk_digests)
        sha.update(0x5a.toByte())
        if (rangeSize <= 0) {
            sha.update(BytesLe.uint32(0)) // chunk_count = 0
            return sha.digest()
        }
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        var offset = start
        var chunkCount = 0
        // First pass: count chunks (we need the count up front for the top-level digest).
        var remaining = rangeSize
        while (remaining > 0) {
            chunkCount++
            remaining -= minOf(CHUNK_SIZE_BYTES.toLong(), remaining)
        }
        sha.update(BytesLe.uint32(chunkCount.toLong()))
        // Second pass: compute each chunk digest and feed it into the top-level hash.
        remaining = rangeSize
        offset = start
        while (remaining > 0) {
            val chunkLen = minOf(CHUNK_SIZE_BYTES.toLong(), remaining).toInt()
            file.seek(offset)
            file.readFully(buffer, 0, chunkLen)
            val chunkSha = MessageDigest.getInstance("SHA-256")
            chunkSha.update(0xA5.toByte())
            chunkSha.update(BytesLe.uint32(chunkLen.toLong()))
            chunkSha.update(buffer, 0, chunkLen)
            sha.update(chunkSha.digest())
            offset += chunkLen
            remaining -= chunkLen
        }
        return sha.digest()
    }

    // ---------------------------------------------------------------------------------------
    //  Block pair / signer record assembly
    // ---------------------------------------------------------------------------------------

    /**
     * Builds one length-prefixed id-value pair for the signing block.
     *
     * Per the spec, each pair is: `length uint64 | id uint32 | value`
     * where `length` = size of (id + value) = 4 + value.size, and does **not** include the
     * `length` field itself.
     *
     * The `value` is a length-prefixed-sequence of length-prefixed signers:
     *   `uint32(seq_len) | uint32(signer_len) | signer`
     */
    private fun buildBlockPair(
        blockId: Int,
        config: ApkSignerConfig,
        topLevelDigest: ByteArray,
        isV3: Boolean
    ): ByteArray {
        val signer = buildSigner(config, topLevelDigest, isV3)
        // value = length-prefixed-sequence of length-prefixed signers:
        //   uint32(total_bytes) | uint32(signer_len) | signer
        val signersSeqInner = ByteBufferListBuilder().apply {
            addLengthPrefixed(signer)
        }.toByteArray()
        val value = ByteBufferListBuilder().apply {
            addLengthPrefixed(signersSeqInner)
        }.toByteArray()
        // pair = length uint64 | id uint32 | value
        // length = sizeof(id) + sizeof(value) = 4 + value.size, excluding the length field itself.
        val remainderLen = (4 + value.size).toLong()
        val pairBuilder = ByteBufferListBuilder()
        pairBuilder.raw(BytesLe.uint64(remainderLen))
        pairBuilder.raw(BytesLe.uint32(blockId.toLong()))
        pairBuilder.raw(value)
        return pairBuilder.toByteArray()
    }

    /**
     * Builds a single signer record (length-prefixed at the caller level).
     *
     * v2 signer = length-prefixed(signed-data) | signatures | length-prefixed(public-key)
     * v3 signer = length-prefixed(signed-data) | minSdk uint32 | maxSdk uint32 |
     *             signatures | length-prefixed(public-key)
     *
     * signed-data = digests | certificates | additional-attributes
     *   digests             = length-prefixed-seq of length-prefixed(alg-id | length-prefixed(digest))
     *   certificates        = length-prefixed-seq of length-prefixed(DER cert)
     *   additional-attributes = length-prefixed-seq (empty for single key)
     */
    private fun buildSigner(
        config: ApkSignerConfig,
        topLevelDigest: ByteArray,
        isV3: Boolean
    ): ByteArray {
        // --- digests: length-prefixed sequence of length-prefixed digest entries ---
        // Each digest entry: length-prefixed( algorithm-id uint32 | length-prefixed(digest) )
        // The sequence wraps all entries with an outer uint32 length.
        val digestEntry = ByteBufferListBuilder().apply {
            raw(BytesLe.uint32(SIGNATURE_ALGORITHM_ID.toLong()))
            addLengthPrefixed(topLevelDigest)
        }.toByteArray()
        // Wrap the single entry in a length-prefixed sequence:
        //   uint32(entry_len) | entry   (the outer length = entry length, single entry)
        val digestsInner = ByteBufferListBuilder().apply {
            addLengthPrefixed(digestEntry)
        }.toByteArray()
        val digests = ByteBufferListBuilder().apply {
            addLengthPrefixed(digestsInner)
        }.toByteArray()

        // --- certificates: length-prefixed sequence of length-prefixed DER certs ---
        val certInner = ByteBufferListBuilder().apply {
            for (cert in config.certificates) {
                addLengthPrefixed(cert.encoded)
            }
        }.toByteArray()
        val certificates = ByteBufferListBuilder().apply {
            addLengthPrefixed(certInner)
        }.toByteArray()

        // additional-attributes: empty length-prefixed sequence (uint32(0)) for a single key.
        val additionalAttributes = ByteBufferListBuilder().apply {
            addLengthPrefixed(ByteArray(0))
        }.toByteArray()

        // signed-data = digests | certificates | additional-attributes
        val signedData = ByteBufferListBuilder().apply {
            raw(digests)
            raw(certificates)
            raw(additionalAttributes)
        }.toByteArray()

        // --- signatures: length-prefixed sequence of length-prefixed signatures over signed-data ---
        // Each signature: length-prefixed( algorithm-id uint32 | length-prefixed(sig bytes) )
        val signedDataBytes = signedData
        val signatureBytes = signData(config.privateKey, signedDataBytes)
        val sigInner = ByteBufferListBuilder().apply {
            raw(BytesLe.uint32(SIGNATURE_ALGORITHM_ID.toLong()))
            addLengthPrefixed(signatureBytes)
        }.toByteArray()
        val signaturesInner = ByteBufferListBuilder().apply {
            addLengthPrefixed(sigInner)
        }.toByteArray()
        val signatures = ByteBufferListBuilder().apply {
            addLengthPrefixed(signaturesInner)
        }.toByteArray()

        // --- public key (DER SubjectPublicKeyInfo) ---
        val publicKey = config.certificates.first().publicKey.encoded

        // Assemble the signer record. Field order differs between v2 and v3:
        //   v2: signed-data | signatures | public-key
        //   v3: signed-data | minSdk | maxSdk | signatures | public-key
        val signerBuilder = ByteBufferListBuilder()
        signerBuilder.addLengthPrefixed(signedData)
        if (isV3) {
            // v3 inserts min/max SDK between signed-data and signatures. We use 0 (minSdk =
            // Android 1) / 0x7FFFFFFF (maxSdk = any) so the signature is accepted on every release.
            signerBuilder.raw(BytesLe.uint32(0))
            signerBuilder.raw(BytesLe.uint32(0x7FFFFFFFL))
        }
        signerBuilder.raw(signatures)
        signerBuilder.addLengthPrefixed(publicKey)
        return signerBuilder.toByteArray()
    }

    /**
     * Signs [signedData] using RSASSA-PSS (SHA-256, salt=32) with the PSS parameters set explicitly
     * via [java.security.Signature.setParameter], which works on both desktop JVM and Android ART.
     */
    private fun signData(privateKey: PrivateKey, signedData: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM_NAME)
        val pssParams = java.security.spec.PSSParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
            PSS_SALT_LENGTH, 1 /* trailer field */
        )
        signature.setParameter(pssParams)
        signature.initSign(privateKey)
        signature.update(signedData)
        return signature.sign()
    }

    // ---------------------------------------------------------------------------------------
    //  Signing block framing
    // ---------------------------------------------------------------------------------------

    /**
     * Wraps the concatenated id-value [pairs] in the APK Signing Block framing:
     * `size_of_block uint64 | pairs | size_of_block uint64 | magic`.
     */
    private fun assembleSigningBlock(pairs: ByteArray): ByteArray {
        // size_of_block counts everything after the leading size_of_block: pairs + footer
        // (trailing size_of_block uint64 + 16-byte magic).
        val footerSize = 8 + APK_SIGNING_BLOCK_MAGIC.size
        val sizeOfBlock = pairs.size + footerSize
        val block = ByteArray(8 + sizeOfBlock)
        val buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(sizeOfBlock.toLong())
        buf.put(pairs)
        buf.putLong(sizeOfBlock.toLong())
        buf.put(APK_SIGNING_BLOCK_MAGIC)
        return block
    }

    // ---------------------------------------------------------------------------------------
    //  Little-endian byte helpers and a length-prefixed buffer builder.
    // ---------------------------------------------------------------------------------------

    private object BytesLe {
        fun uint32(value: Long): ByteArray {
            val v = value.toInt()
            return byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte()
            )
        }
        fun uint64(value: Long): ByteArray {
            return byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 32) and 0xFF).toByte(),
                ((value ushr 40) and 0xFF).toByte(),
                ((value ushr 48) and 0xFF).toByte(),
                ((value ushr 56) and 0xFF).toByte()
            )
        }
    }

    /**
     * A growable byte buffer with convenience methods for length-prefixing sub-payloads with a
     * little-endian uint32, matching the APK Signing Block's pervasive length-prefixed encoding.
     */
    private class ByteBufferListBuilder {
        private val out = java.io.ByteArrayOutputStream()

        fun raw(bytes: ByteArray) {
            out.write(bytes)
        }

        /** Writes a uint32 length prefix followed by [bytes]. */
        fun addLengthPrefixed(bytes: ByteArray) {
            out.write(BytesLe.uint32(bytes.size.toLong()))
            out.write(bytes)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
