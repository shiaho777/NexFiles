/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

/**
 * One row of the hex display: a 16-byte slice of the file starting at [offset], pre-formatted
 * into the three display columns (offset, hex bytes, ASCII) so the adapter can render it as a
 * single monospace string without per-bind formatting.
 *
 * The formatted string looks like:
 * ```
 * 00000000  4D 5A 90 00 03 00 00 00  04 00 00 00 FF FF 00 00  |MZ..............|
 * ```
 *
 * [lineIndex] is the absolute line number in the file (= offset / 16), used as a stable RecyclerView
 * item ID. [globalOffset] is the file offset of the first byte in this line.
 */
data class HexLine(
    val lineIndex: Long,
    val globalOffset: Long,
    /** Exactly 16 bytes; the last line of the file may be padded with zeros past [validByteCount]. */
    val bytes: ByteArray,
    /** Number of valid bytes in [bytes] (16 for all lines except possibly the last). */
    val validByteCount: Int
) {
    /** The pre-formatted display text for this line. */
    val displayText: String by lazy { formatLine() }

    private fun formatLine(): String {
        val sb = StringBuilder(80)
        // Offset column: 8-digit hex.
        sb.append(String.format("%08X", globalOffset))
        sb.append("  ")
        // Hex column: 16 bytes as 2-digit hex, with a double space after the 8th byte.
        for (i in 0 until 16) {
            if (i < validByteCount) {
                sb.append(String.format("%02X", bytes[i].toInt() and 0xFF))
            } else {
                sb.append("  ")
            }
            sb.append(if (i == 7) "  " else " ")
        }
        sb.append(' ')
        // ASCII column: printable chars, dots for non-printable.
        for (i in 0 until validByteCount) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(if (b in 32..126) b.toChar() else '.')
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexLine) return false
        return lineIndex == other.lineIndex && globalOffset == other.globalOffset &&
            validByteCount == other.validByteCount && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = lineIndex.hashCode()
        result = 31 * result + globalOffset.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + validByteCount
        return result
    }
}
