package com.nexterm.core.terminal

import com.nexterm.data.model.TerminalTheme
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for the indexed-colour table.
 *
 * The values asserted here are the xterm specification's, not this implementation's:
 * if the cube ramp or the greyscale steps drift, every 256-colour TUI renders in
 * subtly wrong colours and nothing else in the app would notice.
 */
class TerminalPaletteTest {

    private val theme = TerminalTheme.FALLBACK
    private val palette = TerminalPalette.build(theme)

    @Test
    fun `has an entry for every index the emulator can ask for`() {
        assertEquals(TextStyle.NUM_INDEXED_COLORS, palette.size)
    }

    @Test
    fun `the first sixteen entries are the theme's own ANSI colours`() {
        for (i in 0 until 16) {
            assertEquals("index $i", theme.ansi[i], palette[i])
        }
    }

    @Test
    fun `cube index 16 is black and index 231 is white`() {
        assertEquals(0xFF000000.toInt(), palette[16])
        assertEquals(0xFFFFFFFF.toInt(), palette[231])
    }

    @Test
    fun `the cube ramp jumps to 95 then rises in steps of 40`() {
        // Index 16 + 36*r + 6*g + b. Walking red with green and blue at zero gives
        // the ramp directly: 0, 95, 135, 175, 215, 255.
        val expected = listOf(0, 95, 135, 175, 215, 255)
        expected.forEachIndexed { step, level ->
            val entry = palette[16 + 36 * step]
            assertEquals("red step $step", level, (entry shr 16) and 0xFF)
            assertEquals("green must stay 0", 0, (entry shr 8) and 0xFF)
            assertEquals("blue must stay 0", 0, entry and 0xFF)
        }
    }

    @Test
    fun `cube channels are ordered red then green then blue`() {
        // 16 + 6 is green level 1 with red and blue at zero.
        val green = palette[16 + 6]
        assertEquals(0, (green shr 16) and 0xFF)
        assertEquals(95, (green shr 8) and 0xFF)
        assertEquals(0, green and 0xFF)

        // 16 + 1 is blue level 1.
        val blue = palette[17]
        assertEquals(0, (blue shr 16) and 0xFF)
        assertEquals(0, (blue shr 8) and 0xFF)
        assertEquals(95, blue and 0xFF)
    }

    @Test
    fun `greyscale runs from 0x08 to 0xEE in steps of 10`() {
        assertEquals(0xFF080808.toInt(), palette[232])
        assertEquals(0xFFEEEEEE.toInt(), palette[255])
        for (step in 0 until 24) {
            val value = 8 + step * 10
            assertEquals("grey step $step", argb(value, value, value), palette[232 + step])
        }
    }

    @Test
    fun `every entry is fully opaque`() {
        palette.forEachIndexed { index, color ->
            assertEquals("index $index has a non-opaque alpha", 0xFF, (color ushr 24) and 0xFF)
        }
    }

    @Test
    fun `the default foreground background and cursor come from the theme`() {
        assertEquals(theme.foreground, palette[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(theme.background, palette[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(theme.cursor, palette[TextStyle.COLOR_INDEX_CURSOR])
    }

    @Test
    fun `resolve passes a truecolour value straight through`() {
        val truecolour = 0xFF3B82F6.toInt()
        assertEquals(truecolour, TerminalPalette.resolve(truecolour, palette))
    }

    @Test
    fun `resolve maps an index to its palette entry`() {
        assertEquals(palette[9], TerminalPalette.resolve(9, palette))
        assertEquals(palette[196], TerminalPalette.resolve(196, palette))
    }

    @Test
    fun `resolve falls back to the foreground for an out-of-range index`() {
        val foreground = palette[TextStyle.COLOR_INDEX_FOREGROUND]
        assertEquals(foreground, TerminalPalette.resolve(9999, palette))
        assertEquals(foreground, TerminalPalette.resolve(TerminalPalette.SIZE, palette))
    }

    @Test
    fun `a negative value is truecolour, not an index`() {
        // Encoded colours are ints: anything with the top byte set is truecolour, so
        // a "negative" index is really 0xFFRRGGBB and must pass through untouched.
        // It can never be a real index, and reading it as one would resolve garbage.
        assertEquals(-3, TerminalPalette.resolve(-3, palette))
    }

    @Test
    fun `two different themes produce different tables`() {
        val other = theme.copy(
            id = 2,
            name = "other",
            background = 0xFF102030.toInt(),
            foreground = 0xFFEEEEEE.toInt(),
        )

        val otherPalette = TerminalPalette.build(other)

        assertNotEquals(
            palette[TextStyle.COLOR_INDEX_BACKGROUND],
            otherPalette[TextStyle.COLOR_INDEX_BACKGROUND],
        )
        // The 216-colour cube is fixed by the spec and must not follow the theme.
        assertEquals(palette[100], otherPalette[100])
    }

    private fun argb(r: Int, g: Int, b: Int) = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}
