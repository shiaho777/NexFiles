/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.security.MessageDigest

fun ByteArray.sha1Digest(): ByteArray = MessageDigest.getInstance("SHA-1").digest(this)

fun ByteArray.sha256Digest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

fun ByteArray.sha512Digest(): ByteArray = MessageDigest.getInstance("SHA-512").digest(this)

fun ByteArray.sha1Hex(): String = sha1Digest().toHexString()

fun ByteArray.sha256Hex(): String = sha256Digest().toHexString()

fun ByteArray.sha512Hex(): String = sha512Digest().toHexString()

fun ByteArray.toHexString(): String {
    val chars = CharArray(2 * size)
    for (index in indices) {
        val byte = this[index]
        chars[2 * index] = ((byte.toInt() ushr 4) and 0xF).toHexChar()
        chars[2 * index + 1] = (byte.toInt() and 0xF).toHexChar()
    }
    return String(chars)
}

private fun Int.toHexChar(): Char = if (this >= 10) 'A' + (this - 10) else '0' + this
