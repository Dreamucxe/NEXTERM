package com.nexterm.core.terminal

import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a shell on the Android system itself through a real PTY.
 *
 * The heavy lifting is done by Termux's `terminal-emulator` library: its native
 * `libtermux.so` performs the `forkpty()` / `TIOCSWINSZ` calls, and its
 * [TerminalEmulator] implements the VT100/xterm state machine (ANSI + 256-colour +
 * truecolour, scrollback, alternate buffer, mouse reporting). NEXTERM supplies the
 * process/environment policy and the Compose renderer on top.
 *
 * Scope of a "local" session: it runs inside NEXTERM's own UID, so it sees the app
 * sandbox and the world-readable parts of the system. That is the same footing every
 * unrooted Android terminal starts from; elevation is handled by the privilege layer.
 */
class LocalPtyProvider(
    private val context: Context,
) : TerminalProvider {

    override val kind: SessionKind = SessionKind.LOCAL

    override fun availability(): ProviderAvailability =
        if (nativeLibraryLoaded) {
            ProviderAvailability.Available
        } else {
            ProviderAvailability.Unavailable(
                reason = "The terminal engine could not start.",
                detail = nativeLoadError?.toString()
                    ?: "libtermux.so is missing for this device's ABI.",
                remedy = listOf(
                    "Reinstall NEXTERM using a build that matches this device's CPU",
                ),
            )
        }

    override suspend fun createSession(
        request: SessionRequest,
        listener: TerminalListener,
    ): TerminalHandle {
        (availability() as? ProviderAvailability.Unavailable)?.let {
            throw TerminalStartException(it.reason, it.detail)
        }

        // Finding a shell and a usable cwd stats the filesystem, which blocks, so it
        // is resolved off the main thread before anything is constructed.
        val setup = withContext(Dispatchers.IO) {
            val shell = resolveShell(request.executable)
                ?: throw TerminalStartException(
                    "No usable shell was found on this device.",
                    "Tried: ${shellCandidates.joinToString(", ")}",
                )

            // A directory the child can actually chdir() into; a dead cwd makes the
            // shell exit immediately with a confusing error.
            val cwd = resolveWorkingDirectory(request.workingDirectory)

            SessionSetup(
                shell = shell,
                cwd = cwd,
                environment = buildEnvironment(request.environment, cwd, shell),
            )
        }

        // The session itself has to be built on the main thread. [TerminalSession]
        // creates an android.os.Handler in its constructor to post emulator callbacks,
        // and a Handler adopts the Looper of whichever thread creates it — a
        // Dispatchers.IO worker has none, so the constructor throws "Can't create
        // handler inside thread ... that has not called Looper.prepare()" and the tab
        // dies before the shell ever runs. forkpty() does not wait on the child, so
        // this costs nothing measurable; it is also where Termux's own service starts
        // its sessions.
        return withContext(Dispatchers.Main.immediate) {
            PtyTerminalHandle(
                request = request,
                shell = setup.shell,
                cwd = setup.cwd,
                environment = setup.environment,
                listener = listener,
            )
        }
    }

    /** Everything the session needs, resolved before leaving the IO dispatcher. */
    private data class SessionSetup(
        val shell: String,
        val cwd: String,
        val environment: Map<String, String>,
    )

    /** Picks the first shell that exists and is executable. */
    private fun resolveShell(preferred: String): String? {
        val candidates = buildList {
            if (preferred.isNotBlank()) add(preferred)
            addAll(shellCandidates)
        }
        return candidates.firstOrNull { path ->
            runCatching { File(path).let { it.exists() && it.canExecute() } }.getOrDefault(false)
        }
    }

    /** Falls back through cwd candidates until one is a readable directory. */
    private fun resolveWorkingDirectory(requested: String): String {
        val candidates = listOf(
            requested,
            context.filesDir.absolutePath,
            "/",
        )
        return candidates.firstOrNull { path ->
            runCatching { File(path).let { it.isDirectory && it.canRead() } }.getOrDefault(false)
        } ?: "/"
    }

    /**
     * Builds the child's environment.
     *
     * `TERM=xterm-256color` is what makes ncurses programs (top, nano, vim) emit the
     * escape sequences the emulator understands. HOME points at app-private storage
     * because that is the only place a local session can reliably write.
     */
    private fun buildEnvironment(
        overrides: Map<String, String>,
        cwd: String,
        shell: String,
    ): Map<String, String> {
        val home = context.filesDir.absolutePath
        val base = linkedMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to home,
            "PWD" to cwd,
            "SHELL" to shell,
            "USER" to (System.getProperty("user.name") ?: "android"),
            "LANG" to "en_US.UTF-8",
            "PATH" to systemPath(),
            "TMPDIR" to context.cacheDir.absolutePath,
            "ANDROID_DATA" to (System.getenv("ANDROID_DATA") ?: "/data"),
            "ANDROID_ROOT" to (System.getenv("ANDROID_ROOT") ?: "/system"),
            // Lets scripts detect they are inside NEXTERM.
            "NEXTERM" to "1",
            "NEXTERM_FILES" to home,
        )
        System.getenv("EXTERNAL_STORAGE")?.let { base["EXTERNAL_STORAGE"] = it }
        base.putAll(overrides)
        return base
    }

    private fun systemPath(): String {
        val existing = System.getenv("PATH").orEmpty()
        val extras = listOf("/system/bin", "/system/xbin", "/vendor/bin")
        val merged = LinkedHashSet<String>()
        existing.split(':').filter { it.isNotBlank() }.forEach(merged::add)
        extras.filter { File(it).isDirectory }.forEach(merged::add)
        return merged.joinToString(":")
    }

    companion object {
        /** Shells present on essentially every Android build, best first. */
        private val shellCandidates = listOf(
            "/system/bin/sh",
            "/system/bin/bash",
            "/bin/sh",
        )

        private var nativeLoadError: Throwable? = null

        /**
         * Loading is attempted once, eagerly, so the UI can report a clear failure
         * rather than crashing on the first session. [TerminalSession] links against
         * the same library, so if this succeeds the engine is usable.
         */
        private val nativeLibraryLoaded: Boolean by lazy {
            runCatching { System.loadLibrary("termux") }
                .onFailure { nativeLoadError = it }
                .isSuccess
        }
    }
}

