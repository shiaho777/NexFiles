/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/**
 * End-to-end APK signer: takes an input APK (possibly already signed) and produces a freshly
 * signed output APK with the schemes enabled in [config].
 *
 * The pipeline is:
 *  1. **Strip** — rewrite the input as a clean unsigned ZIP (drops the old v1 entries and the old
 *     APK Signing Block). This gives us a known-good baseline with correct CD/EOCD offsets.
 *  2. **v1 (optional)** — add MANIFEST.MF / CERT.SF / CERT.RSA entries into the ZIP.
 *  3. **v2/v3 (optional)** — compute the four-chunk digest, generate the signing block, and splice
 *     it between the entries and the central directory, patching the EOCD offset.
 *
 * We write to a temp file and atomically rename at the end, so a failed sign never produces a
 * half-written [outputApk].
 *
 * Usage: `ApkSigner.sign(File("app.apk"), File("app-signed.apk"), config)`
 */
object ApkSigner {

    private const val CREATED_BY = "NexFiles"

    /**
     * Signs [inputApk] and writes the result to [outputApk].
     *
     * @throws IOException if the input is not a valid APK or signing fails.
     */
    fun sign(inputApk: File, outputApk: File, config: ApkSignerConfig) {
        require(outputApk != inputApk) { "Output must differ from input" }

        // Stage to a temp file in the same directory so the final rename is atomic on the same
        // filesystem.
        val tempFile = File(outputApk.parentFile, outputApk.name + ".signing.tmp")
        try {
            // Step 1: strip to a clean unsigned ZIP.
            ApkSignatureStripper.strip(inputApk, tempFile)

            // Step 2: add v1 entries if requested.
            if (config.v1Enabled) {
                addV1Signature(tempFile, config)
            }

            // Step 3: insert v2/v3 signing block if either is requested.
            if (config.v2Enabled || config.v3Enabled) {
                insertV2V3SigningBlock(tempFile, config)
            }

            // Atomic-ish swap into the final name.
            if (outputApk.exists()) outputApk.delete()
            if (!tempFile.renameTo(outputApk)) {
                throw IOException("Failed to move signed APK to ${outputApk.name}")
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw if (e is IOException) e else IOException("Signing failed", e)
        }
    }

    /**
     * Adds v1 (JAR) signature entries to [apkFile] in place. We rewrite the ZIP to append the
     * three META-INF files produced by [ApkV1Signer].
     */
    private fun addV1Signature(apkFile: File, config: ApkSignerConfig) {
        val v1Entries = ZipFile(apkFile).use { zip ->
            ApkV1Signer.generateV1Entries(
                zip, config.privateKey, config.certificates.first(), "$CREATED_BY (${config.keyAlias})"
            )
        }
        val tempFile = File(apkFile.parentFile, apkFile.name + ".v1.tmp")
        try {
            ZipFile(apkFile).use { zip ->
                ZipArchiveOutputStream(
                    BufferedOutputStream(FileOutputStream(tempFile))
                ).use { out ->
                    // Copy all existing entries, skipping any stale v1 files (there shouldn't be
                    // any after strip, but we're defensive).
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || ApkSignatureStripper.isV1SignatureEntry(entry.name)) continue
                        val copyEntry = ZipArchiveEntry(entry.name)
                        copyEntry.method = entry.method
                        copyEntry.size = entry.size
                        copyEntry.crc = entry.crc
                        copyEntry.time = entry.time
                        out.putArchiveEntry(copyEntry)
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeArchiveEntry()
                    }
                    // Append the v1 signature entries. CERT.RSA must be STORED (uncompressed) to
                    // match what the v1 verifier expects; MANIFEST.MF and CERT.SF can be DEFLATEd.
                    for (v1 in v1Entries) {
                        val sigEntry = ZipArchiveEntry(v1.name)
                        sigEntry.method = if (v1.name.endsWith(".RSA"))
                            ZipArchiveEntry.STORED else ZipArchiveEntry.DEFLATED
                        sigEntry.size = v1.data.size.toLong()
                        sigEntry.crc = java.util.zip.CRC32().apply { update(v1.data) }.value
                        sigEntry.time = System.currentTimeMillis()
                        out.putArchiveEntry(sigEntry)
                        out.write(v1.data)
                        out.closeArchiveEntry()
                    }
                }
            }
            apkFile.delete()
            if (!tempFile.renameTo(apkFile)) {
                throw IOException("Failed to apply v1 signature")
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw if (e is IOException) e else IOException("v1 signing failed", e)
        }
    }

    /**
     * Computes the v2/v3 signing block for [apkFile] and splices it between the entries and the
     * central directory. The EOCD's central-directory offset is updated to point past the block.
     *
     * We do this with a single temp-file copy + a targeted patch, avoiding a full second rewrite.
     */
    private fun insertV2V3SigningBlock(apkFile: File, config: ApkSignerConfig) {
        RandomAccessFile(apkFile, "rw").use { raf ->
            val fileLength = raf.length()
            val eocdOffset = ZipBlockUtils.findEndOfCentralDirectory(raf)
            val cdOffset = ZipBlockUtils.readCentralDirOffset(raf, eocdOffset)
            // Sanity: there must be no signing block already (strip removed it).
            val existingBlock = ZipBlockUtils.findSigningBlock(raf, cdOffset)
            if (existingBlock != null) {
                throw IOException("APK already has a signing block; strip first")
            }

            // Generate the signing block bytes. The digest covers [0, cdOffset) for entries,
            // then CD, then EOCD — exactly the ranges the verifier will hash after insertion.
            val signingBlock = ApkV2V3Signer.generateSigningBlock(raf, eocdOffset, cdOffset, config)
            val blockSize = signingBlock.size.toLong()

            // We need to make room for the block at cdOffset by shifting the CD + EOCD right.
            // Do it in chunks from the end to avoid overwriting data we still need.
            val cdAndEocdSize = fileLength - cdOffset
            raf.shiftBytesRight(cdOffset, cdAndEocdSize, blockSize)

            // Write the signing block into the now-vacated gap.
            raf.seek(cdOffset)
            raf.write(signingBlock)

            // Patch the EOCD's central-directory offset: CD moved right by blockSize.
            val newCdOffset = cdOffset + blockSize
            ZipBlockUtils.writeCentralDirOffset(raf, eocdOffset + blockSize, newCdOffset)
        }
    }

    /**
     * Moves [length] bytes starting at [sourceOffset] to [sourceOffset + delta], working from the
     * end of the range backward so the source and destination regions (which overlap) don't
     * clobber each other. Used to open a gap for inserting the signing block.
     */
    private fun RandomAccessFile.shiftBytesRight(
        sourceOffset: Long, length: Long, delta: Long
    ) {
        if (delta <= 0 || length <= 0) return
        val chunkSize = 1024 * 64
        val buffer = ByteArray(chunkSize)
        var remaining = length
        var readOffset = sourceOffset + length
        var writeOffset = sourceOffset + length + delta
        while (remaining > 0) {
            val toMove = minOf(chunkSize.toLong(), remaining).toInt()
            readOffset -= toMove
            writeOffset -= toMove
            seek(readOffset)
            readFully(buffer, 0, toMove)
            seek(writeOffset)
            write(buffer, 0, toMove)
            remaining -= toMove
        }
    }
}
