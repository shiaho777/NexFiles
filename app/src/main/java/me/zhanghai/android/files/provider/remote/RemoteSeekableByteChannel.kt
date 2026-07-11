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
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.ForceableChannel
import me.zhanghai.android.files.provider.common.dupFileDescriptorOrNull
import me.zhanghai.android.files.provider.common.force
import java.io.IOException
import java.nio.ByteBuffer

class RemoteSeekableByteChannel : ForceableChannel, SeekableByteChannel, Parcelable {
    private val localChannel: SeekableByteChannel?
    private val remoteChannel: IRemoteSeekableByteChannel?

    @Volatile
    private var isRemoteClosed = false

    // -- fd fast path ------------------------------------------------------------------
    // Lazily-fetched duplicate of the remote channel's backing file descriptor. When present,
    // reads and writes go through Os.pread/pwrite with an explicit offset, so the channel's
    // logical position lives entirely on the client and no per-read Binder round-trip happens.
    // `fdState` starts at FD_UNKNOWN and flips to FD_READY (a usable fd was obtained) or
    // FD_UNAVAILABLE (the remote channel has no fd backing; fall back to per-call IPC) on the
    // first operation, whichever it is.
    @Volatile
    private var remoteFd: ParcelFileDescriptor? = null
    @Volatile
    private var fdState: Int = FD_UNKNOWN
    @Volatile
    private var remotePosition: Long = 0L
    @Volatile
    private var remoteSize: Long = -1L

    constructor(channel: SeekableByteChannel) {
        localChannel = channel
        remoteChannel = null
    }

    @Throws(IOException::class)
    override fun read(destination: ByteBuffer): Int =
        if (remoteChannel != null) {
            ensureFdReady()
            if (fdState == FD_READY) readViaFd(destination) else readViaIpc(destination)
        } else {
            localChannel!!.read(destination)
        }

    @Throws(IOException::class)
    private fun readViaFd(destination: ByteBuffer): Int {
        if (!destination.hasRemaining()) return 0
        val position = remotePosition
        // pread reads at an explicit offset without disturbing any shared file offset, so the
        // channel's logical position lives entirely on the client.
        val read = runOsIo { Os.pread(remoteFd!!.fileDescriptor, destination, position) }
        if (read > 0) {
            remotePosition = position + read
        }
        return read
    }

    @Throws(IOException::class)
    private fun readViaIpc(destination: ByteBuffer): Int {
        val destinationBytes = ByteArray(destination.remaining())
        val size = remoteChannel.call { exception -> read(destinationBytes, exception) }
        if (size > 0) {
            destination.put(destinationBytes, 0, size)
        }
        return size
    }

    @Throws(IOException::class)
    override fun write(source: ByteBuffer): Int =
        if (remoteChannel != null) {
            ensureFdReady()
            if (fdState == FD_READY) writeViaFd(source) else writeViaIpc(source)
        } else {
            localChannel!!.write(source)
        }

    @Throws(IOException::class)
    private fun writeViaFd(source: ByteBuffer): Int {
        if (!source.hasRemaining()) return 0
        val position = remotePosition
        val written = runOsIo { Os.pwrite(remoteFd!!.fileDescriptor, source, position) }
        if (written > 0) {
            remotePosition = position + written
            // Growing past the known size invalidates the cached length.
            if (remotePosition > remoteSize) {
                remoteSize = remotePosition
            }
        }
        return written
    }

    @Throws(IOException::class)
    private fun writeViaIpc(source: ByteBuffer): Int {
        val oldPosition = source.position()
        val sourceBytes = ByteArray(source.remaining())
        source.get(sourceBytes)
        source.position(oldPosition)
        val size = remoteChannel.call { exception -> write(sourceBytes, exception) }
        source.position(oldPosition + size)
        return size
    }