/**
 * Bridges Termux's [TerminalSession] to NEXTERM's [TerminalHandle].
 *
 * The session is created eagerly in the constructor so that a failure to fork
 * surfaces immediately as an exception rather than as a silently dead tab.
 *
 * Must be constructed on the main thread — [TerminalSession] builds a [android.os.Handler]
 * and needs a Looper. Callers go through [LocalPtyProvider.createSession], which
 * arranges that; instantiating this from a background dispatcher throws.
 */
private class PtyTerminalHandle(
    request: SessionRequest,
    private val shell: String,
    cwd: String,
    environment: Map<String, String>,
    private val listener: TerminalListener,
) : TerminalHandle, EmulatorBacked {

    override val id: String = request.id
    override val kind: SessionKind = request.kind

    /**
     * The argv the child is exec'd with, program name included.
     *
     * This is also what the PTY is handed, and the program name is not optional: the
     * terminal library copies this array into `execvp`'s argv verbatim rather than
     * synthesising argv[0] from the command path. Leaving it out shifts every argument
     * down one slot, and the program being run sees its first real argument as its own
     * name — for a proot session that means `-r` lands in the program-name slot, the
     * rootfs path is read as the command to execute, and proot fails with
     * "'…/distros/ubuntu' is not a regular file". A plain shell survives the same
     * mistake (argc 0 and no arguments to lose), which is exactly why it stayed hidden.
     *
     * Held as a property because [SessionRequest] carries what the caller asked for,
     * which for a proot session is not what ran.
     */
    override val launchCommand: List<String> = listOf(shell) + request.args

    private var reportedTitle: String? = null

    /**
     * Termux's client callbacks. Everything is forwarded to NEXTERM's listener;
     * the logging methods intentionally drop the message rather than writing shell
     * content (which can contain secrets) into logcat.
     */
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changed: TerminalSession) = listener.onScreenUpdated(this@PtyTerminalHandle)

        override fun onTitleChanged(changed: TerminalSession) {
            val title = changed.title ?: return
            reportedTitle = title
            listener.onTitleChanged(this@PtyTerminalHandle, title)
        }

        override fun onSessionFinished(finished: TerminalSession) =
            listener.onSessionFinished(this@PtyTerminalHandle, finished.exitStatus)

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            listener.onCopyToClipboard(this@PtyTerminalHandle, text.orEmpty())
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) =
            listener.onPasteRequested(this@PtyTerminalHandle)

        override fun onBell(session: TerminalSession) = listener.onBell(this@PtyTerminalHandle)

        override fun onColorsChanged(session: TerminalSession) = listener.onScreenUpdated(this@PtyTerminalHandle)

        override fun onTerminalCursorStateChange(state: Boolean) = listener.onScreenUpdated(this@PtyTerminalHandle)

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        // Shell output can carry credentials; never mirror it into the system log.
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    val session: TerminalSession = try {
        TerminalSession(
            shell,
            cwd,
            launchCommand.toTypedArray(),
            environment.map { "${it.key}=${it.value}" }.toTypedArray(),
            request.transcriptRows.coerceIn(
                TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN,
                TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX,
            ),
            client,
        ).also { it.initializeEmulator(request.initialColumns, request.initialRows) }
    } catch (t: Throwable) {
        throw TerminalStartException(
            "The shell could not be started.",
            "$shell in $cwd: ${t.message}",
            t,
        )
    }

    override val isRunning: Boolean get() = session.isRunning
    override val exitStatus: Int? get() = if (session.isRunning) null else session.exitStatus
    override val pid: Int? get() = session.pid.takeIf { it > 0 }
    override val title: String? get() = reportedTitle
    override val currentWorkingDirectory: String? get() = runCatching { session.cwd }.getOrNull()

    override val emulator: TerminalEmulator? get() = session.emulator

    override fun write(bytes: ByteArray) = session.write(bytes, 0, bytes.size)

    override fun write(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    override fun resize(columns: Int, rows: Int) {
        if (columns < 1 || rows < 1) return
        session.updateSize(columns, rows)
    }

    override fun destroy() = session.finishIfRunning()
}
