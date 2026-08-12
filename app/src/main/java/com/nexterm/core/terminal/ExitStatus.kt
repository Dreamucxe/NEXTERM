package com.nexterm.core.terminal

/**
 * A finished session's status, translated into something the user can act on.
 *
 * @param headline one line, always shown
 * @param explanation what the number actually means, when the number alone misleads
 * @param failed whether this should read as a failure rather than a clean exit
 */
data class ExitDescription(
    val headline: String,
    val explanation: String? = null,
    val failed: Boolean,
)

/**
 * Decodes the wait status of a finished session.
 *
 * A bare number is rarely self-explanatory, and three cases matter enough to name.
 * Termux's `JNI.waitFor` reports a child that was killed as `-signal` rather than as
 * a status. Shells report the same thing as `128 + signal`. And proot reports 255
 * when the process it was tracing never recorded a normal exit at all — which is the
 * one case where the number actively misleads, because it looks like the guest chose
 * to fail. Showing "status 255" and stopping there is what makes a failure
 * undebuggable, which is exactly what this exists to prevent.
 */
object ExitStatus {

    fun describe(status: Int, kind: SessionKind = SessionKind.LOCAL): ExitDescription = when {
        status == 0 -> ExitDescription("Session ended.", failed = false)

        // The engine negates the signal number for a child that was killed rather
        // than one that exited, so a negative status is never a real exit code.
        status < 0 -> signalDeath(-status)

        // The shell convention for the same thing, used when a shell reports on
        // behalf of a child it waited for itself.
        status in 129..(128 + MAX_SIGNAL) -> signalDeath(status - 128, viaShell = true)

        status == 126 -> ExitDescription(
            headline = "The program was found but could not be run (status 126).",
            explanation = "Usually a missing execute permission, or a #! line " +
                "pointing at an interpreter that is not installed.",
            failed = true,
        )

        status == 127 -> ExitDescription(
            headline = "The program was not found (status 127).",
            explanation = "The shell could not resolve the command on its PATH.",
            failed = true,
        )

        status == PROOT_NO_STATUS && kind == SessionKind.PROOT -> ExitDescription(
            headline = "proot exited with status 255 without reporting the guest's status.",
            explanation = "255 is proot's uninitialised status. It means the process " +
                "proot was tracing died on a signal instead of exiting, and proot " +
                "prints nothing of its own in that path — so this is not the guest " +
                "choosing to fail, and it is not a proot error either: proot's own " +
                "errors exit 1 with a message. In practice the signal is SIGSYS (the " +
                "app sandbox's seccomp filter rejecting a system call) or SIGSEGV (the " +
                "guest crashing as it is exec'd). NEXTERM checks a minimal launch in " +
                "both of proot's interception modes before opening a session, so the " +
                "mode itself already worked once — which points at this session's own " +
                "program rather than at the setup. Output captured above this banner is " +
                "the guest's own and is the better clue.",
            failed = true,
        )

        status == PROOT_NO_STATUS -> ExitDescription(
            headline = "Session ended with status 255.",
            explanation = "255 is often how a program reports a failure it has no " +
                "specific code for.",
            failed = true,
        )

        else -> ExitDescription("Session ended with status $status.", failed = true)
    }

    private fun signalDeath(signal: Int, viaShell: Boolean = false): ExitDescription {
        val named = SIGNAL_NAMES[signal]?.let { "$it (signal $signal)" } ?: "signal $signal"
        val cause = when (signal) {
            SIGSYS -> "SIGSYS means a seccomp filter rejected a system call. Android " +
                "installs one on every app process, so a guest binary that issues a " +
                "call the sandbox does not permit is killed outright."

            SIGSEGV, SIGBUS, SIGILL -> "The program crashed. A guest binary that " +
                "crashes immediately usually does not match this device's CPU."

            SIGKILL -> "Something outside the session ended it, usually Android " +
                "reclaiming memory."

            else -> null
        }
        val shellNote = "The shell reported this as 128 + $signal.".takeIf { viaShell }
        return ExitDescription(
            headline = "Session was killed by $named.",
            explanation = listOfNotNull(cause, shellNote).joinToString(" ").ifBlank { null },
            failed = true,
        )
    }

    /** proot's `last_exit_status` starts at -1, which a process reports as 255. */
    private const val PROOT_NO_STATUS = 255

    /** Highest signal number a `128 + N` status can encode. */
    private const val MAX_SIGNAL = 64

    private const val SIGILL = 4
    private const val SIGBUS = 7
    private const val SIGKILL = 9
    private const val SIGSEGV = 11
    private const val SIGSYS = 31

    private val SIGNAL_NAMES = mapOf(
        1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", SIGILL to "SIGILL",
        5 to "SIGTRAP", 6 to "SIGABRT", SIGBUS to "SIGBUS", 8 to "SIGFPE",
        SIGKILL to "SIGKILL", 10 to "SIGUSR1", SIGSEGV to "SIGSEGV",
        12 to "SIGUSR2", 13 to "SIGPIPE", 14 to "SIGALRM", 15 to "SIGTERM",
        17 to "SIGCHLD", 19 to "SIGSTOP", 24 to "SIGXCPU", 25 to "SIGXFSZ",
        SIGSYS to "SIGSYS",
    )
}
