/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import java.io.IOException

/**
 * Raised when the terminal service can't be obtained — Shizuku missing, permission denied, or the
 * remote binding failed. The UI maps this to a "set up Shizuku" prompt rather than a raw stack.
 */
class TerminalServiceUnavailableException(message: String) : IOException(message) {
    constructor(cause: Throwable) : this(cause.message ?: cause::class.java.simpleName)
}
