package com.nexterm.core.terminal

/**
 * How a session is being executed. This drives the privilege badge in the UI and
 * decides which [TerminalProvider] a session is routed to.
 */
enum class SessionKind {
    /** A shell on the Android system itself, inside the app's own UID sandbox. */
    LOCAL,

    /** A shell inside a proot Linux rootfs (Ubuntu, Alpine, ...). */
    PROOT,

    /** A remote shell reached over SSH. */
    SSH,
}

/** The privilege a session actually runs with. Never assume, always detect. */
enum class PrivilegeLevel {
    UNPRIVILEGED,
    SHIZUKU,
    ROOT,
}

/**
 * Everything needed to start one session. Deliberately a plain value type so it can
 * be persisted, restored, and passed across layers without dragging in Android or
 * PTY types.
 */
data class SessionRequest(
    val kind: SessionKind,
    /**
     * Identity the started session will carry. Callers that already own an id — the
     * workspace passes its tab id — supply it so a tab and its session can never
     * drift apart; anything else gets a fresh one.
     */
    val id: String = java.util.UUID.randomUUID().toString(),
    /** Executable to run. For [SessionKind.LOCAL] this is an absolute path. */
    val executable: String,
    /** argv[0] is supplied by the provider; these are the arguments after it. */
    val args: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String,
    val initialColumns: Int = 80,
    val initialRows: Int = 24,
    val transcriptRows: Int = 5_000,
    /** Populated for [SessionKind.SSH]; ignored otherwise. */
    val sshProfileId: Long? = null,
    /** Populated for [SessionKind.PROOT]; ignored otherwise. */
    val distroId: String? = null,
)

/**
 * A live terminal session.
 *
 * Implementations own a real OS process (or a real remote channel) and a real
 * terminal-emulator state machine; nothing here simulates output.
 */
interface TerminalHandle {
    val id: String
    val kind: SessionKind

    /** True until the underlying process exits or the session is destroyed. */
    val isRunning: Boolean

    /** Exit status, or null while still running. */
    val exitStatus: Int?

    /** OS pid of the child, or null when not applicable (e.g. SSH). */
    val pid: Int?

    /** Title reported by the shell via OSC 0/2, when it sets one. */
    val title: String?

    /** Best-effort current working directory, read from /proc when available. */
    val currentWorkingDirectory: String?

    /**
     * The command this handle actually launched, argv[0] first.
     *
     * Only for the diagnostics the user opens themselves: when a session dies on
     * startup, the resolved paths are the difference between a debuggable failure and
     * a bare exit code. A proot session's real argv is assembled deep in the provider
     * and is nothing like what the caller asked for, which is exactly why it is worth
     * showing. Empty for handles with no local process, such as SSH.
     */
    val launchCommand: List<String> get() = emptyList()

    /** Send raw bytes (already encoded) to the child's stdin. */
    fun write(bytes: ByteArray)

    /** Send text as UTF-8 to the child's stdin. */
    fun write(text: String)

    /** Inform the PTY of a new window size (TIOCSWINSZ) and reflow the emulator. */
    fun resize(columns: Int, rows: Int)

    /** Terminate the session and release its file descriptors. */
    fun destroy()
}

/**
 * Abstraction over "something that can run a terminal session".
 *
 * The UI depends only on this interface, never on the PTY implementation, so a
 * provider can be swapped (local PTY, proot, SSH) without touching Compose code.
 */
interface TerminalProvider {
    val kind: SessionKind

    /**
     * Whether this provider can currently start a session, and why not if it cannot.
     * Checked before the UI offers the option, so the user never gets a dead button.
     */
    fun availability(): ProviderAvailability

    /** Start a real session. Throws [TerminalStartException] on failure. */
    suspend fun createSession(request: SessionRequest, listener: TerminalListener): TerminalHandle
}

/** Whether a provider can run, with a human-readable reason when it cannot. */
sealed interface ProviderAvailability {
    data object Available : ProviderAvailability

    /**
     * @param reason short, user-facing explanation
     * @param detail technical specifics, shown behind an expander
     * @param remedy concrete steps the user can take, if any
     */
    data class Unavailable(
        val reason: String,
        val detail: String? = null,
        val remedy: List<String> = emptyList(),
    ) : ProviderAvailability
}

/** Callbacks from a running session. All are delivered on the main thread. */
interface TerminalListener {
    /** New output has been parsed into the screen buffer; time to redraw. */
    fun onScreenUpdated(handle: TerminalHandle)

    /** The shell changed the window title (OSC 0/2). */
    fun onTitleChanged(handle: TerminalHandle, title: String)

    /** The child exited. [exitStatus] is its wait status. */
    fun onSessionFinished(handle: TerminalHandle, exitStatus: Int)

    /** The terminal bell (BEL) was received. */
    fun onBell(handle: TerminalHandle)

    fun onCopyToClipboard(handle: TerminalHandle, text: String)

    fun onPasteRequested(handle: TerminalHandle)
}

/** Thrown when a session cannot be started; message is already user-readable. */
class TerminalStartException(
    message: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
