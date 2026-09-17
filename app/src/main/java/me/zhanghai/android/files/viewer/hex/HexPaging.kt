/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.TreeMap

internal const val HEX_PAGE_SIZE = 4096
internal const val HEX_BYTES_PER_LINE = 16

/** Adapter interface deliberately independent of Android and the java8.nio provider types. */
internal interface HexSeekableSource : Closeable {
    fun position(offset: Long)
    fun read(buffer: ByteBuffer): Int
}

internal data class HexPage(val offset: Long, val bytes: ByteArray, val eofOffset: Long? = null) {
    val endOffset: Long get() = offset + bytes.size

    fun lines(): List<HexLine> = bytes.indices.step(HEX_BYTES_PER_LINE).map { index ->
        val count = minOf(HEX_BYTES_PER_LINE, bytes.size - index)
        val slice = ByteArray(HEX_BYTES_PER_LINE)
        System.arraycopy(bytes, index, slice, 0, count)
        val absoluteOffset = offset + index
        HexLine(absoluteOffset / HEX_BYTES_PER_LINE, absoluteOffset, slice, count)
    }
}

/**
 * Single-owner reader. Calls (including close) must be serialized by the owner. Streams stay open
 * across forward reads, and are reopened only to seek backwards. No file-sized cache or spool.
 * [checkActive] is invoked between blocking provider operations, including zero-progress retries.
 */
internal class HexPageReader(
    private val openStream: () -> InputStream,
    private val openChannel: () -> HexSeekableSource? = { null }
) : Closeable {
    private var channel: HexSeekableSource? = null
    private var channelProbed = false
    private var input: InputStream? = null
    private var streamOffset = 0L
    private var closed = false

    fun read(offset: Long, size: Int = HEX_PAGE_SIZE, checkActive: () -> Unit = {}): HexPage {
        require(offset >= 0 && size > 0)
        check(!closed) { "Reader is closed" }
        checkActive()
        if (!channelProbed) {
            channel = openChannel()
            channelProbed = true
        }
        val source = channel
        if (source != null) {
            source.position(offset)
        } else {
            if (offset < streamOffset) {
                val previous = input
                input = null
                previous?.close()
                streamOffset = 0
            }
            if (input == null) input = openStream()
            val scratch = ByteArray(8192)
            while (streamOffset < offset) {
                checkActive()
                val remaining = offset - streamOffset
                val skipped = input!!.skip(remaining)
                if (skipped > 0) {
                    if (skipped > remaining) throw IOException("Invalid stream skip count")
                    streamOffset += skipped
                } else {
                    val count = readStream(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                    if (count < 0) return HexPage(offset, ByteArray(0), streamOffset)
                    streamOffset += count
                }
            }
        }
        val bytes = ByteArray(minOf(size.toLong(), Long.MAX_VALUE - offset).toInt())
        var count = 0
        var zeroReads = 0
        var eof = false
        while (count < bytes.size) {
            checkActive()
            val read = if (source != null) {
                source.read(ByteBuffer.wrap(bytes, count, bytes.size - count))
            } else {
                readStream(bytes, count, bytes.size - count)
            }
            when {
                read < 0 -> { eof = true; break }
                read == 0 -> {
                    // A blocking provider may temporarily return zero, but must not spin forever.
                    if (++zeroReads >= 16) throw IOException("Channel made no read progress")
                }
                else -> {
                    if (read > bytes.size - count) throw IOException("Invalid read count")
                    count += read
                    if (source == null) streamOffset += read
                    zeroReads = 0
                }
            }
        }
        return HexPage(offset, bytes.copyOf(count), if (eof) offset + count else null)
    }

    private fun readStream(bytes: ByteArray, offset: Int, length: Int): Int {
        val stream = input!!
        val count = stream.read(bytes, offset, length)
        if (count != 0) return count
        // Some providers violate InputStream's bulk-read contract. One-byte read guarantees
        // progress or EOF; a returned zero here is a valid byte, not a zero-length read.
        val byte = stream.read()
        if (byte < 0) return -1
        bytes[offset] = byte.toByte()
        return 1
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            channel?.close()
        } finally {
            input?.close()
            channel = null
            input = null
        }
    }
}

/** Contiguous bidirectional page window. All mutation belongs to the UI thread. */
internal class HexPageWindow(private val maxPages: Int = 5) {
    init { require(maxPages > 0) }
    private val pages = TreeMap<Long, HexPage>()
    val pageCount: Int get() = pages.size
    val firstOffset: Long? get() = pages.firstEntry()?.value?.offset
    val endOffset: Long? get() = pages.lastEntry()?.value?.endOffset

    fun put(page: HexPage, replace: Boolean = false) {
        if (replace) pages.clear()
        if (page.bytes.isEmpty()) return
        val prepend = firstOffset?.let { page.offset < it } ?: false
        if (pages.isNotEmpty() && !pages.containsKey(page.offset)) {
            require(page.endOffset == firstOffset || page.offset == endOffset) {
                "Nonadjacent page must replace the window"
            }
        }
        pages[page.offset] = page
        while (pages.size > maxPages) {
            if (prepend) pages.pollLastEntry() else pages.pollFirstEntry()
        }
    }

    fun lines(): List<HexLine> = pages.values.flatMap { it.lines() }

    fun edit(offset: Long, value: Byte) {
        val page = pages.floorEntry(offset)?.value ?: return
        if (offset < page.endOffset) page.bytes[(offset - page.offset).toInt()] = value
    }

    fun clear() = pages.clear()
}

/** Snapshot and revision bookkeeping, separate from the service and Fragment lifecycle. */
internal class HexEditState(val buffer: ByteArray) {
    private var revision = 0L
    private var savedRevision = 0L
    private var savingRevision: Long? = null
    val isDirty: Boolean get() = revision != savedRevision
    val isSaving: Boolean get() = savingRevision != null

    fun edit(offset: Long, value: Byte): Boolean {
        if (offset < 0 || offset >= buffer.size || buffer[offset.toInt()] == value) return false
        buffer[offset.toInt()] = value
        ++revision
        return true
    }

    fun beginSave(): ByteArray? {
        if (!isDirty || isSaving) return null
        val snapshot = buffer.copyOf()
        savingRevision = revision
        return snapshot
    }

    fun finishSave(successful: Boolean) {
        val saving = savingRevision ?: return
        if (successful) savedRevision = saving
        savingRevision = null
    }
}
