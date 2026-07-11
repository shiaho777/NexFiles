/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import java.io.IOException

/**
 * Thin JNI bridge to the native PTY layer in `terminal.c`. All methods are static and take/return
 * the raw master file descriptor — process lifecycle (the actual fork, exec, waitpid) happens in
 * native code, while Kotlin orchestrates sessions and I/O across the Shizuku process boundary.
 *
 * This class must be loaded only inside the Shizuku-started process (see
 * [me.zhanghai.android.files.terminal.remote.ShizukuTerminalServiceInterface]), because that is
 * the only context where exec of a bundled binary is permitted. Loading it in the app process
 * would succeed but the subsequent exec would hit the W^X restriction.
 */
object TerminalNative {
    init {
        System.loadLibrary("terminal")
    }

    /**
     * Forks a child attached to a new pseudo-terminal and execs `argv[0]` with optional `envp`.
     * Returns the master fd of the PTY on success. Throws [IOException] on fork or allocation
     * failure; exec failure in the child is reported via the exit status of [waitForExit]
     * (126 = generic exec error, 127 = ENOENT).
     */
    @Throws(IOException::class)
    @JvmStatic
    external fun createPty(
        argv: Array<String>,
        envp: Array<String>?,
        rows: Int,
        cols: Int
    ): Int

    /** Writes [length] bytes from [bytes] starting at [offset]; returns bytes written. */
    @Throws(IOException::class)
    @JvmStatic
    external fun write(fd: Int, bytes: ByteArray, offset: Int, length: Int): Int

    /**
     * Reads up to [length] bytes into [buffer] at [offset]. Blocks for up to [timeoutMillis] via
     * poll(); returns 0 on timeout, the byte count on success. EOF/IO failure throws.
     */
    @Throws(IOException::class)
    @JvmStatic
    external fun read(fd: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int

    /** Updates the kernel's view of the terminal size (sends SIGWINCH to the foreground job). */
    @Throws(IOException::class)
    @JvmStatic
    external fun setSize(fd: Int, rows: Int, cols: Int)

    /** Closes the master fd. Never throws — used during teardown. */
    @JvmStatic
    external fun close(fd: Int): Int

    /**
     * Blocks until the most recently-exited child of this process is reaped and returns its raw
     * waitpid status. Kotlin decodes WIFEXITED/WIFSIGNALED from there.
     */
    @Throws(IOException::class)
    @JvmStatic
    external fun waitForExit(fd: Int): Int
}
