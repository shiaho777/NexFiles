/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.ui

/**
 * A character-cell grid modelling the terminal screen. Each cell holds a Unicode code point plus
 * an attribute byte encoding foreground/background colour and the bold/underline/reverse flags
 * that SGR sequences manipulate. The buffer supports the two-region model VT100 needs (scroll
 * region + visible grid), plus line-wrapping and history-scroll that [TerminalView] consumes.
 *
 * Kept deliberately allocation-light: cells are plain IntArrays (one Int = codepoint, one Int =
 * attribute) so redraws and scrolls don't churn the GC.
 */
class TerminalBuffer(val rows: Int, val cols: Int) {
    /** Per-cell code points, row-major: index = row * cols + col. 0 means blank. */
    private val codePoints = IntArray(rows * cols)
    /** Per-cell attributes, packed as Foreground(0-15) | Background(0-15)<<4 | Flags<<8. */
    private val attributes = IntArray(rows * cols)
    /** Lines scrolled off the top, oldest first. Bounded to avoid unbounded growth. */
    private val history = ArrayList<CharArray>()
    private val historyAttributes = ArrayList<IntArray>()

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set

    // Scroll region; default full screen. Top/bottom inclusive.
    var scrollTop = 0
        private set
    var scrollBottom = rows - 1
        private set

    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set

    private val MAX_HISTORY = 2000

    fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    fun moveCursor(dRow: Int, dCol: Int) {
        setCursor(cursorRow + dRow, cursorCol + dCol)
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    fun setCursorVisible(visible: Boolean) { cursorVisible = visible }
    fun setApplicationCursorKeys(on: Boolean) { applicationCursorKeys = on }

    fun clear() {
        codePoints.fill(0)
        attributes.fill(0)
        setCursor(0, 0)
    }

    /** Clears from the cursor to end-of-line, inclusive. */
    fun clearLineFromCursor() {
        val base = cursorRow * cols
        for (c in cursorCol until cols) {
            codePoints[base + c] = 0
            attributes[base + c] = 0
        }
    }

    fun clearLineAll(row: Int) {
        if (row !in 0 until rows) return
        val base = row * cols
        for (c in 0 until cols) {
            codePoints[base + c] = 0
            attributes[base + c] = 0
        }
    }

    /** Clears from cursor to end of screen. */
    fun clearFromCursor() {
        clearLineFromCursor()
        for (r in (cursorRow + 1) until rows) {
            clearLineAll(r)
        }
    }

    /** Clears from start of screen to cursor inclusive. */
    fun clearToCursor() {
        for (r in 0 until cursorRow) {
            clearLineAll(r)
        }
        val base = cursorRow * cols
        for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
            codePoints[base + c] = 0
            attributes[base + c] = 0
        }
    }

    /** Writes one codepoint at the cursor with [attr], advancing the cursor (with line wrap). */
    fun put(codePoint: Int, attr: Int) {
        if (cursorCol >= cols) {
            // Auto-wrap: move to next line. Real terminals track an implicit "pending wrap" state
            // but a simple advance is enough for vim/htop/apt.
            cursorCol = 0
            cursorRow++
            if (cursorRow > scrollBottom) {
                scrollUp(1)
                cursorRow = scrollBottom
            }
        }
        val index = cursorRow * cols + cursorCol
        codePoints[index] = codePoint
        attributes[index] = attr
        cursorCol++
    }

    /** Scrolls [n] lines up within the scroll region, pushing the top line into history. */
    fun scrollUp(n: Int) {
        if (n <= 0) return
        val regionHeight = scrollBottom - scrollTop + 1
        val move = n.coerceAtMost(regionHeight)
        // Capture moved-out lines into history.
        for (i in 0 until move) {
            val row = scrollTop + i
            pushHistory(row)
        }
        // Shift rows up within the region.
        val rowCount = regionHeight - move
        for (i in 0 until rowCount) {
            val src = (scrollTop + move + i) * cols
            val dst = (scrollTop + i) * cols
            System.arraycopy(codePoints, src, codePoints, dst, cols)
            System.arraycopy(attributes, src, attributes, dst, cols)
        }
        // Clear the newly-freed bottom rows.
        for (i in rowCount until regionHeight) {
            val base = (scrollTop + i) * cols
            for (c in 0 until cols) {
                codePoints[base + c] = 0
                attributes[base + c] = 0
            }
        }
    }

    /** Scrolls [n] lines down within the scroll region (content moves down, top fills blank). */
    fun scrollDown(n: Int) {
        if (n <= 0) return
        val regionHeight = scrollBottom - scrollTop + 1
        val move = n.coerceAtMost(regionHeight)
        // Shift rows down from bottom to top of region.
        for (i in (regionHeight - move - 1) downTo 0) {
            val src = (scrollTop + i) * cols
            val dst = (scrollTop + i + move) * cols
            System.arraycopy(codePoints, src, codePoints, dst, cols)
            System.arraycopy(attributes, src, attributes, dst, cols)
        }
        // Clear the top `move` rows.
        for (i in 0 until move) {
            val base = (scrollTop + i) * cols
            for (c in 0 until cols) {
                codePoints[base + c] = 0
                attributes[base + c] = 0
            }
        }
    }

    private fun pushHistory(row: Int) {
        if (history.size >= MAX_HISTORY) {
            history.removeAt(0)
            historyAttributes.removeAt(0)
        }
        val chars = CharArray(cols)
        val attrs = IntArray(cols)
        val base = row * cols
        for (c in 0 until cols) {
            val cp = codePoints[base + c]
            chars[c] = if (cp == 0) ' ' else Character.toChars(cp).firstOrNull() ?: ' '
            attrs[c] = attributes[base + c]
        }
        history.add(chars)
        historyAttributes.add(attrs)
    }

    fun codePointAt(row: Int, col: Int): Int =
        if (row in 0 until rows && col in 0 until cols) codePoints[row * cols + col] else 0

    fun attributeAt(row: Int, col: Int): Int =
        if (row in 0 until rows && col in 0 until cols) attributes[row * cols + col] else 0

    /** History rows currently retained (for scrollback rendering in [TerminalView]). */
    val historySize: Int
        get() = history.size

    fun historyRow(row: Int): CharArray = history[row]
    fun historyAttributes(row: Int): IntArray = historyAttributes[row]
}
