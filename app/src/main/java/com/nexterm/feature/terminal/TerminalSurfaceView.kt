package com.nexterm.feature.terminal

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The terminal's pixels and touch handling.
 *
 * A `View` rather than a Compose `Canvas` because three things a terminal cannot do
 * without are all View-level concerns: an [android.view.inputmethod.InputConnection]
 * for soft-keyboard text, [OverScroller] for fling through scrollback, and
 * [ScaleGestureDetector] for pinch-to-resize. Drawing lives in [TerminalRenderer],
 * which this class only calls.
 *
 * The screen is *not* copied into Compose state. `TerminalEmulator` mutates its own
 * buffer on the reader thread, and a session producing output at full speed would
 * turn every frame into a recomposition. Instead the session's revision counter
 * triggers [invalidate], so fast output costs a redraw and nothing more.
 */
class TerminalSurfaceView(context: Context) : TerminalImeView(context) {

    var renderer: TerminalRenderer? = null
        set(value) {
            field = value
            reportGrid()
            invalidate()
        }

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            topRow = 0
            clearSelection()
            reportGrid()
            invalidate()
        }

    var palette: IntArray = IntArray(TextStyle.NUM_INDEXED_COLORS)
        set(value) {
            field = value
            invalidate()
        }

    /** Drawn cursor colour, already resolved from the active theme. */
    var cursorColor: Int = 0xFFFFFFFF.toInt()

    /** One of `TerminalEmulator.TERMINAL_CURSOR_STYLE_*`. */
    var cursorStyle: Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    /** False while a blinking cursor is in its off phase. */
    var cursorVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Reports the grid the current size can hold, so the PTY can be resized. */
    var onGridChanged: ((columns: Int, rows: Int) -> Unit)? = null

    /** Pinch-to-zoom, as a multiplier to apply to the configured font size. */
    var onFontScale: ((Float) -> Unit)? = null

    /** Fired with the selected text, or null when the selection is dropped. */
    var onSelectionChanged: ((String?) -> Unit)? = null

    /** A tap on the terminal body; the host uses it to raise the keyboard. */
    var onTapped: (() -> Unit)? = null

    /** Long press with no active selection: the host opens the context menu. */
    var onContextMenu: (() -> Unit)? = null

    /** Rows scrolled above the live screen. 0 is the bottom; negative reaches history. */
    private var topRow = 0

    private var selection: IntArray? = null
    private var selecting = false
    private var columns = 0
    private var rows = 0

    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** True when the view is showing history rather than the live bottom of the screen. */
    val isScrolledBack: Boolean get() = topRow != 0

    /** Snaps back to the live screen, which output should always do. */
    fun scrollToBottom() {
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    fun clearSelection() {
        if (selection != null || selecting) {
            selection = null
            selecting = false
            onSelectionChanged?.invoke(null)
            invalidate()
        }
    }

    /** The currently selected text, or null. */
    fun selectedText(): String? {
        val bounds = selection ?: return null
        val screen = emulator?.screen ?: return null
        return runCatching {
            screen.getSelectedText(bounds[0], bounds[1], bounds[2], bounds[3])
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Selects the word under a point, which is what a long press should do first. */
    fun selectWordAt(x: Float, y: Float) {
        val activeRenderer = renderer ?: return
        val activeEmulator = emulator ?: return
        val column = (x / activeRenderer.cellWidth).toInt()
            .coerceIn(0, (activeEmulator.mColumns - 1).coerceAtLeast(0))
        val row = topRow + (y / activeRenderer.cellHeight).toInt()

        val line = runCatching { activeEmulator.screen.getSelectedText(0, row, activeEmulator.mColumns, row) }
            .getOrNull() ?: return
        if (line.isEmpty()) return

        val clamped = column.coerceAtMost(line.length - 1)
        if (line[clamped].isWhitespace()) return
        var start = clamped
        var end = clamped
        while (start > 0 && !line[start - 1].isWhitespace()) start--
        while (end < line.length - 1 && !line[end + 1].isWhitespace()) end++

        selection = intArrayOf(start, row, end, row)
        selecting = true
        onSelectionChanged?.invoke(selectedText())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
    }

    /**
     * Recomputes the grid and tells the host only when it actually changed, because
     * every report becomes a `TIOCSWINSZ` and a `SIGWINCH` in the child process.
     */
    private fun reportGrid() {
        val activeRenderer = renderer ?: return
        if (width <= 0 || height <= 0) return
        val newColumns = activeRenderer.columnsFor(width.toFloat())
        val newRows = activeRenderer.rowsFor(height.toFloat())
        if (newColumns != columns || newRows != rows) {
            columns = newColumns
            rows = newRows
            onGridChanged?.invoke(newColumns, newRows)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val activeRenderer = renderer ?: return
        val activeEmulator = emulator ?: return
        // Clamp here rather than at scroll time: the transcript shrinks as it fills,
        // so a position that was valid a moment ago may no longer be.
        topRow = topRow.coerceIn(-activeEmulator.screen.activeTranscriptRows, 0)
        activeRenderer.render(
            canvas = canvas,
            emulator = activeEmulator,
            palette = palette,
            topRow = topRow,
            selection = selection,
            cursorVisible = cursorVisible && topRow == 0,
            cursorColor = cursorColor,
            cursorStyle = cursorStyle,
        )
    }

    // ---- Touch ----

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                scrollRemainder = 0f
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selection != null) {
                    clearSelection()
                } else {
                    onTapped?.invoke()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (selection == null) {
                    selectWordAt(e.x, e.y)
                    if (selection == null) onContextMenu?.invoke()
                } else {
                    onContextMenu?.invoke()
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (selecting) {
                    extendSelectionTo(e2.x, e2.y)
                    return true
                }
                scrollByPixels(distanceY)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (selecting) return false
                val activeEmulator = emulator ?: return false
                val cellHeight = renderer?.cellHeight ?: return false
                val history = activeEmulator.screen.activeTranscriptRows
                scroller.fling(
                    0, topRow * cellHeight,
                    0, -velocityY.toInt(),
                    0, 0,
                    -history * cellHeight, 0,
                )
                postInvalidateOnAnimation()
                return true
            }
        },
    )

    /**
     * Pinch changes the font size rather than applying a canvas scale: a terminal
     * scaled as a bitmap is a blurry terminal, and the grid has to be re-measured
     * anyway so the shell learns its new width.
     */
    private val scaleGestures = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Ignore the jitter that comes from two fingers merely resting.
                if (abs(detector.scaleFactor - 1f) < 0.01f) return false
                onFontScale?.invoke(detector.scaleFactor)
                return true
            }
        },
    )

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestures.onTouchEvent(event)
        // A second finger means a pinch, so no scrolling or selection should follow.
        if (scaleGestures.isInProgress) {
            return true
        }
        gestures.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (selecting) {
                selecting = false
                onSelectionChanged?.invoke(selectedText())
            }
        }
        return true
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val cellHeight = renderer?.cellHeight ?: return
        val newTop = (scroller.currY.toFloat() / cellHeight).roundToInt()
        if (newTop != topRow) {
            topRow = newTop
            invalidate()
        }
        postInvalidateOnAnimation()
    }

    /**
     * Scrolls by whole rows, keeping the sub-row remainder so a slow drag still moves
     * the view instead of rounding to zero on every event.
     */
    private fun scrollByPixels(distanceY: Float) {
        val cellHeight = renderer?.cellHeight ?: return
        val activeEmulator = emulator ?: return
        scrollRemainder += distanceY
        val wholeRows = (scrollRemainder / cellHeight).toInt()
        if (wholeRows == 0) return
        scrollRemainder -= wholeRows * cellHeight

        // Dragging up (positive distanceY) reveals older output, which is upward in
        // the emulator's coordinates, hence the sign flip.
        val history = activeEmulator.screen.activeTranscriptRows
        val newTop = (topRow - wholeRows).coerceIn(-history, 0)
        if (newTop != topRow) {
            topRow = newTop
            invalidate()
        }
    }

    private fun extendSelectionTo(x: Float, y: Float) {
        val activeRenderer = renderer ?: return
        val activeEmulator = emulator ?: return
        val bounds = selection ?: return
        val column = (x / activeRenderer.cellWidth).toInt()
            .coerceIn(0, (activeEmulator.mColumns - 1).coerceAtLeast(0))
        val row = topRow + (y / activeRenderer.cellHeight).toInt()
        selection = intArrayOf(bounds[0], bounds[1], column, row)
        invalidate()
    }
}

