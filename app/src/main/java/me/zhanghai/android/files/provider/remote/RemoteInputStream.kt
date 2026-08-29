/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.remote

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.system.ErrnoException
import android.system.Os
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class RemoteInputStream : InputStream, Parcelable {
    private val localInputStream: InputStream?
    private val remoteInputStream: IRemoteInputStream?

    // -- fd fast path ------------------------------------------------------------------
    // When the remote stream is backed by a real file descriptor (e.g. a FileInputStream over a
    // local file), we pull a duplicate once and read locally through Os.read(), avoiding a
    // Binder round-trip per read. Sequential reads share the fd's offset, which is exactly what
    // an InputStream semantics wants, so no client-side position bookkeeping is needed.
    @Volatile
    private var remoteFd: ParcelFileDescriptor? = null
    @Volatile
    private var fdState: Int = FD_UNKNOWN

    constructor(inputStream: InputStream) {
        localInputStream = inputStream
        remoteInputStream = null
    }

    @Throws(IOException::class)
    override fun read(): Int =
        if (remoteInputStream != null) {
            ensureFdReady()
            if (fdState == FD_READY) {
                val buffer = ByteArray(1)
                val size = readViaFd(buffer, 0, 1)
                if (size <= 0) -1 else buffer[0].toInt() and 0xFF
            } else {
                remoteInputStream.call { exception -> read(exception) }
            }
        } else {
            localInputStream!!.read()
        }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (remoteInputStream != null) {
            ensureFdReady()
            if (fdState == FD_READY) {
                readViaFd(buffer, offset, length)
            } else {
                val remoteBuffer = ByteArray(length)
                val size = remoteInputStream.call { exception -> read2(remoteBuffer, exception) }
                if (size > 0) {
                    remoteBuffer.copyInto(buffer, offset, 0, size)
                }
                size
            }
        } else {
            localInputStream!!.read(buffer, offset, length)
        }

    @Throws(IOException::class)
    private fun readViaFd(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val fd = remoteFd!!.fileDescriptor
        return try {
            // Os.read uses the fd's shared offset; sequential reads advance it naturally.
            Os.read(fd, buffer, offset, length)
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
    }

    @Synchronized
    private fun ensureFdReady() {
        if (fdState != FD_UNKNOWN) return
        val pfd = try {
            remoteInputStream!!.call { exception -> openFd(exception) }
        } catch (e: Exception) {
            null
        }
        if (pfd != null) {
            remoteFd = pfd
            fdState = FD_READY
        } else {
            fdState = FD_UNAVAILABLE
        }
    }

    @Throws(IOException::class)
    override fun skip(size: Long): Long =
        if (remoteInputStream != null) {
            ensureFdReady()
            if (fdState == FD_READY) {
                // For a seekable fd, lseek is a local syscall and far cheaper than an IPC skip.
                val fd = remoteFd!!.fileDescriptor
                try {
                    Os.lseek(fd, size, android.system.OsConstants.SEEK_CUR)
                } catch (e: ErrnoException) {
                    // Non-seekable streams (pipes/sockets) can't lseek; fall back to IPC.
                    remoteInputStream.call { exception -> skip(size, exception) }
                }
            } else {
                remoteInputStream.call { exception -> skip(size, exception) }
            }
        } else {
            localInputStream!!.skip(size)
        }

    @Throws(IOException::class)
    override fun available(): Int =
        if (remoteInputStream != null) {
            remoteInputStream.call { exception -> available(exception) }
        } else {
            localInputStream!!.available()
        }

    @Throws(IOException::class)
    override fun close() {
        if (remoteInputStream != null) {
            remoteInputStream.call { exception -> close(exception) }
            remoteFd?.close()
        } else {
            localInputStream!!.close()
        }
    }

    private class Stub(private val mInputStream: InputStream) : IRemoteInputStream.Stub() {
        override fun read(exception: ParcelableException): Int =
            tryRun(exception) { mInputStream.read() } ?: 0

        override fun read2(buffer: ByteArray, exception: ParcelableException): Int =
            tryRun(exception) { mInputStream.read(buffer) } ?: 0

        override fun skip(size: Long, exception: ParcelableException): Long =
            tryRun(exception) { mInputStream.skip(size) } ?: 0

        override fun available(exception: ParcelableException): Int =
            tryRun(exception) { mInputStream.available() } ?: 0

        override fun close(exception: ParcelableException) {
            tryRun(exception) { mInputStream.close() }
        }

        override fun openFd(exception: ParcelableException): ParcelFileDescriptor? =
            // Only FileInputStream exposes its backing fd via getFD(); for anything else we
            // return null and the client falls back to per-call read2()/skip() IPC.
            tryRun(exception) {
                (mInputStream as? FileInputStream)?.let { stream ->
                    runCatching { ParcelFileDescriptor.dup(stream.fd) }.getOrNull()
                }
            }
    }

    private constructor(source: Parcel) {
        localInputStream = null
        remoteInputStream = IRemoteInputStream.Stub.asInterface(source.readStrongBinder())
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        if (remoteInputStream != null) {
            dest.writeStrongBinder(remoteInputStream.asBinder())
        } else {
            dest.writeStrongBinder(Stub(localInputStream!!).asBinder())
        }
    }

    companion object {
        private const val FD_UNKNOWN = 0
        private const val FD_READY = 1
        private const val FD_UNAVAILABLE = 2

        @JvmField
        val CREATOR = object : Parcelable.Creator<RemoteInputStream> {
            override fun createFromParcel(source: Parcel): RemoteInputStream =
                RemoteInputStream(source)

            override fun newArray(size: Int): Array<RemoteInputStream?> = arrayOfNulls(size)
        }
    }
}

fun InputStream.toRemote(): RemoteInputStream = RemoteInputStream(this)
