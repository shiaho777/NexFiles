package me.zhanghai.android.files.viewer.hex

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class HexPagingTest {
    @Test fun sequentialStreamDoesNotReskipPrefixAndBackwardSeekReopens() {
        val bytes = ByteArray(12288) { it.toByte() }
        var opens = 0
        var closes = 0
        var consumed = 0
        var skipped = 0L
        val reader = HexPageReader({
            opens++
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, minOf(len, 13)).also { if (it > 0) consumed += it }
                override fun skip(n: Long): Long = super.skip(n).also { skipped += it }
                override fun close() { closes++; super.close() }
            }
        })
        reader.use {
            for (i in 0..2) assertArrayEquals(bytes.copyOfRange(i * 4096, (i + 1) * 4096),
                it.read(i * 4096L).bytes)
            assertEquals(1, opens)
            assertEquals(12288, consumed)
            assertEquals(0L, skipped)
            assertArrayEquals(bytes.copyOfRange(4096, 8192), it.read(4096).bytes)
            assertEquals(2, opens)
            assertEquals(1, closes)
            assertEquals(4096L, skipped)
        }
        assertEquals(2, closes)
    }

    @Test fun directLongJumpAndShortChannelReadsKeepLongLineIds() {
        val target = (Int.MAX_VALUE.toLong() + 100) * 16
        var position = 0L
        var seeks = 0
        var opens = 0
        var closes = 0
        var reads = 0
        HexPageReader({ throw AssertionError("stream used") }, {
            opens++
            object : HexSeekableSource {
                override fun position(offset: Long) { position = offset; seeks++ }
                override fun read(buffer: ByteBuffer): Int {
                    reads++
                    if (reads == 1) return 0
                    if (position >= target + 19) return -1
                    val count = minOf(3, buffer.remaining(), (target + 19 - position).toInt())
                    repeat(count) { buffer.put((position++ - target).toByte()) }
                    return count
                }
                override fun close() { closes++ }
            }
        }).use {
            val page = it.read(target)
            assertArrayEquals(ByteArray(19) { index -> index.toByte() }, page.bytes)
            assertEquals(target + 19, page.eofOffset)
            assertEquals(target / 16, page.lines()[0].lineIndex)
            assertEquals(target + 16, page.lines()[1].globalOffset)
            assertEquals(3, page.lines()[1].validByteCount)
            assertEquals(1, seeks)
            assertEquals(1, opens)
        }
        assertEquals(1, closes)
    }

    @Test fun zeroSkipAndZeroBulkReadStillReachEof() {
        HexPageReader({
            object : InputStream() {
                var position = 0
                override fun skip(n: Long) = 0L
                override fun read(b: ByteArray, off: Int, len: Int) = 0
                override fun read(): Int = if (position < 23) position++ else -1
            }
        }).use {
            val page = it.read(16)
            assertArrayEquals(ByteArray(7) { index -> (index + 16).toByte() }, page.bytes)
            assertEquals(23L, page.eofOffset)
            val beyond = it.read(4096)
            assertTrue(beyond.bytes.isEmpty())
            assertEquals(23L, beyond.eofOffset)
            assertEquals(23, it.read(0).bytes.size)
        }
    }

    @Test fun emptyAndExactPageEof() {
        HexPageReader({ ByteArrayInputStream(ByteArray(4096)) }).use {
            assertNull(it.read(0).eofOffset)
            val eof = it.read(4096)
            assertTrue(eof.bytes.isEmpty())
            assertEquals(4096L, eof.eofOffset)
        }
        HexPageReader({ ByteArrayInputStream(ByteArray(0)) }).use {
            assertEquals(0L, it.read(0).eofOffset)
        }
    }

    @Test fun zeroChannelProgressFailsRatherThanPretendingEof() {
        var calls = 0
        HexPageReader({ throw AssertionError() }, {
            object : HexSeekableSource {
                override fun position(offset: Long) {}
                override fun read(buffer: ByteBuffer): Int { calls++; return 0 }
                override fun close() {}
            }
        }).use {
            try { it.read(0); fail("Expected IOException") } catch (_: IOException) {}
        }
        assertEquals(16, calls)
    }

    @Test fun cancellationPropagatesAndReaderCloses() {
        var closed = false
        var checks = 0
        val cancellation = CancellationException("test")
        try {
            HexPageReader({
                object : ByteArrayInputStream(ByteArray(100)) {
                    override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, 1)
                    override fun close() { closed = true }
                }
            }).use { reader -> reader.read(0) { if (++checks == 5) throw cancellation } }
            fail("Expected cancellation")
        } catch (e: CancellationException) { assertSame(cancellation, e) }
        assertTrue(closed)
    }

    @Test fun windowStaysBoundedInBothDirectionsAndJumpReplaces() {
        val window = HexPageWindow(3)
        for (i in 0..99) {
            window.put(HexPage(i * 4096L, ByteArray(4096)))
            assertTrue(window.pageCount <= 3)
            assertTrue(window.lines().size <= 768)
        }
        assertEquals(97 * 4096L, window.firstOffset)
        for (i in 96 downTo 0) {
            window.put(HexPage(i * 4096L, ByteArray(4096)))
            assertEquals(3, window.pageCount)
        }
        assertEquals(0L, window.firstOffset)
        assertEquals(12288L, window.endOffset)
        val target = 1L shl 40
        window.put(HexPage(target, ByteArray(19)), replace = true)
        assertEquals(1, window.pageCount)
        assertEquals(target / 16, window.lines().first().lineIndex)
        window.edit(target + 17, 42)
        assertEquals(42.toByte(), window.lines()[1].bytes[1])
        window.put(HexPage(target + 4096, ByteArray(0)), replace = true)
        assertEquals(0, window.pageCount)
    }

    @Test fun saveSnapshotGuardsRepeatAndPreservesNewEdits() {
        val state = HexEditState(ByteArray(4))
        assertNull(state.beginSave())
        assertFalse(state.edit(-1, 1))
        assertFalse(state.edit(4, 1))
        assertFalse(state.edit(0, 0))
        assertTrue(state.edit(0, 1))
        val snapshot = state.beginSave()
        assertTrue(state.isSaving)
        assertNull(state.beginSave())
        state.edit(1, 2)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), snapshot)
        state.finishSave(true)
        assertTrue(state.isDirty)
        assertFalse(state.isSaving)
        assertArrayEquals(byteArrayOf(1, 2, 0, 0), state.beginSave())
        state.finishSave(false)
        assertTrue(state.isDirty)
        assertFalse(state.isSaving)
        state.beginSave()
        state.finishSave(true)
        assertFalse(state.isDirty)
    }
}
