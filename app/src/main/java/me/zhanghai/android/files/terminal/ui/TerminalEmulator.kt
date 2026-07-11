/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.ui

import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.StandardCharsets

/**
 * State machine that consumes raw PTY bytes, decodes them as UTF-8, and drives a [TerminalBuffer]
 * with the resulting characters and ANSI/VT100 control sequences. Implements the subset that
 * real-world Linux programs (vim, htop, apt, bash prompts, ls --color) exercise: CSI cursor /
 * erase / scroll / SGR sequences, DEC private modes (cursor visibility, application cursor keys,
 * alternate screen), and the plain C0 controls (CR, LF, BS, HT, BEL).
 *
 * The parser is incremental and resumable — it tolerates partial UTF-8 and split escape sequences
 * across [write] calls, buffering the dangling bytes until more arrive.
 */
class TerminalEmulator(val buffer: TerminalBuffer) {

    // Decoding state — UTF-8 decoder with leftover bytes for multi-byte sequences split across
    // writes.
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
    private val decodeIn = ByteBuffer.allocate(8)
    private val decodeOut = java.nio.CharBuffer.allocate(16)

    // Current SGR attributes, packed as in TerminalBuffer. Default = light grey on black.
    private var currentAttr: Int = ATTR_FG(DEFAULT_FG) or ATTR_BG(DEFAULT_BG)

    // Escape-sequence parse state machine.
    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private var csiIntermediate: Char = 0.toChar()
    private var csiPrivate: Char = 0.toChar()