    /**
     * Lazily fetches the remote fd on first use. Sets [fdState] to [FD_READY] when the fd fast
     * path is usable, or [FD_UNAVAILABLE] when the remote channel has no fd backing (and the
     * caller must fall back to IPC). Cached so we only ever pay for the openFd() Binder
     * round-trip once per channel.
     */
    @Synchronized
    private fun ensureFdReady() {
        if (fdState != FD_UNKNOWN) return
        val pfd = try {
            remoteChannel.call { exception -> openFd(exception) }
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
    override fun position(): Long =
        if (remoteChannel != null) {
            ensureFdReady()
            if (fdState == FD_READY) remotePosition
            else remoteChannel.call { exception -> position(exception) }
        } else {
            localChannel!!.position()
        }

    @Throws(IOException::class)
    override fun position(newPosition: Long): SeekableByteChannel {
        if (remoteChannel != null) {
            ensureFdReady()
            if (fdState == FD_READY) {
                remotePosition = newPosition
            } else {
                remoteChannel.call { exception -> position2(newPosition, exception) }
            }
        } else {
            localChannel!!.position(newPosition)
        }
        return this
    }

    @Throws(IOException::class)
    override fun size(): Long =
        if (remoteChannel != null) {
            ensureFdReady()
            when {
                fdState == FD_READY && remoteSize >= 0 -> remoteSize
                remoteFd != null -> {
                    // fstat is local to the duplicated fd; cheaper and fresher than a Binder call.
                    val stat = runOsIo { Os.fstat(remoteFd!!.fileDescriptor) }
                    remoteSize = stat.st_size
                    remoteSize
                }
                else -> remoteChannel.call { exception -> size(exception) }
            }
        } else {
            localChannel!!.size()
        }

    @Throws(IOException::class)
    override fun truncate(size: Long): SeekableByteChannel {
        if (remoteChannel != null) {
            ensureFdReady()
            if (remoteFd != null) {
                runOsIo { Os.ftruncate(remoteFd!!.fileDescriptor, size) }
                remoteSize = size
            } else {
                remoteChannel.call { exception -> truncate(size, exception) }
            }
        } else {
            return localChannel!!.truncate(size)
        }
        return this
    }

    @Throws(IOException::class)
    override fun force(metaData: Boolean) {
        if (remoteChannel != null) {
            ensureFdReady()
            if (remoteFd != null) {
                runOsIo { Os.fsync(remoteFd!!.fileDescriptor) }
            } else {
                remoteChannel.call { exception -> force(metaData, exception) }
            }
        } else {
            localChannel!!.force(metaData)
        }
    }

    /**
     * Runs a [Os] syscall and translates [ErrnoException] into [IOException] so callers only
     * have to declare [IOException]. [InterruptedIOException] (thrown by pread/pwrite on
     * signal interruption) already extends [IOException] and passes through unchanged.
     */
    @Throws(IOException::class)
    private inline fun <T> runOsIo(block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw IOException(e)
    }

    override fun isOpen(): Boolean =
        if (remoteChannel != null) {
            !isRemoteClosed
        } else {
            localChannel!!.isOpen
        }

    @Throws(IOException::class)
    override fun close() {
        if (remoteChannel != null) {
            remoteChannel.call { exception -> close(exception) }
            // The duplicated fd is independent of the remote channel's fd; close our copy.
            remoteFd?.close()
            isRemoteClosed = true
        } else {
            localChannel!!.close()
        }
    }

    private class Stub(
        private val channel: SeekableByteChannel
    ) : IRemoteSeekableByteChannel.Stub() {
        override fun read(destination: ByteArray, exception: ParcelableException): Int =
            tryRun(exception) { channel.read(ByteBuffer.wrap(destination)) } ?: 0

        override fun write(source: ByteArray, exception: ParcelableException): Int =
            tryRun(exception) { channel.write(ByteBuffer.wrap(source)) } ?: 0

        override fun position(exception: ParcelableException): Long =
            tryRun(exception) { channel.position() } ?: 0

        override fun position2(newPosition: Long, exception: ParcelableException) {
            tryRun(exception) { channel.position(newPosition) }
        }

        override fun size(exception: ParcelableException): Long =
            tryRun(exception) { channel.size() } ?: 0

        override fun truncate(size: Long, exception: ParcelableException) {
            tryRun(exception) { channel.truncate(size) }
        }

        override fun force(metaData: Boolean, exception: ParcelableException) {
            tryRun(exception) { channel.force(metaData) }
        }

        override fun close(exception: ParcelableException) {
            tryRun(exception) { channel.close() }
        }

        override fun openFd(exception: ParcelableException): ParcelFileDescriptor? =
            // Returns a duplicated fd when the channel is fd-backed; null otherwise, so the
            // client transparently falls back to the per-call read()/write() IPC.
            tryRun(exception) { channel.dupFileDescriptorOrNull() }
    }

    private constructor(source: Parcel) {
        localChannel = null
        remoteChannel = IRemoteSeekableByteChannel.Stub.asInterface(source.readStrongBinder())
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        if (remoteChannel != null) {
            dest.writeStrongBinder(remoteChannel.asBinder())
        } else {
            dest.writeStrongBinder(Stub(localChannel!!).asBinder())
        }
    }

    companion object {
        private const val FD_UNKNOWN = 0
        private const val FD_READY = 1
        private const val FD_UNAVAILABLE = 2

        @JvmField
        val CREATOR = object : Parcelable.Creator<RemoteSeekableByteChannel> {
            override fun createFromParcel(source: Parcel): RemoteSeekableByteChannel =
                RemoteSeekableByteChannel(source)

            override fun newArray(size: Int): Array<RemoteSeekableByteChannel?> = arrayOfNulls(size)
        }
    }
}

fun SeekableByteChannel.toRemote(): RemoteSeekableByteChannel = RemoteSeekableByteChannel(this)
