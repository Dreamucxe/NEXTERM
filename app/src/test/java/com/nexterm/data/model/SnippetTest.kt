package com.nexterm.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the snippet template language.
 *
 * A snippet is typed into a live shell, so the rules that matter are the ones that
 * decide what text a user ends up about to execute: which `$WORDS` are treated as
 * fillable, and what happens to the ones that are left blank.
 */
class SnippetTest {

    private fun snippet(template: String) =
        Snippet(id = 1, name = "s", template = template, description = null, position = 0)

    @Test
    fun `finds placeholders in first-appearance order`() {
        val s = snippet("ssh \$USER@\$HOST -p \$PORT")

        assertEquals(listOf("USER", "HOST", "PORT"), s.placeholders)
    }

    @Test
    fun `lists a repeated placeholder once`() {
        val s = snippet("cp \$FILE \$FILE.bak")

        assertEquals(listOf("FILE"), s.placeholders)
    }

    @Test
    fun `ignores lowercase shell variables so real commands survive intact`() {
        // $HOME and $PWD would be expanded by the shell itself; $path is not a
        // placeholder either, because the convention is uppercase-only.
        val s = snippet("cd \$HOME && echo \$path && ls \$DIR")

        assertEquals(listOf("HOME", "DIR"), s.placeholders)
    }

    @Test
    fun `treats digits and underscores as part of a placeholder name`() {
        val s = snippet("tar -xf \$FILE_1 -C \$DIR2")

        assertEquals(listOf("FILE_1", "DIR2"), s.placeholders)
    }

    @Test
    fun `does not treat a name starting with a digit as a placeholder`() {
        val s = snippet("echo \$1 \$2")

        assertTrue(s.placeholders.isEmpty())
    }

    @Test
    fun `has no placeholders when the template is a plain command`() {
        assertTrue(snippet("git status").placeholders.isEmpty())
    }

    @Test
    fun `renders every supplied value`() {
        val s = snippet("ssh \$USER@\$HOST -p \$PORT")

        val rendered = s.render(mapOf("USER" to "leo", "HOST" to "example.com", "PORT" to "2222"))

        assertEquals("ssh leo@example.com -p 2222", rendered)
    }

    @Test
    fun `renders a repeated placeholder at every occurrence`() {
        val s = snippet("cp \$FILE \$FILE.bak")

        assertEquals("cp notes.txt notes.txt.bak", s.render(mapOf("FILE" to "notes.txt")))
    }

    @Test
    fun `leaves an unfilled placeholder visible rather than emptying it`() {
        // An empty substitution would silently produce `ssh @ -p `, which looks like a
        // finished command. Leaving the token in place makes the gap obvious.
        val s = snippet("ssh \$USER@\$HOST")

        assertEquals("ssh leo@\$HOST", s.render(mapOf("USER" to "leo")))
    }

    @Test
    fun `an empty string is a real answer and is substituted`() {
        val s = snippet("grep \$PATTERN file")

        assertEquals("grep  file", s.render(mapOf("PATTERN" to "")))
    }

    @Test
    fun `ignores values for placeholders the template does not contain`() {
        val s = snippet("uptime")

        assertEquals("uptime", s.render(mapOf("UNUSED" to "x")))
    }

    @Test
    fun `does not expand a value that itself looks like a placeholder`() {
        val s = snippet("echo \$A")

        // One pass only: the substituted text is data, not another template.
        assertEquals("echo \$B", s.render(mapOf("A" to "\$B", "B" to "surprise")))
    }

    @Test
    fun `render with no values returns the template unchanged`() {
        val template = "docker run -it \$IMAGE"

        assertEquals(template, snippet(template).render(emptyMap()))
    }
}

/**
 * [TerminalTheme] carries an [IntArray], which breaks the equality a data class
 * would generate. The overrides are hand-written, so they are worth checking: theme
 * identity decides whether the renderer rebuilds its palette.
 */
class TerminalThemeTest {

    private val theme = TerminalTheme.FALLBACK

    @Test
    fun `two themes with the same colours are equal`() {
        assertEquals(theme, theme.copy(ansi = theme.ansi.copyOf()))
    }

    @Test
    fun `a changed ansi colour makes a theme unequal`() {
        val changed = theme.ansi.copyOf().also { it[3] = 0xFF123456.toInt() }

        assertNotEquals(theme, theme.copy(ansi = changed))
    }

    @Test
    fun `a changed background makes a theme unequal`() {
        assertNotEquals(theme, theme.copy(background = 0xFF010203.toInt()))
    }

    @Test
    fun `equal themes agree on hash code`() {
        assertEquals(theme.hashCode(), theme.copy(ansi = theme.ansi.copyOf()).hashCode())
    }

    @Test
    fun `the fallback theme is complete enough to render with`() {
        assertEquals(16, theme.ansi.size)
        assertNotEquals(theme.background, theme.foreground)
        assertTrue(theme.isBuiltIn)
    }

    @Test
    fun `every fallback colour is opaque`() {
        val colors = theme.ansi.toMutableList().apply {
            add(theme.background); add(theme.foreground); add(theme.cursor); add(theme.accent)
        }
        colors.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }
}

/** The `$NAME` scan also decides which snippets the palette can run without a dialog. */
class SnippetPaletteEligibilityTest {

    @Test
    fun `a snippet with no placeholders can be run straight from the palette`() {
        val direct = Snippet(1, "uptime", "uptime -p", null, 0)

        assertTrue(direct.placeholders.isEmpty())
    }

    @Test
    fun `a snippet with placeholders must go through the fill dialog`() {
        val needsInput = Snippet(2, "ssh", "ssh \$USER@\$HOST", null, 1)

        assertFalse(needsInput.placeholders.isEmpty())
    }
}
