package com.nexterm.core.terminal

import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable, UI-facing state of one session.
 *
 * Held separately from [TerminalHandle] so Compose can react to changes without
 * touching the emulator's mutable internals from the composition.
 */
data class SessionState(
    val id: String,
    val kind: SessionKind,
    val privilege: PrivilegeLevel,
    /** Bumped on every screen change; the renderer keys its redraw off this. */
    val revision: Long = 0,
    val isRunning: Boolean = true,
    val exitStatus: Int? = null,
    /**
     * The tail of the session's output, captured at the moment the child exited.
     *
     * The engine writes the child's stderr into the same buffer as its stdout, so a
     * failed launch has already printed its reason on screen. Keeping a copy means the
     * exit banner can show that reason instead of only a number, and that it survives
     * the user scrolling away or the activity being recreated.
     */
    val exitOutput: String? = null,
    /** Resolved argv of the launched process, for the diagnostics the user can open. */
    val launchCommand: List<String> = emptyList(),
    /** Title from OSC 0/2, if the shell set one. */
    val title: String? = null,
    val workingDirectory: String? = null,
    val pid: Int? = null,
    /** Output arrived while this session was not on screen. */
    val hasUnseenActivity: Boolean = false,
    /** BEL arrived while this session was not on screen. */
    val hasUnseenBell: Boolean = false,
    val columns: Int = 80,
    val rows: Int = 24,
)

