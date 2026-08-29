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

internal object ApkSignatureStripper {
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
                    copyEntry.size = entry.size
                    copyEntry.crc = entry.crc
                    copyEntry.time = entry.time
                    out.putArchiveEntry(copyEntry)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeArchiveEntry()
                }
            }
        }
        RandomAccessFile(outputApk, "r").use { raf ->
            val eocdOffset = ZipBlockUtils.findEndOfCentralDirectory(raf)
            val cdOffset = ZipBlockUtils.readCentralDirOffset(raf, eocdOffset)
            val signingBlock = ZipBlockUtils.findSigningBlock(raf, cdOffset)
            if (signingBlock != null) {
                throw IOException("Stripping failed: signing block still present after rewrite")
            }
        }
    }

    fun isV1SignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
            upper.endsWith(".EC") || upper == "META-INF/MANIFEST.MF"
    }
}
