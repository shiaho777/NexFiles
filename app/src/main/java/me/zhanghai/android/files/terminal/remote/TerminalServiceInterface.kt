/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.os.Bundle
import me.zhanghai.android.files.provider.remote.ParcelableException
import me.zhanghai.android.files.provider.remote.ParcelableObject
import me.zhanghai.android.files.terminal.IRemotePty
import me.zhanghai.android.files.terminal.IRemoteTerminalService
import me.zhanghai.android.files.terminal.TerminalConfig
import me.zhanghai.android.files.terminal.TerminalNative
import me.zhanghai.android.files.util.RemoteCallback
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementation of [IRemoteTerminalService] that runs inside the Shizuku-started process (shell
 * uid). Each [createPty] call forks a child via [TerminalNative.createPty] and spins a dedicated
 * thread that pumps master-fd output back through the registered [RemoteCallback].
 *
 * This class is constructed by Shizuku on the remote side (see
 * [ShizukuTerminalServiceInterface]); it must not be instantiated in the app process.
 */
open class TerminalServiceInterface : IRemoteTerminalService.Stub() {

    private val pumpExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "terminal-pump").apply { isDaemon = true }
    }

    override fun createPty(
        config: ParcelableObject,
        outputCallback: RemoteCallback,
        exception: ParcelableException
    ): IRemotePty? {
        val terminalConfig = config.value<TerminalConfig>()
        return try {
            val masterFd = TerminalNative.createPty(
                terminalConfig.argv.toTypedArray(),
                terminalConfig.envp?.toTypedArray(),
                terminalConfig.rows,
                terminalConfig.cols
            )
            val session = PtySession(masterFd, outputCallback)
            pumpExecutor.execute(session.pumpRunnable)
            session
        } catch (e: IOException) {
            exception.value = e
            null
        }
    }

    override fun release(exception: ParcelableException) {
        // Sessions own their own fds and close themselves; nothing to shut down here beyond the
        // pump pool, which we leave running in case the service is re-bound.
    }

    /**
     * A live PTY: holds the master fd, pumps its output to the callback, and exposes write/resize
     * /close/wait. Implements IRemotePty so the app side gets a binder proxy to it.
     */
    private class PtySession(
        private val masterFd: Int,
        private val outputCallback: RemoteCallback
    ) : IRemotePty.Stub() {
        private val closed = AtomicBoolean(false)
        private val outputBuffer = ByteArray(OUTPUT_CHUNK_SIZE)

        // Runs on a pumpExecutor thread: read master fd in a loop, forward each chunk to the
        // callback. Exits when read returns EOF or an error, or when close() flips the flag.
        val pumpRunnable = Runnable {
            try {
                while (!closed.get()) {
                    val n = TerminalNative.read(
                        masterFd, outputBuffer, 0, outputBuffer.size, READ_TIMEOUT_MILLIS.toInt()
                    )
                    if (n < 0) {
                        // read threw — the outer try catches it.
                        break
                    }
                    if (n == 0) {
                        // Timeout with no data; loop and poll again unless we've been closed.
                        continue
                    }
                    val chunk = outputBuffer.copyOf(n)
                    val bundle = Bundle().apply { putByteArray(TerminalConfig.OUTPUT_KEY, chunk) }
                    runCatching { outputCallback.sendResult(bundle) }
                }
            } catch (e: IOException) {
                // EOF or the child closed the slave side — expected on session end, just stop.
            } finally {
                // Make sure the fd is released even if close() was never called.
                if (closed.compareAndSet(false, true)) {
                    runCatching { TerminalNative.close(masterFd) }
                }
            }
        }

        override fun write(data: ByteArray, exception: ParcelableException): Int = try {
            TerminalNative.write(masterFd, data, 0, data.size)
        } catch (e: IOException) {
            exception.value = e
            -1
        }

        override fun setSize(rows: Int, cols: Int, exception: ParcelableException) {
            try {
                TerminalNative.setSize(masterFd, rows, cols)
            } catch (e: IOException) {
                exception.value = e
            }
        }

        override fun close(exception: ParcelableException) {
            if (closed.compareAndSet(false, true)) {
                runCatching { TerminalNative.close(masterFd) }
            }
        }

        override fun waitForExit(exception: ParcelableException): Int = try {
            TerminalNative.waitForExit(masterFd)
        } catch (e: IOException) {
            exception.value = e
            -1
        }
    }

    companion object {
        // 8 KiB matches glibc's default BUFSIZ-ish and keeps IPC round-trips reasonable.
        private const val OUTPUT_CHUNK_SIZE = 8192
        // 100ms is short enough that close() is responsive yet avoids busy-looping on idle PTYs.
        private const val READ_TIMEOUT_MILLIS = 100L
    }
}