/**
 * Owns every live terminal session in the process.
 *
 * Sessions outlive the composables that display them: a tab can scroll off screen,
 * the activity can be recreated on rotation, and the shell keeps running. Only
 * [closeSession] or the child exiting ends a session.
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    private val providers: TerminalProviders,
) {
    private val handles = ConcurrentHashMap<String, TerminalHandle>()

    private val _states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val states: StateFlow<Map<String, SessionState>> = _states.asStateFlow()

    /** The session currently visible; used to decide whether activity is "unseen". */
    @Volatile
    private var foregroundSessionId: String? = null

    /** Number of sessions still running, for the foreground-service notification. */
    val runningCount: Int get() = handles.values.count { it.isRunning }

    fun handle(id: String): TerminalHandle? = handles[id]

    /** The emulator backing a session, or null for non-PTY sessions. */
    fun emulator(id: String): TerminalEmulator? = (handles[id] as? EmulatorBacked)?.emulator

    fun setForeground(id: String?) {
        foregroundSessionId = id
        if (id == null) return
        // Seeing a session clears its activity markers.
        _states.update { current ->
            val existing = current[id] ?: return@update current
            if (!existing.hasUnseenActivity && !existing.hasUnseenBell) return@update current
            current + (id to existing.copy(hasUnseenActivity = false, hasUnseenBell = false))
        }
    }

    /**
     * Starts a real session and registers it.
     *
     * @throws TerminalStartException with a user-readable message on failure.
     */
    suspend fun createSession(
        request: SessionRequest,
        privilege: PrivilegeLevel = PrivilegeLevel.UNPRIVILEGED,
    ): String {
        val provider = providers.forKind(request.kind)
            ?: throw TerminalStartException("No engine is available for ${request.kind}.")

        (provider.availability() as? ProviderAvailability.Unavailable)?.let {
            throw TerminalStartException(it.reason, it.detail)
        }

        // The listener needs the id, which only exists once the handle is built, so
        // it reads through this holder rather than capturing a not-yet-known value.
        lateinit var created: TerminalHandle
        val listener = object : TerminalListener {
            override fun onScreenUpdated(handle: TerminalHandle) = mutate(handle.id) {
                it.copy(
                    revision = it.revision + 1,
                    hasUnseenActivity = it.hasUnseenActivity || handle.id != foregroundSessionId,
                    workingDirectory = handle.currentWorkingDirectory ?: it.workingDirectory,
                )
            }

            override fun onTitleChanged(handle: TerminalHandle, title: String) =
                mutate(handle.id) { it.copy(title = title) }

            override fun onSessionFinished(handle: TerminalHandle, exitStatus: Int) {
                // Captured before the state is mutated. By the time this fires the
                // emulator already holds both the child's own last words and the
                // engine's "[Process completed]" line, so this is the one moment the
                // reason for a failed launch is guaranteed to be readable.
                val tail = captureOutputTail(handle)
                mutate(handle.id) {
                    it.copy(
                        // Bumped like any other screen change. The child's final output
                        // arrives immediately before this callback, and without a new
                        // revision the renderer keeps showing the frame from before it
                        // — which is how an error message ends up in the buffer but
                        // never on screen.
                        revision = it.revision + 1,
                        isRunning = false,
                        exitStatus = exitStatus,
                        exitOutput = tail,
                    )
                }
            }

            override fun onBell(handle: TerminalHandle) = mutate(handle.id) {
                it.copy(hasUnseenBell = it.hasUnseenBell || handle.id != foregroundSessionId)
            }

            override fun onCopyToClipboard(handle: TerminalHandle, text: String) {
                clipboardSink?.invoke(text)
            }

            override fun onPasteRequested(handle: TerminalHandle) {
                pasteSource?.invoke()?.let { handle.write(it) }
            }
        }

        created = provider.createSession(request, listener)
        handles[created.id] = created
        _states.update { current ->
            current + (created.id to SessionState(
                id = created.id,
                kind = created.kind,
                privilege = privilege,
                pid = created.pid,
                launchCommand = created.launchCommand,
                workingDirectory = request.workingDirectory,
                columns = request.initialColumns,
                rows = request.initialRows,
            ))
        }
        return created.id
    }

    fun write(id: String, text: String) {
        handles[id]?.takeIf { it.isRunning }?.write(text)
    }

    fun write(id: String, bytes: ByteArray) {
        handles[id]?.takeIf { it.isRunning }?.write(bytes)
    }

    fun resize(id: String, columns: Int, rows: Int) {
        val handle = handles[id] ?: return
        val state = _states.value[id]
        if (state?.columns == columns && state.rows == rows) return
        handle.resize(columns, rows)
        mutate(id) { it.copy(columns = columns, rows = rows) }
    }

    /** Ends a session and forgets it. Safe to call on an already-dead session. */
    fun closeSession(id: String) {
        handles.remove(id)?.destroy()
        _states.update { it - id }
        if (foregroundSessionId == id) foregroundSessionId = null
    }

    /** Kills the child but keeps the tab, so the user can read the final output. */
    fun killSession(id: String) {
        handles[id]?.destroy()
    }

    fun closeAll() {
        handles.keys.toList().forEach(::closeSession)
    }

    /** Set by the UI layer so OSC 52 / paste callbacks can reach the clipboard. */
    @Volatile
    var clipboardSink: ((String) -> Unit)? = null

    @Volatile
    var pasteSource: (() -> String?)? = null

    private inline fun mutate(id: String, transform: (SessionState) -> SessionState) {
        _states.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to transform(existing))
        }
    }

    /**
     * Reads the last lines of a finished session's screen.
     *
     * Deliberately kept in memory and shown on screen only — the same text can contain
     * anything the user typed, so it never reaches logcat or disk, matching the rule
     * the session client's no-op log methods enforce.
     */
    private fun captureOutputTail(handle: TerminalHandle): String? {
        val emulator = (handle as? EmulatorBacked)?.emulator ?: return null
        return runCatching {
            val screen = emulator.screen
            val rows = emulator.mRows
            // Screen rows are indexed from -activeTranscriptRows (oldest scrollback)
            // to mRows - 1 (the bottom line), so counting back from the end reaches
            // into the scrollback without running off it.
            val first = maxOf(-screen.activeTranscriptRows, rows - OUTPUT_TAIL_ROWS)
            screen.getSelectedText(0, first, emulator.mColumns - 1, rows - 1)
                ?.lines()
                ?.dropWhile(String::isBlank)
                ?.dropLastWhile(String::isBlank)
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        /** Enough to hold a launch failure's output without becoming a second screen. */
        const val OUTPUT_TAIL_ROWS = 40
    }
}

/** Implemented by handles that expose a Termux emulator for rendering. */
interface EmulatorBacked {
    val emulator: TerminalEmulator?
}
