package com.nexterm.core.terminal

import com.nexterm.data.model.TerminalTheme
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle

/**
 * Builds the 259-entry colour table the emulator resolves indexed colours against.
 *
 * Indices 0..15 are the theme's ANSI colours, 16..231 the xterm 6×6×6 colour cube,
 * 232..255 the 24-step greyscale ramp, and 256..258 the default foreground,
 * background and cursor. Generating 16..255 arithmetically (rather than storing 256
 * colours per theme) is exactly what xterm specifies, so `ls --color`, vim colour
 * schemes and 256-colour TUIs all land on the colours their authors intended.
 */
object TerminalPalette {

    const val SIZE = TextStyle.NUM_INDEXED_COLORS

    fun build(theme: TerminalTheme): IntArray {
        val colors = IntArray(SIZE)

        for (i in 0 until 16) colors[i] = theme.ansi[i]

        // 16..231: 6 levels per channel. The xterm ramp is not linear — it jumps
        // from 0 to 95 and then rises in steps of 40.
        var index = 16
        for (red in 0 until 6) {
            for (green in 0 until 6) {
                for (blue in 0 until 6) {
                    colors[index++] = argb(cubeLevel(red), cubeLevel(green), cubeLevel(blue))
                }
            }
        }

        // 232..255: greyscale from #080808 to #EEEEEE in steps of 10.
        for (step in 0 until 24) {
            val value = 8 + step * 10
            colors[index++] = argb(value, value, value)
        }

        colors[TextStyle.COLOR_INDEX_FOREGROUND] = theme.foreground
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = theme.background
        colors[TextStyle.COLOR_INDEX_CURSOR] = theme.cursor
        return colors
    }

    /**
     * Pushes a palette into a live emulator.
     *
     * Copied in place rather than replaced, because the emulator holds a final
     * reference to the array and OSC 4 sequences write into the same one — so a
     * program that sets its own colours keeps working after a theme change.
     */
    fun applyTo(emulator: TerminalEmulator, palette: IntArray) {
        val target = emulator.mColors.mCurrentColors
        System.arraycopy(palette, 0, target, 0, minOf(palette.size, target.size))
    }

    /**
     * Resolves one encoded colour from a cell style to ARGB.
     *
     * Truecolour values arrive with the alpha byte already set to 0xFF; anything
     * else is an index into [palette].
     */
    fun resolve(encoded: Int, palette: IntArray): Int =
        if (encoded and ALPHA_MASK == ALPHA_MASK) {
            encoded
        } else {
            palette.getOrElse(encoded) { palette[TextStyle.COLOR_INDEX_FOREGROUND] }
        }

    private fun cubeLevel(step: Int) = if (step == 0) 0 else 55 + step * 40

    private fun argb(r: Int, g: Int, b: Int) =
        ALPHA_MASK or (r shl 16) or (g shl 8) or b

    private const val ALPHA_MASK = 0xFF000000.toInt()
}
