/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksign

import java.io.IOException
import java.io.RandomAccessFile

/**
 * ZIP structural primitives shared by signing and stripping: locating the End-of-Central-Directory
 * record, reading/writing the central-directory offset it carries, and locating the APK Signing
 * Block that (optionally) precedes the central directory.
 *
 * All offsets are absolute file offsets. We work on [RandomAccessFile] rather than a channel so
 * the signing path can memory-map and write at arbitrary positions in one pass.
 *
 * The APK on-disk layout is:
 * ```
 *  [ZIP entries (local headers + data)]
 *  [APK Signing Block]  <-- optional, immediately before the central directory
 *  [ZIP Central Directory]
 *  [End of Central Directory]  <-- offset_to_central_dir points at the CD start
 * ```
 *
 * @see <a href="https://source.android.com/security/apksigning/v2#apk-signing-block">APK Signing Block</a>
 */
internal object ZipBlockUtils {

    const val EOCD_SIGNATURE = 0x06054B50
    const val CD_SIGNATURE = 0x02014B50
    const val EOCD_MAX_COMMENT = 0xFFFF
    private const val EOCD_MIN_SIZE = 22
    private const val CD_OFFSET_FIELD_OFFSET = 16 // within the EOCD record

    const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204B5041L // "APK Sig "
    const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206B636F6C42L // "Block 42"
    private const val APK_SIG_BLOCK_MIN_SIZE = 32L
    private const val APK_SIG_BLOCK_FOOTER_SIZE = 24L
    private const val APK_SIG_BLOCK_HEADER_SIZE = 8L

    /**
     * Scans backwards from the end of [file] to find the EOCD signature. The EOCD may be followed
     * by up to 65535 bytes of archive comment, so the search window covers that range.
     */
    fun findEndOfCentralDirectory(file: RandomAccessFile): Long {
        val fileLength = file.length()
        val maxEocdScan = minOf(fileLength, (EOCD_MAX_COMMENT + EOCD_MIN_SIZE).toLong())
        val scanStart = fileLength - maxEocdScan
        var offset = fileLength - EOCD_MIN_SIZE
        while (offset >= scanStart) {
            file.seek(offset)
            if (file.readInt() == EOCD_SIGNATURE) {
                return offset
            }
            offset--
        }
        throw IOException("Not an APK: End-of-Central-Directory record not found")
    }

    /**
     * Reads the central-directory start offset stored in the EOCD record at [eocdOffset].
     * This is the value we must patch whenever we insert or remove bytes before the CD.
     */
    fun readCentralDirOffset(file: RandomAccessFile, eocdOffset: Long): Long {
        file.seek(eocdOffset + CD_OFFSET_FIELD_OFFSET)
        return file.readInt().toLong() and 0xFFFFFFFFL
    }

    /**
     * Writes a new central-directory start offset into the EOCD at [eocdOffset]. Used after
     * inserting/removing the APK Signing Block to keep the ZIP consistent.
     */
    fun writeCentralDirOffset(file: RandomAccessFile, eocdOffset: Long, cdOffset: Long) {
        file.seek(eocdOffset + CD_OFFSET_FIELD_OFFSET)
        file.writeInt(cdOffset.toInt())
    }

    /**
     * Locates the APK Signing Block that sits immediately before the central directory. Returns
     * the [SigningBlockLocation] or null if no valid block is present (e.g. a v1-only or unsigned
     * APK).
     *
     * The block uses bookend sizes: a uint64 at the start and again at the end (minus the 16-byte
     * magic), with the magic "APK Sig Block 42" as a footer. We validate via the footer and derive
     * the start from the leading size.
     */
    fun findSigningBlock(file: RandomAccessFile, cdOffset: Long): SigningBlockLocation? {
        if (cdOffset < APK_SIG_BLOCK_MIN_SIZE) return null
        // Footer: size_of_block uint64 | magic uint128 (two uint64 halves).
        file.seek(cdOffset - APK_SIG_BLOCK_FOOTER_SIZE)
        val sizeOfBlock = file.readLong()
        val magicLo = file.readLong()
        val magicHi = file.readLong()
        // Java's readLong is big-endian; the on-disk format is little-endian.
        if (java.lang.Long.reverseBytes(magicLo) != APK_SIG_BLOCK_MAGIC_LO ||
            java.lang.Long.reverseBytes(magicHi) != APK_SIG_BLOCK_MAGIC_HI
        ) {
            return null
        }
        val leSizeOfBlock = java.lang.Long.reverseBytes(sizeOfBlock)
        val blockStart = cdOffset - leSizeOfBlock - APK_SIG_BLOCK_HEADER_SIZE
        if (blockStart < 0) return null
        val blockSize = leSizeOfBlock
        return SigningBlockLocation(blockStart, blockSize, cdOffset)
    }

    /**
     * Physical location of the APK Signing Block within the file.
     *
     * @property startOffset absolute offset where the leading size_of_block uint64 begins.
     * @property size the size value stored in the block (excludes the leading uint64 itself but
     *           includes the footer). Total bytes occupied = size + 8.
     * @property centralDirOffset absolute offset of the central directory (i.e. the byte
     *           immediately after the block).
     */
    data class SigningBlockLocation(
        val startOffset: Long,
        val size: Long,
        val centralDirOffset: Long
    ) {
        /** Total number of bytes the block occupies on disk, including the leading size field. */
        val totalSize: Long get() = size + APK_SIG_BLOCK_HEADER_SIZE
    }
}
