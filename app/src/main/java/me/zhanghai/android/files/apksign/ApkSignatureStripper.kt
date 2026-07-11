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
 * Removes all signatures from an APK, producing a clean unsigned copy:
 *  - v1 (JAR): META-INF/*.SF, *.RSA, *.DSA, *.EC and MANIFEST.MF entries are dropped.
 *  - v2/v3: the APK Signing Block is excised and the EOCD central-directory offset is corrected.
 *
 * We rewrite the ZIP rather than patching in place, because removing v1 entries changes the
 * central directory and local-header layout, and commons-compress gives us a correct writer that
 * recomputes all offsets. The rewrite also naturally drops the signing block (it lives in the gap
 * between entries and the CD, which a fresh write never creates).
 *
 * The output is a valid unsigned ZIP that can then be re-signed or modified freely.
 */
internal object ApkSignatureStripper {

    /**
     * Strips all signatures from [inputApk], writing the unsigned result to [outputApk].
     * The output file is overwritten if it exists.
     */
    fun strip(inputApk: File, outputApk: File) {
        ZipFile(inputApk).use { zip ->
            ZipArchiveOutputStream(
                BufferedOutputStream(FileOutputStream(outputApk))
            ).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (isV1SignatureEntry(entry.name)) continue
                    val copyEntry = ZipArchiveEntry(entry.name)
                    copyEntry.method = entry.method
                    // Store uncompressed-data length and CRC so the writer produces a valid entry.
                    copyEntry.size = entry.size
                    copyEntry.crc = entry.crc
                    copyEntry.time = entry.time
                    out.putArchiveEntry(copyEntry)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeArchiveEntry()
                }
            }
        }
        // The rewrite above produced a clean ZIP with no signing block and no META-INF signature
        // entries. A defensive pass verifies no APK Signing Block snuck in (it shouldn't, since
        // commons-compress never emits one), which lets us fail fast on unexpected input.
        RandomAccessFile(outputApk, "r").use { raf ->
            val eocdOffset = ZipBlockUtils.findEndOfCentralDirectory(raf)
            val cdOffset = ZipBlockUtils.readCentralDirOffset(raf, eocdOffset)
            val signingBlock = ZipBlockUtils.findSigningBlock(raf, cdOffset)
            if (signingBlock != null) {
                throw IOException("Stripping failed: signing block still present after rewrite")
            }
        }
    }

    /**
     * True if [name] is a v1 signature file or the JAR manifest (which only exists to back the
     * v1 signature). META-INF/ directories that aren't signature-related (e.g. services/) are kept.
     */
    fun isV1SignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
            upper.endsWith(".EC") || upper == "META-INF/MANIFEST.MF"
    }
}
