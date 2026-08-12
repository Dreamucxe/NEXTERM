package com.nexterm.core.terminal

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Everything needed to open one SSH connection. Secrets are supplied per-connect. */
data class SshCredentials(
    val host: String,
    val port: Int,
    val username: String,
    /** PEM/OpenSSH private key material, or null when using a password. */
    val privateKey: ByteArray? = null,
    val privateKeyPassphrase: CharArray? = null,
    val password: CharArray? = null,
    /** known_hosts content. When null, unknown hosts are rejected. */
    val knownHosts: String? = null,
    /** Set only after the user has explicitly accepted a new host key. */
    val trustUnknownHost: Boolean = false,
) {
    /** Wipes key material from memory once a connection attempt is finished. */
    fun shred() {
        privateKey?.fill(0)
        privateKeyPassphrase?.fill(Char(0))
        password?.fill(Char(0))
    }
}

/** Thrown when a host key is not in known_hosts and the user has not accepted it. */
class UnknownHostKeyException(
    val fingerprint: String,
    val host: String,
) : Exception("The authenticity of host '$host' can't be established.")

/**
 * Real SSH sessions over a JSch [ChannelShell].
 *
 * This is a pure-JVM implementation, which matters here: it needs no native code,
 * so it works on every ABI. Remote output is fed through the same [TerminalEmulator]
 * used for local PTY sessions, so colours, ncurses apps and resizing behave
 * identically whether the shell is local or remote.
 */
class SshProvider(
    private val credentialsFor: suspend (profileId: Long) -> SshCredentials,
) : TerminalProvider {

    override val kind: SessionKind = SessionKind.SSH

    /** SSH itself is always "available"; individual connections may still fail. */
    override fun availability(): ProviderAvailability = ProviderAvailability.Available

    override suspend fun createSession(
        request: SessionRequest,
        listener: TerminalListener,
    ): TerminalHandle = withContext(Dispatchers.IO) {
        val profileId = request.sshProfileId
            ?: throw TerminalStartException("No SSH profile was selected for this session.")

        val credentials = credentialsFor(profileId)
        try {
            val jsch = JSch()

            credentials.knownHosts?.takeIf { it.isNotBlank() }?.let {
                jsch.setKnownHosts(it.byteInputStream())
            }
            credentials.privateKey?.let { key ->
                val passphrase = credentials.privateKeyPassphrase
                    ?.let { String(it).toByteArray(Charsets.UTF_8) }
                jsch.addIdentity(
                    "nexterm-$profileId",
                    key.copyOf(),
                    null,
                    passphrase,
                )
            }

            val session: Session = jsch.getSession(
                credentials.username,
                credentials.host,
                credentials.port,
            )
            credentials.password?.let { session.setPassword(String(it)) }

            session.setConfig(Properties().apply {
                // Host-key checking stays on unless the user has explicitly accepted
                // this host; "no" is never used, as that silently allows MITM.
                setProperty(
                    "StrictHostKeyChecking",
                    if (credentials.trustUnknownHost) "accept-new" else "yes",
                )
                setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
            })
            session.setDaemonThread(true)

            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                if (message.contains("UnknownHostKey", ignoreCase = true)) {
                    val fingerprint = message.substringAfterLast(' ').trim()
                    throw UnknownHostKeyException(fingerprint, credentials.host)
                }
                throw TerminalStartException(
                    describeConnectFailure(e, credentials),
                    "${e::class.java.simpleName}: ${e.message}",
                    e,
                )
            }

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color", request.initialColumns, request.initialRows, 0, 0)
            channel.setEnv("LANG", "en_US.UTF-8")

            SshTerminalHandle(
                sshSession = session,
                channel = channel,
                request = request,
                listener = listener,
            ).also { it.start() }
        } finally {
            credentials.shred()
        }
    }

    /** Turns JSch's terse errors into something a user can act on. */
    private fun describeConnectFailure(e: Exception, credentials: SshCredentials): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", true) || message.contains("Auth cancel", true) ->
                "Authentication failed for ${credentials.username}@${credentials.host}."
            message.contains("UnknownHostKey", true) ->
                "The host key for ${credentials.host} is not trusted yet."
            message.contains("timeout", true) || message.contains("timed out", true) ->
                "Timed out connecting to ${credentials.host}:${credentials.port}."
            message.contains("Connection refused", true) ->
                "${credentials.host} refused the connection on port ${credentials.port}."
            message.contains("UnknownHost", true) || message.contains("resolve", true) ->
                "Could not resolve the host name ${credentials.host}."
            else -> "Could not connect to ${credentials.host}:${credentials.port}."
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}

