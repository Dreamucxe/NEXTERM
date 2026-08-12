package com.nexterm.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit-status decoder is the difference between a debuggable failure and a bare
 * number, so the cases that actually mislead are pinned here.
 */
class ExitStatusTest {

    @Test
    fun `zero is not a failure`() {
        val result = ExitStatus.describe(0)
        assertFalse(result.failed)
        assertEquals("Session ended.", result.headline)
        assertNull(result.explanation)
    }

    @Test
    fun `proot 255 is explained as a missing status rather than a guest failure`() {
        val result = ExitStatus.describe(255, SessionKind.PROOT)
        assertTrue(result.failed)
        assertTrue(result.headline.contains("without reporting"))
        // The point of the explanation is that 255 is not the guest's own choice.
        assertTrue(result.explanation!!.contains("uninitialised"))
    }

    @Test
    fun `255 outside proot does not claim proot semantics`() {
        val result = ExitStatus.describe(255, SessionKind.LOCAL)
        assertTrue(result.failed)
        assertFalse(result.headline.contains("proot"))
        assertNotNull(result.explanation)
    }

    @Test
    fun `a negative status is decoded as the signal that killed the child`() {
        val result = ExitStatus.describe(-9)
        assertTrue(result.failed)
        assertTrue(result.headline.contains("SIGKILL"))
        assertTrue(result.headline.contains("signal 9"))
    }

    @Test
    fun `SIGSYS is attributed to seccomp`() {
        val result = ExitStatus.describe(-31)
        assertTrue(result.headline.contains("SIGSYS"))
        assertTrue(result.explanation!!.contains("seccomp"))
    }

    @Test
    fun `the shell's 128 plus signal convention is decoded too`() {
        val result = ExitStatus.describe(139)
        assertTrue(result.headline.contains("SIGSEGV"))
        assertTrue(result.explanation!!.contains("128 + 11"))
    }

    @Test
    fun `126 and 127 are distinguished`() {
        assertTrue(ExitStatus.describe(126).headline.contains("could not be run"))
        assertTrue(ExitStatus.describe(127).headline.contains("was not found"))
    }

    @Test
    fun `an ordinary failure status is reported as itself`() {
        val result = ExitStatus.describe(2)
        assertTrue(result.failed)
        assertEquals("Session ended with status 2.", result.headline)
    }

    @Test
    fun `an unnamed signal still reports its number`() {
        val result = ExitStatus.describe(-63)
        assertTrue(result.headline.contains("signal 63"))
    }

    @Test
    fun `statuses above the signal range are not misread as signals`() {
        // 200 is not 128 + a plausible signal, so it must stay a plain status.
        assertEquals("Session ended with status 200.", ExitStatus.describe(200).headline)
    }
}
