/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.EMPTY
import me.zhanghai.android.files.provider.common.asFuture
import me.zhanghai.android.files.provider.common.map
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.findCauseByClass
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteFileAccessor
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

class FileByteChannel(
    private val file: RemoteFile,
    isAppend: Boolean
) : AbstractFileByteChannel(isAppend) {
    // Writes are pipelined: a WRITE request is left in flight while the caller prepares the next
    // chunk, so throughput is no longer capped at one round-trip per write. The window bounds how
    // much data can be unacknowledged at once; SFTP servers commonly allow ~2 MB in flight and
    // OpenSSH's own client uses a similar depth. Responses are drained in request order.
    private var pendingWrites = ArrayDeque<Future<Response>>()
    private var nextWritePosition = 0L

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        try {
            RemoteFileAccessor.asyncRead(file, position, size)
        } catch (e: IOException) {
            throw e.maybeToSpecificException()
        }
            .asFuture()
            .map(
                { response ->
                    val dataLength: Int
                    when (response.type) {
                        PacketType.STATUS -> {
                            response.ensureStatusIs(Response.StatusCode.EOF)
                            return@map ByteBuffer::class.EMPTY
                        }
                        PacketType.DATA -> {
                            dataLength = response.readUInt32AsInt()
                        }
                        else -> throw SFTPException("Unexpected packet type ${response.type}")
                    }
                    if (dataLength == 0) {
                        return@map ByteBuffer::class.EMPTY
                    }
                    val length = dataLength.coerceAtMost(size)
                    ByteBuffer.wrap(response.array(), response.rpos(), length)
                }, { e ->
                    ((e as? ExecutionException)?.cause as? IOException)?.maybeToSpecificException()
                        ?.let { ExecutionException(it) } ?: e
                }
            )

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        // I don't think we are using native or read-only ByteBuffer, so just call array() here.
        val offset = source.arrayOffset() + source.position()
        val length = source.remaining()
        try {
            awaitPendingWrites()
            pendingWrites += RemoteFileAccessor
                .asyncWrite(file, position, source.array(), offset, length)
                .asFuture()
        } catch (e: IOException) {
            throw e.maybeToSpecificException()
        }
        source.position(source.limit())
    }

    @Throws(IOException::class)
    override fun onAppend(source: ByteBuffer) {
        // Appends share the same pipelined path; the base class would re-query the size for every
        // chunk, so track the running position ourselves instead.
        onWrite(nextWritePosition, source)
        nextWritePosition += source.remaining()
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        try {
            awaitPendingWrites()
            file.setLength(size)
        } catch (e: IOException) {
            throw e.maybeToSpecificException()
        }
    }

    @Throws(IOException::class)
    override fun onSize(): Long =
        try{
            awaitPendingWrites()
            file.length()
        } catch (e: IOException) {
            throw e.maybeToSpecificException()
        }

    @Throws(IOException::class)
    override fun onForce(metaData: Boolean) {
        try {
            awaitPendingWrites()
        } catch (e: IOException) {
            throw e.maybeToSpecificException()
        }
    }

    /**
     * Blocks until the number of in-flight WRITE responses is below [WRITE_PIPELINE_DEPTH],
     * surfacing the first write error. Must be called with the channel's ioLock held (the base
     * class does that for write/truncate/size/close).
     */
    @Throws(IOException::class)
    private fun awaitPendingWrites() {
        while (pendingWrites.size >= WRITE_PIPELINE_DEPTH) {
            checkPendingWrite(pendingWrites.removeFirst())
        }
    }

    /**
     * Waits for all outstanding WRITE responses and rethrows the first error. Called from
     * [onClose] so a failed transfer is never reported as successful.
     */
    @Throws(IOException::class)
    private fun flushPendingWrites() {
        while (pendingWrites.isNotEmpty()) {
            checkPendingWrite(pendingWrites.removeFirst())
        }
    }

    @Throws(IOException::class)
    private fun checkPendingWrite(future: Future<Response>) {
        try {
            future.get().ensureStatusPacketIsOK()
        } catch (e: ExecutionException) {
            val cause = e.cause as? IOException ?: throw e
            throw cause.maybeToSpecificException()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException().apply { initCause(e) }
        }
    }

    private fun IOException.maybeToSpecificException(): IOException =
        when {
            this is SFTPException && statusCode == Response.StatusCode.INVALID_HANDLE -> {
                setClosed()
                AsynchronousCloseException().apply { initCause(this@maybeToSpecificException) }
            }
            findCauseByClass<InterruptedException>() != null -> {
                closeSafe()
                ClosedByInterruptException().apply { initCause(this@maybeToSpecificException) }
            }
            else -> this
        }

    @Throws(IOException::class)
    override fun onClose() {
        try {
            flushPendingWrites()
        } finally {
            try {
                file.close()
            } catch (e: SFTPException) {
                // NO_SUCH_FILE is returned when canceling an in-progress copy to SFTP server.
                if (e.statusCode != Response.StatusCode.NO_SUCH_FILE) {
                    throw e
                }
            }
        }
    }

    companion object {
        private const val WRITE_PIPELINE_DEPTH = 20
    }
}
