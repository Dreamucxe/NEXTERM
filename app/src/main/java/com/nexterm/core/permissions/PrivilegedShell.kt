package com.nexterm.core.permissions

import com.nexterm.core.common.IoDispatcher
import com.nexterm.core.terminal.PrivilegeLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Result of one elevated command: exit status plus both streams, never discarded. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** The line most worth showing a user when something failed. */
    val failureLine: String?
        get() = stderr.lineSequence().firstOrNull { it.isNotBlank() }
            ?: stdout.lineSequence().firstOrNull { it.isNotBlank() }
}

/** Raised when a command cannot even be started at the requested privilege. */
class PrivilegeUnavailableException(
    val reason: String,
    val detail: String? = null,
) : Exception(reason)

/**
 * Runs single shell commands at elevated privilege.
 *
 * Two very different transports hide behind one API:
 *
 *  * **root** — `su -c <command>`. Real superuser; the user's su manager may prompt.
 *  * **Shizuku** — `Shizuku.newProcess`, reached reflectively. This runs as **shell**
 *    (uid 2000) or system, *not* as root. It can read most of `/data` and use
 *    `pm`/`am`, but it cannot write to another app's private directory and cannot
 *    remount `/system`. Presenting it as root would be a lie, so [availableLevels]
 *    reports the two separately and callers pick explicitly.
 *
 * Nothing here caches a shell. Each call is its own process, so a revoked
 * permission takes effect immediately rather than at the next app start.
 */
@Singleton
class PrivilegedShell @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Which elevations answered a real probe, most capable first. */
    suspend fun availableLevels(): List<PrivilegeLevel> {
        val state = privilegeManager.refresh()
        return buildList {
            if (state.rootAvailable) add(PrivilegeLevel.ROOT)
            if (state.shizuku == ShizukuState.RUNNING_PERMISSION_GRANTED) {
                add(PrivilegeLevel.SHIZUKU)
            }
        }
    }

    /**
     * Executes [command] through `sh -c` at [level].
     *
     * @throws PrivilegeUnavailableException when the transport is not usable, rather
     *   than returning a non-zero exit that a caller might mistake for a real failure
     *   of the command itself.
     */
    suspend fun run(
        command: String,
        level: PrivilegeLevel,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ShellResult = withContext(io) {
        val process = when (level) {
            PrivilegeLevel.ROOT -> startRoot(command)
            PrivilegeLevel.SHIZUKU -> startShizuku(command)
            PrivilegeLevel.UNPRIVILEGED -> startPlain(command)
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = drain(process.inputStream, out)
        val errReader = drain(process.errorStream, err)

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outReader.join(DRAIN_JOIN_MS)
            errReader.join(DRAIN_JOIN_MS)
            throw PrivilegeUnavailableException(
                "The command did not finish in time.",
                "Gave up after ${timeoutMs}ms running: $command",
            )
        }
        outReader.join(DRAIN_JOIN_MS)
        errReader.join(DRAIN_JOIN_MS)

        ShellResult(process.exitValue(), out.toString(), err.toString())
    }

    private fun startPlain(command: String): Process =
        ProcessBuilder("sh", "-c", command).start()

    private fun startRoot(command: String): Process {
        val suPath = privilegeManager.state.value.suPath
            ?: throw PrivilegeUnavailableException(
                "Root is not available.",
                "No su binary answered the uid probe on this device.",
            )
        return runCatching { ProcessBuilder(suPath, "-c", command).start() }.getOrElse {
            throw PrivilegeUnavailableException(
                "Root refused the request.",
                it.message ?: "$suPath could not be started.",
            )
        }
    }

    /**
     * Shizuku's `newProcess` is a hidden API, so it is invoked reflectively; a
     * missing method means the installed Shizuku is too old rather than absent.
     */
    private fun startShizuku(command: String): Process {
        val shizuku = runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
            ?: throw PrivilegeUnavailableException(
                "Shizuku is not installed.",
                "The rikka.shizuku classes are not present in this process.",
            )

        val method = runCatching {
            shizuku.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrElse {
            throw PrivilegeUnavailableException(
                "This Shizuku version cannot run shell commands.",
                "Shizuku.newProcess is missing; update the Shizuku app.",
            )
        }

        return runCatching {
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        }.getOrElse {
            throw PrivilegeUnavailableException(
                "Shizuku refused the request.",
                (it.cause ?: it).message
                    ?: "Shizuku is installed but the service did not accept the call.",
            )
        }
    }

    /** Reads a stream on its own thread; a full pipe buffer would otherwise deadlock. */
    private fun drain(stream: InputStream, into: StringBuilder): Thread =
        Thread { runCatching { stream.bufferedReader().forEachLine { into.append(it).append('\n') } } }
            .apply { isDaemon = true; start() }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DRAIN_JOIN_MS = 1_000L
    }
}