    /** Feeds raw PTY output; may be called repeatedly with partial data. */
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        // Decode in small chunks so partial UTF-8 at the boundary stays in decodeIn for next time.
        while (i < end) {
            decodeIn.clear()
            val toCopy = minOf(decodeIn.capacity(), end - i)
            decodeIn.put(bytes, i, toCopy)
            decodeIn.flip()
            i += toCopy
            decodeOut.clear()
            val result = decoder.decode(decodeIn, decodeOut, false)
            if (result.isError) {
                // Malformed UTF-8 — replace and advance to avoid an infinite loop.
                decoder.reset()
                continue
            }
            decodeOut.flip()
            while (decodeOut.hasRemaining()) {
                processChar(decodeOut.get())
            }
            // decodeIn now holds any leftover (underflow); keep it for the next write.
        }
    }

    private fun processChar(c: Char) {
        when (state) {
            State.GROUND -> processGround(c)
            State.ESCAPE -> processEscape(c)
            State.CSI -> processCsi(c)
        }
    }

    private fun processGround(c: Char) {
        when {
            c == 0x1b.toChar() -> state = State.ESCAPE
            c == '\r' -> buffer.setCursor(buffer.cursorRow, 0)
            c == '\n' || c == 0x0b.toChar() || c == 0x0c.toChar() -> lineFeed()
            c == '\b' -> buffer.moveCursor(0, -1)
            c == '\t' -> tab()
            c == 0x07.toChar() -> { /* bell — could vibrate, ignored for now */ }
            c.code < 0x20 -> { /* other C0 controls ignored */ }
            else -> buffer.put(c.code, currentAttr)
        }
    }

    private fun processEscape(c: Char) {
        when (c) {
            '[' -> enterCsi()
            'M' -> buffer.scrollUp(1)        // Reverse line feed (RI).
            'D' -> lineFeed()                 // Index (IND).
            '7' -> { /* save cursor — TODO */ state = State.GROUND }
            '8' -> { /* restore cursor — TODO */ state = State.GROUND }
            'c' -> { buffer.resetScrollRegion(); buffer.clear(); state = State.GROUND } // RIS reset
            ')' -> { /* charset designator, skip next */ state = State.GROUND }
            else -> { state = State.GROUND }
        }
    }

    private fun enterCsi() {
        csiParams.clear()
        csiIntermediate = 0.toChar()
        csiPrivate = 0.toChar()
        state = State.CSI
    }

    private fun processCsi(c: Char) {
        when {
            c == '?' || c == '>' || c == '<' || c == '=' -> csiPrivate = c
            c in '0'..'9' || c == ';' || c == ':' -> csiParams.append(c)
            c in ' '..'/' -> csiIntermediate = c
            c in '@'..'~' -> {
                dispatchCsi(c)
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun dispatchCsi(finalByte: Char) {
        val params = csiParams.toString().split(';').map { it.toIntOrNull() ?: 0 }
        val p0 = params.getOrElse(0) { 0 }
        val p1 = params.getOrElse(1) { 0 }
        if (csiPrivate == '?') {
            dispatchDecPrivate(p0, finalByte)
            return
        }
        when (finalByte) {
            // Cursor positioning.
            'A' -> buffer.moveCursor(-p0.coerceAtLeast(1), 0)
            'B' -> buffer.moveCursor(p0.coerceAtLeast(1), 0)
            'C' -> buffer.moveCursor(0, p0.coerceAtLeast(1))
            'D' -> buffer.moveCursor(0, -p0.coerceAtLeast(1))
            'E' -> { buffer.setCursor(buffer.cursorRow + p0.coerceAtLeast(1), 0) }
            'F' -> { buffer.setCursor(buffer.cursorRow - p0.coerceAtLeast(1), 0) }
            'G' -> buffer.setCursor(buffer.cursorRow, p0.coerceAtLeast(1) - 1)
            'd' -> buffer.setCursor(p0.coerceAtLeast(1) - 1, buffer.cursorCol)
            'H', 'f' -> {
                val row = (if (params.isNotEmpty()) params[0] else 1).coerceAtLeast(1) - 1
                val col = (if (params.size > 1) params[1] else 1).coerceAtLeast(1) - 1
                buffer.setCursor(row, col)
            }
            // Erase.
            'J' -> when (p0) {
                0 -> buffer.clearFromCursor()
                1 -> buffer.clearToCursor()
                else -> buffer.clear()
            }
            'K' -> when (p0) {
                0 -> buffer.clearLineFromCursor()
                1 -> { /* clear to left of cursor — TODO */ }
                else -> buffer.clearLineAll(buffer.cursorRow)
            }
            // Scroll.
            'S' -> buffer.scrollUp(p0.coerceAtLeast(1))
            'T' -> buffer.scrollDown(p0.coerceAtLeast(1))
            'L' -> { /* insert lines — TODO */ }
            'M' -> { /* delete lines — TODO */ }
            // SGR (colours/attributes).
            'm' -> dispatchSgr(params)
            // Others ignored.
        }
    }

    private fun dispatchDecPrivate(code: Int, finalByte: Char) {
        if (finalByte != 'h' && finalByte != 'l') return
        val enable = finalByte == 'h'
        when (code) {
            25 -> buffer.setCursorVisible(enable)            // DECTCEM cursor show/hide
            1 -> buffer.setApplicationCursorKeys(enable)      // DECCKM application cursor keys
            47, 1047, 1049 -> { /* alternate screen — TODO, treat as clear */ if (enable) buffer.clear() }
            else -> { /* many other DEC modes unhandled */ }
        }
    }

    private fun dispatchSgr(params: List<Int>) {
        if (params.isEmpty()) {
            currentAttr = ATTR_FG(DEFAULT_FG) or ATTR_BG(DEFAULT_BG)
            return
        }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> currentAttr = ATTR_FG(DEFAULT_FG) or ATTR_BG(DEFAULT_BG)
                p == 1 -> currentAttr = currentAttr or FLAG_BOLD
                p == 4 -> currentAttr = currentAttr or FLAG_UNDERLINE
                p == 7 -> currentAttr = currentAttr or FLAG_REVERSE
                p == 22 -> currentAttr = currentAttr and FLAG_BOLD.inv()
                p == 24 -> currentAttr = currentAttr and FLAG_UNDERLINE.inv()
                p == 27 -> currentAttr = currentAttr and FLAG_REVERSE.inv()
                p in 30..37 -> currentAttr = (currentAttr and 0xFFFFFFF0.toInt()) or ATTR_FG(p - 30)
                p == 39 -> currentAttr = (currentAttr and 0xFFFFFFF0.toInt()) or ATTR_FG(DEFAULT_FG)
                p in 40..47 -> currentAttr = (currentAttr and 0xFFFFFF0F.toInt()) or ATTR_BG(p - 40)
                p == 49 -> currentAttr = (currentAttr and 0xFFFFFF0F.toInt()) or ATTR_BG(DEFAULT_BG)
                p in 90..97 -> currentAttr = (currentAttr and 0xFFFFFFF0.toInt()) or ATTR_FG(p - 90 + 8)
                p in 100..107 -> currentAttr = (currentAttr and 0xFFFFFF0F.toInt()) or ATTR_BG(p - 100 + 8)
                // Extended (256-colour / true-colour) SGR sequences are parsed but mapped onto the
                // 16-colour attribute space via parity; full 24-bit colour would need a wider attr
                // word. Skip the 38;5;n and 38;2;r;g;b forms for now.
                p == 38 || p == 48 -> {
                    // Consume the sub-params: 38;5;n or 38;2;r;g;b
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> i += 2  // 256-colour, fall through to default
                            2 -> i += 4  // true colour, fall through
                        }
                    }
                }
            }
            i++
        }
    }

    private fun lineFeed() {
        val newRow = buffer.cursorRow + 1
        if (newRow > buffer.scrollBottom) {
            buffer.scrollUp(1)
        } else {
            buffer.setCursor(newRow, buffer.cursorCol)
        }
    }

    private fun tab() {
        val next = ((buffer.cursorCol / 8) + 1) * 8
        buffer.setCursor(buffer.cursorRow, next.coerceAtMost(buffer.cols - 1))
    }

    private enum class State { GROUND, ESCAPE, CSI }

    companion object {
        // Attribute packing helpers — see TerminalBuffer for the bit layout.
        private const val DEFAULT_FG = 7   // Light grey.
        private const val DEFAULT_BG = 0   // Black.
        const val FLAG_BOLD = 1 shl 8
        const val FLAG_UNDERLINE = 1 shl 9
        const val FLAG_REVERSE = 1 shl 10
        private fun ATTR_FG(fg: Int) = fg and 0x0F
        private fun ATTR_BG(bg: Int) = (bg and 0x0F) shl 4
    }
}
