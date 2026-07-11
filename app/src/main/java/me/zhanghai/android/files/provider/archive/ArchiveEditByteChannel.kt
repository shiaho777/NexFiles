/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.Path
import java8.nio.channels.SeekableByteChannel
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A writable byte channel that buffers archive-edit writes in memory and flushes them to the
 * [ArchiveFileSystem] edit overlay on [close]. This is what backs `Files.newOutputStream` against
 * a path inside an archive: edits never touch the archive directly; they land in the overlay and
 * are rebuilt into the archive when the user commits.
 *
 * Reads (positioned) are also supported so that callers that mix read/write on the same channel
 * work, though the typical editor flow only writes.
 */
internal class ArchiveEditByteChannel(
    private val path: Path,
    private val fileSystem: ArchiveFileSystem
) : SeekableByteChannel {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size: Long = 0
    private var position: Long = 0
    private var isOpen = true

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0) { "position < 0" }
        position = newPosition
        return this
    }

    override fun size(): Long = size

    override fun truncate(newSize: Long): SeekableByteChannel {
        require(newSize >= 0) { "size < 0" }
        if (newSize < size) {
            size = newSize
            if (position > size) position = size
        }
        return this
    }

    override fun read(dst: ByteBuffer): Int {
        if (position >= size) return -1
        val toRead = minOf(dst.remaining().toLong(), size - position).toInt()
        dst.put(buffer, position.toInt(), toRead)
        position += toRead
        return toRead
    }

    override fun write(src: ByteBuffer): Int {
        val toWrite = src.remaining()
        ensureCapacity(position + toWrite)
        src.get(buffer, position.toInt(), toWrite)
        position += toWrite
        if (position > size) size = position
        return toWrite
    }

    private fun ensureCapacity(required: Long) {
        if (required <= buffer.size) return
        var newCapacity = buffer.size
        while (newCapacity < required) newCapacity = newCapacity * 2
        buffer = buffer.copyOf(newCapacity)
    }

    override fun isOpen(): Boolean = isOpen

    override fun close() {
        if (!isOpen) return
        isOpen = false
        // Commit the staged bytes into the overlay. Copy so the caller can't mutate post-close.
        val bytes = buffer.copyOf(size.toInt())
        fileSystem.writeFile(path, bytes)
    }

    companion object {
        private const val INITIAL_CAPACITY = 8192
    }
}