/**
 * Pumps a JSch shell channel through a [TerminalEmulator].
 *
 * Reads happen on a dedicated IO coroutine; emulator mutation and listener
 * callbacks are marshalled to the main thread, matching the threading contract the
 * local PTY path already establishes.
 */
private class SshTerminalHandle(
    private val sshSession: Session,
    private val channel: ChannelShell,
    private val request: SessionRequest,
    private val listener: TerminalListener,
) : TerminalHandle, EmulatorBacked {

    override val id: String = request.id
    override val kind: SessionKind = SessionKind.SSH

    private val mainDispatcher = android.os.Handler(android.os.Looper.getMainLooper())
        .asCoroutineDispatcher("nexterm-ssh-main")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var reportedTitle: String? = null
    private var finished = false
    private var recordedExit: Int? = null

    private lateinit var remoteInput: InputStream
    private lateinit var remoteOutput: OutputStream

    /** Emulator output sink: bytes the *terminal* wants to send back to the host. */
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            scope.launch {
                runCatching {
                    remoteOutput.write(data, offset, count)
                    remoteOutput.flush()
                }
            }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            val title = newTitle ?: return
            reportedTitle = title
            listener.onTitleChanged(this@SshTerminalHandle, title)
        }

        override fun onCopyTextToClipboard(text: String?) {
            listener.onCopyToClipboard(this@SshTerminalHandle, text.orEmpty())
        }

        override fun onPasteTextFromClipboard() = listener.onPasteRequested(this@SshTerminalHandle)

        override fun onBell() = listener.onBell(this@SshTerminalHandle)

        override fun onColorsChanged() = listener.onScreenUpdated(this@SshTerminalHandle)
    }

    /**
     * [TerminalEmulator] invokes its client for cursor style and for logging when it
     * meets an escape sequence it cannot parse, and it does so without null checks.
     * A no-op client is therefore required, not optional: passing null would crash
     * the session on the first malformed sequence a remote host emits. Log methods
     * discard their argument so remote output never reaches logcat.
     */
    private val emulatorClient = object : com.termux.terminal.TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) = Unit
        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) = Unit
        override fun onBell(session: com.termux.terminal.TerminalSession) = Unit
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val terminalEmulator = TerminalEmulator(
        output,
        request.initialColumns,
        request.initialRows,
        request.transcriptRows,
        emulatorClient,
    )

    override val emulator: TerminalEmulator get() = terminalEmulator

    fun start() {
        remoteInput = channel.inputStream
        remoteOutput = channel.outputStream
        channel.connect(CHANNEL_TIMEOUT_MS)

        scope.launch {
            val buffer = ByteArray(READ_BUFFER)
            try {
                while (true) {
                    val read = remoteInput.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    val chunk = buffer.copyOf(read)
                    withContext(mainDispatcher) {
                        terminalEmulator.append(chunk, chunk.size)
                        listener.onScreenUpdated(this@SshTerminalHandle)
                    }
                }
            } catch (_: Throwable) {
                // Falls through to the finish path below; a broken pipe on
                // disconnect is expected, not an error worth surfacing twice.
            } finally {
                val status = runCatching { channel.exitStatus }.getOrDefault(-1)
                withContext(mainDispatcher) { finish(status) }
            }
        }
    }

    private fun finish(status: Int) {
        if (finished) return
        finished = true
        recordedExit = status
        listener.onSessionFinished(this, status)
    }

    override val isRunning: Boolean get() = !finished && channel.isConnected
    override val exitStatus: Int? get() = recordedExit
    override val pid: Int? get() = null
    override val title: String? get() = reportedTitle
    override val currentWorkingDirectory: String? get() = null

    override fun write(bytes: ByteArray) {
        if (!isRunning) return
        scope.launch {
            runCatching {
                remoteOutput.write(bytes)
                remoteOutput.flush()
            }
        }
    }

    override fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    override fun resize(columns: Int, rows: Int) {
        if (columns < 1 || rows < 1 || !isRunning) return
        terminalEmulator.resize(columns, rows)
        runCatching { channel.setPtySize(columns, rows, 0, 0) }
    }

    override fun destroy() {
        runCatching { channel.disconnect() }
        runCatching { sshSession.disconnect() }
        finish(recordedExit ?: 0)
        scope.cancel()
    }

    private companion object {
        const val CHANNEL_TIMEOUT_MS = 15_000
        const val READ_BUFFER = 8192
    }
}
