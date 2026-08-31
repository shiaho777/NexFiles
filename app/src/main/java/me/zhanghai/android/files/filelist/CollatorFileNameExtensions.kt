/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.toByteString
import java.text.CollationKey
import java.text.Collator
import kotlin.math.min

private val COLLATION_SENTINEL = byteArrayOf(1, 1, 1)

// Collator.getInstance() clones a full rule table on every call, and loadFileItem() runs it once
// per listed file. Collator is not thread-safe, so reuse one instance per worker thread instead
// of allocating a fresh one for every entry of a directory listing.
private val fileNameCollators = object : ThreadLocal<Collator>() {
    override fun initialValue(): Collator = Collator.getInstance()
}

val fileNameCollator: Collator
    get() = fileNameCollators.get()!!

// @see https://github.com/GNOME/glib/blob/mainline/glib/gunicollate.c
//      g_utf8_collate_key_for_filename()
fun Collator.getCollationKeyForFileName(source: String): CollationKey {
    val result = ByteStringBuilder()
    val suffix = ByteStringBuilder()
    var previousIndex = 0
    var index = 0
    val endIndex = source.length
    while (index < endIndex) {
        when {
            source[index] == '.' -> {
                if (previousIndex != index) {
                    val collationKey = getCollationKey(source.substring(previousIndex, index))
                    result.append(collationKey.toByteArray())
                }
                result.append(COLLATION_SENTINEL).append(1)
                previousIndex = index + 1
            }
            source[index].isAsciiDigit() -> {
                if (previousIndex != index) {
                    val collationKey = getCollationKey(source.substring(previousIndex, index))
                    result.append(collationKey.toByteArray())
                }
                result.append(COLLATION_SENTINEL).append(2)
                previousIndex = index
                var leadingZeros: Int
                var digits: Int
                if (source[index] == '0') {
                    leadingZeros = 1
                    digits = 0
                } else {
                    leadingZeros = 0
                    digits = 1
                }
                while (++index < endIndex) {
                    if (source[index] == '0' && digits == 0) {
                        ++leadingZeros
                    } else if (source[index].isAsciiDigit()) {
                        ++digits
                    } else {
                        if (digits == 0) {
                            ++digits
                            --leadingZeros
                        }
                        break
                    }
                }
                while (digits > 1) {
                    result.append(':'.code.toByte())
                    --digits
                }
                if (leadingZeros > 0) {
                    suffix.append(leadingZeros.toByte())
                    previousIndex += leadingZeros
                }
                result.append(source.substring(previousIndex, index).toByteString())
                previousIndex = index
                --index
            }
            else -> {}
        }
        ++index
    }
    if (previousIndex != index) {
        val collationKey = getCollationKey(source.substring(previousIndex, index))
        result.append(collationKey.toByteArray())
    }
    result.append(suffix.toByteString())
    // One-shot build: move the bytes out without the extra full-array copy toByteString makes.
    return ByteArrayCollationKey(source, result.toBorrowedByteString().borrowBytes())
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

@Parcelize
private class ByteArrayCollationKey(
    @Suppress("CanBeParameter")
    private val source: String,
    private val bytes: ByteArray
) : CollationKey(source), Parcelable {
    override fun compareTo(other: CollationKey): Int {
        other as ByteArrayCollationKey
        return bytes.unsignedCompareTo(other.bytes)
    }

    override fun toByteArray(): ByteArray = bytes.copyOf()

    // Value equality so that FileItem's generated data-class equals (used by DiffUtil's
    // areContentsTheSame) compares collation keys by content instead of identity — a reloaded
    // directory creates fresh key instances for otherwise identical items.
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as ByteArrayCollationKey
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

private fun ByteArray.unsignedCompareTo(other: ByteArray): Int {
    val size = size
    val otherSize = other.size
    for (index in 0..<min(size, otherSize)) {
        val byte = this[index].toInt() and 0xFF
        val otherByte = other[index].toInt() and 0xFF
        if (byte < otherByte) {
            return -1
        } else if (byte > otherByte) {
            return 1
        }
    }
    return size - otherSize
}
