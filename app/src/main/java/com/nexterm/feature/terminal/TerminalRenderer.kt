package com.nexterm.feature.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.nexterm.core.terminal.TerminalPalette
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlin.math.ceil

/**
 * Draws a [TerminalEmulator]'s screen onto a canvas.
 *
 * This is a real cell renderer, not a text view: it walks the emulator's row buffer,
 * groups adjacent cells that share a style into runs, and issues one `drawText` per
 * run. That is what keeps a full-screen redraw cheap enough for `top`, `htop` or a
 * scrolling build log at 60fps — a per-character draw call would not be.
 *
 * It deliberately handles the awkward parts of a terminal grid rather than
 * approximating them:
 *  - **wide characters** (CJK, many emoji) occupy two columns, via `wcwidth`;
 *  - **combining marks** have zero width and must be drawn with the base character;
 *  - **surrogate pairs** are two `char`s but one code point;
 *  - a glyph whose measured width does not match the cell grid is drawn on its own
 *    so it cannot push the rest of the line out of alignment.
 *
 * Kept out of the composable layer on purpose: it holds mutable `Paint` state and
 * does no state management, so it can be reused across recompositions.
 */
class TerminalRenderer(
    val textSizePx: Float,
    typeface: Typeface,
    lineSpacingMultiplier: Float = 1f,
) {
    private val textPaint = Paint().apply {
        this.typeface = typeface
        isAntiAlias = true
        textSize = textSizePx
    }

    private val fillPaint = Paint()

    /** Width of one cell. Monospace, so any character measures the same. */
    val cellWidth: Float = textPaint.measureText("W")

    private val fontMetrics = textPaint.fontMetricsInt

    /** Distance between successive baselines. */
    val cellHeight: Int =
        ceil((fontMetrics.descent - fontMetrics.ascent) * lineSpacingMultiplier.coerceAtLeast(0.5f))
            .toInt()
            .coerceAtLeast(1)

    /** Offset from a row's top edge to its text baseline. */
    private val baselineOffset: Int = cellHeight - fontMetrics.descent

    /** Columns that fit in [widthPx]. Always at least one, so layout never divides by zero. */
    fun columnsFor(widthPx: Float): Int =
        if (cellWidth <= 0f) 1 else (widthPx / cellWidth).toInt().coerceAtLeast(1)

    fun rowsFor(heightPx: Float): Int = (heightPx / cellHeight).toInt().coerceAtLeast(1)

    /**
     * Renders the visible portion of [emulator].
     *
     * @param topRow first row to draw, in the emulator's external coordinates.
     *   Zero is the top of the screen; negative values reach into the scrollback.
     * @param palette 259-entry colour table from [TerminalPalette].
     * @param selection `[startCol, startRow, endCol, endRow]` or null.
     */
    fun render(
        canvas: Canvas,
        emulator: TerminalEmulator,
        palette: IntArray,
        topRow: Int,
        selection: IntArray?,
        cursorVisible: Boolean,
        cursorColor: Int,
        cursorStyle: Int,
    ) {
        val screen = emulator.screen
        val columns = emulator.mColumns
        val rows = emulator.mRows
        val reverseVideo = emulator.isReverseVideo

        val cursorRow = if (cursorVisible) emulator.cursorRow else -1
        val cursorCol = emulator.cursorCol

        var y = 0f
        for (screenRow in 0 until rows) {
            val row = topRow + screenRow
            val rowTop = y
            y += cellHeight

            val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val cursorColForRow = if (row == cursorRow) cursorCol else -1
            val (selectionStart, selectionEnd) = selectionRangeFor(selection, row, columns)

            drawRow(
                canvas = canvas,
                line = line,
                columns = columns,
                topPx = rowTop,
                palette = palette,
                reverseVideo = reverseVideo,
                cursorColumn = cursorColForRow,
                cursorColor = cursorColor,
                cursorStyle = cursorStyle,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                selectionColor = palette[TextStyle.COLOR_INDEX_CURSOR],
            )
        }
    }

    /** Columns of [row] covered by [selection], or `-1..-1` when none are. */
    private fun selectionRangeFor(selection: IntArray?, row: Int, columns: Int): Pair<Int, Int> {
        if (selection == null) return -1 to -1
        val startColumn = selection[0]
        val startRow = selection[1]
        val endColumn = selection[2]
        val endRow = selection[3]
        if (row < startRow || row > endRow) return -1 to -1
        val start = if (row == startRow) startColumn else 0
        val end = if (row == endRow) endColumn else columns - 1
        return start to end
    }

    private fun drawRow(
        canvas: Canvas,
        line: TerminalRow,
        columns: Int,
        topPx: Float,
        palette: IntArray,
        reverseVideo: Boolean,
        cursorColumn: Int,
        cursorColor: Int,
        cursorStyle: Int,
        selectionStart: Int,
        selectionEnd: Int,
        selectionColor: Int,
    ) {
        val text = line.mText
        val charsUsed = line.spaceUsed

        var runStyle = 0L
        var runInsideCursor = false
        var runInsideSelection = false
        var runStartColumn = -1
        var runStartIndex = 0
        var runWidthMismatch = false
        var runMeasuredWidth = 0f

        var charIndex = 0
        var column = 0

        while (column < columns) {
            val char = if (charIndex < charsUsed) text[charIndex] else ' '
            val isHighSurrogate = Character.isHighSurrogate(char) && charIndex + 1 < charsUsed
            val charCount = if (isHighSurrogate) 2 else 1
            val codePoint = if (isHighSurrogate) {
                Character.toCodePoint(char, text[charIndex + 1])
            } else {
                char.code
            }

            val codePointWidth = WcWidth.width(codePoint)
            // A zero-width mark belongs to the column already drawn, so it must not
            // start a new run — otherwise accents detach from their base letter.
            val insideCursor = column == cursorColumn ||
                (cursorColumn >= 0 && codePointWidth == 2 && column + 1 == cursorColumn)
            val insideSelection = selectionStart >= 0 &&
                column >= selectionStart && column <= selectionEnd
            val style = line.getStyle(column)

            // Glyphs that do not measure exactly one (or two) cells get their own run
            // so the surrounding text stays on the grid.
            val measured = if (charIndex < charsUsed) {
                textPaint.measureText(text, charIndex, charCount)
            } else {
                cellWidth
            }
            val expected = codePointWidth * cellWidth
            val widthMismatch = codePointWidth > 0 && kotlin.math.abs(measured - expected) > 0.01f

            val startsNewRun = column == 0 ||
                style != runStyle ||
                insideCursor != runInsideCursor ||
                insideSelection != runInsideSelection ||
                widthMismatch || runWidthMismatch

            if (codePointWidth > 0 && startsNewRun) {
                if (runStartColumn != -1) {
                    drawRun(
                        canvas, text, runStartIndex, charIndex - runStartIndex,
                        runStartColumn, column - runStartColumn, runMeasuredWidth, topPx,
                        runStyle, palette, reverseVideo, runInsideCursor, cursorColor,
                        cursorStyle, runInsideSelection, selectionColor,
                    )
                }
                runStartColumn = column
                runStartIndex = charIndex
                runStyle = style
                runInsideCursor = insideCursor
                runInsideSelection = insideSelection
                runWidthMismatch = widthMismatch
                runMeasuredWidth = 0f
            }

            runMeasuredWidth += measured
            // Past the row's used length there is nothing stored to draw, so the
            // character index stays put and only the column advances.
            if (charIndex < charsUsed) charIndex += charCount
            column += codePointWidth.coerceAtLeast(0)

            // Consume any zero-width marks that follow, so they draw with this run.
            while (charIndex < charsUsed && WcWidth.width(
                    if (Character.isHighSurrogate(text[charIndex]) && charIndex + 1 < charsUsed) {
                        Character.toCodePoint(text[charIndex], text[charIndex + 1])
                    } else {
                        text[charIndex].code
                    },
                ) == 0
            ) {
                charIndex += if (Character.isHighSurrogate(text[charIndex])) 2 else 1
            }
        }

        if (runStartColumn != -1) {
            drawRun(
                canvas, text, runStartIndex, charIndex - runStartIndex,
                runStartColumn, columns - runStartColumn, runMeasuredWidth, topPx,
                runStyle, palette, reverseVideo, runInsideCursor, cursorColor,
                cursorStyle, runInsideSelection, selectionColor,
            )
        }
    }

    private fun drawRun(
        canvas: Canvas,
        text: CharArray,
        textStart: Int,
        textCount: Int,
        startColumn: Int,
        columnWidth: Int,
        measuredWidth: Float,
        topPx: Float,
        style: Long,
        palette: IntArray,
        reverseVideo: Boolean,
        insideCursor: Boolean,
        cursorColor: Int,
        cursorStyle: Int,
        insideSelection: Boolean,
        selectionColor: Int,
    ) {
        if (columnWidth <= 0) return

        var foreEncoded = TextStyle.decodeForeColor(style)
        var backEncoded = TextStyle.decodeBackColor(style)
        val effect = TextStyle.decodeEffect(style)

        val bold = effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        val italic = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0
        val underline = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        val strikethrough = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        val dim = effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0
        val invisible = effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0
        var inverse = effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0

        // Bold brightens the low 8 ANSI colours to their bright counterparts, which
        // is what makes `ls` output look the way users expect.
        if (bold && foreEncoded in 0..7) foreEncoded += 8

        var foreground = TerminalPalette.resolve(foreEncoded, palette)
        var background = TerminalPalette.resolve(backEncoded, palette)

        if (reverseVideo) inverse = !inverse
        if (inverse) {
            val swap = foreground
            foreground = background
            background = swap
        }

        val left = startColumn * cellWidth
        val right = left + columnWidth * cellWidth

        if (insideSelection) {
            background = blend(background, selectionColor, 0.45f)
        }

        // Only paint the cell background when it differs from the screen default;
        // the surface below already covers the default, so this avoids overdraw.
        if (background != palette[TextStyle.COLOR_INDEX_BACKGROUND] || insideCursor) {
            val bottom = topPx + cellHeight
            when {
                !insideCursor -> {
                    fillPaint.color = background
                    canvas.drawRect(left, topPx, right, bottom, fillPaint)
                }

                cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK -> {
                    fillPaint.color = cursorColor
                    canvas.drawRect(left, topPx, right, bottom, fillPaint)
                }

                else -> {
                    // Underline and bar cursors sit on top of a normal cell.
                    fillPaint.color = background
                    canvas.drawRect(left, topPx, right, bottom, fillPaint)
                    fillPaint.color = cursorColor
                    if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                        canvas.drawRect(left, bottom - CURSOR_THICKNESS_PX, right, bottom, fillPaint)
                    } else {
                        canvas.drawRect(left, topPx, left + CURSOR_THICKNESS_PX, bottom, fillPaint)
                    }
                }
            }
        }

        if (invisible || textCount <= 0) return

        // A block cursor inverts the glyph under it so the character stays readable.
        if (insideCursor && cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            foreground = background
        }

        textPaint.color = if (dim) blend(foreground, background, 0.4f) else foreground
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = if (italic) ITALIC_SKEW else 0f
        textPaint.isUnderlineText = underline
        textPaint.isStrikeThruText = strikethrough

        canvas.drawText(text, textStart, textCount, left, topPx + baselineOffset, textPaint)

        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false
        textPaint.textSkewX = 0f
        textPaint.isFakeBoldText = false
    }

    /** Linear blend of two ARGB colours; [amount] 0 keeps [from], 1 gives [to]. */
    private fun blend(from: Int, to: Int, amount: Float): Int {
        val inverse = 1f - amount
        val r = ((from shr 16 and 0xFF) * inverse + (to shr 16 and 0xFF) * amount).toInt()
        val g = ((from shr 8 and 0xFF) * inverse + (to shr 8 and 0xFF) * amount).toInt()
        val b = ((from and 0xFF) * inverse + (to and 0xFF) * amount).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val ITALIC_SKEW = -0.35f
        const val CURSOR_THICKNESS_PX = 3f
    }
}
