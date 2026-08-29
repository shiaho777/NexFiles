/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Self-drawing terminal cell grid. Renders the [TerminalBuffer] as a monospace character matrix
 * (foreground colours from SGR, optional bold/underline) and turns user key events into the ANSI
 * byte sequences a shell expects (arrows, Ctrl, Enter, Tab, Backspace). Input bytes are handed to
 * the registered [onInput] callback, which typically forwards them to a [TerminalSession].
 *
 * We draw via Canvas rather than TextView so we can scroll a multi-thousand-row buffer, support
 * the cursor, and avoid the cost of building Spannables for every redraw.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 14f * resources.displayMetrics.density
    }
    private val fontMetrics = paint.fontMetrics
    private val cellWidth: Float = paint.measureText("M")
    private val cellHeight: Float = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
    private val ascent = -fontMetrics.ascent

    // The 16 ANSI colours; project themes own a palette but we use a fixed reasonable default so
    // the terminal reads correctly regardless of light/dark mode.
    private val palette = intArrayOf(
        0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
        0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(),
        0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
        0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt()
    )

    var buffer: TerminalBuffer? = null
        set(value) {
            field = value
            emulator = value?.let { TerminalEmulator(it) }
            updateSize()
            invalidate()
        }
    private var emulator: TerminalEmulator? = null

    /** Called with the ANSI bytes the user has produced via the IME or hardware keys. */
    var onInput: ((ByteArray) -> Unit)? = null

    /** Scroll offset in rows (0 = bottom of history visible). Set by the scroller in the activity. */
    var scrollRow = 0
        set(value) {
            field = value.coerceAtLeast(0)
            invalidate()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(palette[0])
    }

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        emulator?.write(bytes, offset, length)
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return BaseInputConnection(this, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = keyToAnsi(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onInput?.invoke(bytes)
        return true
    }

    private fun keyToAnsi(keyCode: Int, event: KeyEvent): ByteArray? {
        val buf = emulator ?: return null
        val appCursor = buf.buffer.applicationCursorKeys
        fun arrow(seq: String) = (if (appCursor) "\u001bO${seq.last()}" else seq).toByteArray()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> arrow("\u001b[A")
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow("\u001b[B")
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow("\u001b[C")
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow("\u001b[D")
            KeyEvent.KEYCODE_ENTER -> byteArrayOf('\r'.code.toByte())
            KeyEvent.KEYCODE_TAB -> byteArrayOf('\t'.code.toByte())
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7f)  // DEL, what bash expects for backspace
            KeyEvent.KEYCODE_HOME -> "\u001b[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~".toByteArray()
            else -> {
                // Ctrl+letter: map A-Z (and a-z) to their control code.
                val c = event.unicodeChar
                if (event.isCtrlPressed && c in 0x61..0x7a) {
                    byteArrayOf((c - 0x60).toByte())
                } else if (c != 0) {
                    // Pass printable characters through verbatim (UTF-8 encoded by String).
                    String(Character.toChars(c)).toByteArray()
                } else null
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    private fun updateSize() {
        val buf = buffer ?: return
        if (buf.cols < 1 || buf.rows < 1) return
        // The buffer is the source of truth for cell counts; the activity reconciles them when the
        // view changes size. Nothing to do here beyond a redraw.
        invalidate()
    }

    /** Visible cell columns given the current view width. */
    fun visibleCols(): Int = ((width / cellWidth).toInt()).coerceAtLeast(1)

    /** Visible cell rows given the current view height. */
    fun visibleRows(): Int = ((height / cellHeight).toInt()).coerceAtLeast(1)

    private val tmpRect = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buf = buffer ?: return
        val cols = buf.cols
        val visibleRows = (height / cellHeight).toInt()
        // History offset: how many history rows are scrolled into view from the top.
        val historyVisible = minOf(scrollRow, buf.historySize)
        for (row in 0 until visibleRows) {
            val y = row * cellHeight + ascent
            // Map view row → buffer row. With no scroll, the bottom `visibleRows` of the buffer
            // are shown; scrolling reveals history above.
            val bufferRow = row + (buf.rows - visibleRows) - scrollRow + historyVisible
            for (col in 0 until cols) {
                val x = col * cellWidth
                val cp: Int
                val attr: Int
                if (bufferRow in 0 until buf.rows) {
                    cp = buf.codePointAt(bufferRow, col)
                    attr = buf.attributeAt(bufferRow, col)
                } else {
                    // History row.
                    val histRow = bufferRow + buf.rows
                    val inRange = histRow in 0 until buf.historySize
                    if (inRange) {
                        val chars = buf.historyRow(histRow)
                        val attrs = buf.historyAttributes(histRow)
                        cp = if (col < chars.size) chars[col].code else 0
                        attr = if (col < attrs.size) attrs[col] else 0
                    } else {
                        cp = 0
                        attr = 0
                    }
                }
                // Background fill for non-default bg so coloured prompts/htop bars show.
                val bgIndex = (attr shr 4) and 0x0F
                if (bgIndex != 0) {
                    paint.color = palette[bgIndex]
                    tmpRect.set(x.toInt(), (row * cellHeight).toInt(),
                        (x + cellWidth).toInt(), ((row + 1) * cellHeight).toInt())
                    canvas.drawRect(tmpRect, paint)
                }
                if (cp == 0) continue
                val fgIndex = attr and 0x0F
                paint.color = palette[fgIndex]
                paint.isFakeBoldText = (attr and TerminalEmulator.FLAG_BOLD) != 0
                paint.isUnderlineText = (attr and TerminalEmulator.FLAG_UNDERLINE) != 0
                val str = String(Character.toChars(cp))
                canvas.drawText(str, x, y, paint)
            }
        }
        // Cursor block (only when visible, on screen, and not scrolled into history).
        if (buf.cursorVisible && scrollRow == 0
            && buf.cursorRow in (buf.rows - visibleRows) until buf.rows
            && buf.cursorCol in 0 until cols) {
            val viewRow = buf.cursorRow - (buf.rows - visibleRows)
            val x = (buf.cursorCol * cellWidth).toInt()
            val y = (viewRow * cellHeight).toInt()
            paint.color = palette[7]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            tmpRect.set(x, y, (x + cellWidth).toInt(), (y + cellHeight).toInt())
            canvas.drawRect(tmpRect, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
