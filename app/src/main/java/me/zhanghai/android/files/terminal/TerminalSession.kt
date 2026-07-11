/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.Bundle
import android.os.RemoteException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import me.zhanghai.android.files.provider.remote.ParcelableException
import me.zhanghai.android.files.terminal.remote.ShizukuTerminalServiceLauncher
import me.zhanghai.android.files.util.RemoteCallback
import java.io.IOException

/**
 * App-side handle to one PTY session living in the Shizuku process. Bytes written via [write] are
 * forwarded to the child's stdin; the child's stdout/stderr surface as a [Flow] of [ByteArray]
 * chunks via [output]. Call [close] to end the session and [waitForExit] for the exit status.
 *
 * The class is the boundary between the UI (which sees a byte stream + control calls) and the
 * remote binder; it hides all the IPC plumbing from [TerminalView] and [TerminalActivity].
 */
class TerminalSession internal constructor(
    private val remotePty: IRemotePty,
    private val outputChannel: Channel<ByteArray>,
    internal val outputCallback: RemoteCallback,
    val initialRows: Int,
    val initialCols: Int
) {
    /** Stream of raw output bytes from the child process, for the UI to render. */
    val output: Flow<ByteArray> = outputChannel.receiveAsFlow()

    /**
     * Lets [TerminalService.createSession] build the output callback before the remote PTY binder
     * exists (the callback is passed to createPty and must outlive the call). Not for app use.
     */
    class Builder(private val rows: Int, private val cols: Int) {
        private val outputChannel = Channel<ByteArray>(Channel.BUFFERED)

        internal val outputCallback = RemoteCallback { bundle: Bundle ->
            val chunk = bundle.getByteArray(TerminalConfig.OUTPUT_KEY)
            if (chunk != null && chunk.isNotEmpty()) {
                // trySend because the channel could be closed during teardown.
                outputChannel.trySend(chunk)
            }
        }

        fun build(remotePty: IRemotePty): TerminalSession =
            TerminalSession(remotePty, outputChannel, outputCallback, rows, cols)
    }

    /** Sends bytes (user input) to the child. Throws on IPC failure. */
    @Throws(IOException::class)
    fun write(data: ByteArray) {
        val exception = ParcelableException()
        val written = try {
            remotePty.write(data, exception)
        } catch (e: RemoteException) {
            throw IOException(e)
        }
        exception.value?.let { throw it }
        if (written < 0) throw IOException("write failed")
    }

    /** Resizes the terminal window. Safe to call repeatedly as the UI rotates/resizes. */
    @Throws(IOException::class)
    fun resize(rows: Int, cols: Int) {
        val exception = ParcelableException()
        try {
            remotePty.setSize(rows, cols, exception)
        } catch (e: RemoteException) {
            throw IOException(e)
        }
        exception.value?.let { throw it }
    }

    /** Ends the session. Idempotent. */
    fun close() {
        val exception = ParcelableException()
        try {
            remotePty.close(exception)
        } catch (e: RemoteException) {
            // Teardown — don't surface to caller, just log.
        }
        outputChannel.close()
    }

    /**
     * Blocks until the child exits; returns the raw waitpid status (decode WIFEXITED/WIFSIGNALED).
     * Should be called after [close]; -1 on failure.
     */
    fun waitForExit(): Int {
        val exception = ParcelableException()
        return try {
            remotePty.waitForExit(exception)
        } catch (e: RemoteException) {
            -1
        }
    }
}
