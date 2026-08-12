package com.nexterm.feature.files

import android.content.Context
import com.nexterm.core.common.IoDispatcher
import com.nexterm.core.permissions.PrivilegeUnavailableException
import com.nexterm.core.permissions.PrivilegedShell
import com.nexterm.core.permissions.ShellResult
import com.nexterm.core.terminal.PrivilegeLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * A filesystem reached by running commands through an elevated shell.
 *
 * One class serves both root and Shizuku because the mechanics are identical — only
 * the identity of the resulting process differs, and that difference is stated in
 * [capabilities] rather than assumed away. Shizuku runs as **shell** (uid 2000), not
 * as root: it reads far more than an ordinary app but cannot write another app's
 * private data or touch a read-only partition. The UI shows that sentence verbatim.
 *
 * Content is moved through a world-readable staging file in `/data/local/tmp`, which
 * is the one directory both an unprivileged app and a shell-uid process can reach:
 * the directory is `0771`, so "other" may traverse it even though it may not list it.
 */
class ElevatedFileSystemProvider(
    private val context: Context,
    private val shell: PrivilegedShell,
    private val level: PrivilegeLevel,
    private val io: CoroutineDispatcher,
) : FileSystemProvider {

    override val id: String = when (level) {
        PrivilegeLevel.ROOT -> "root"
        PrivilegeLevel.SHIZUKU -> "shizuku"
        PrivilegeLevel.UNPRIVILEGED -> "shell"
    }

    override val displayName: String = when (level) {
        PrivilegeLevel.ROOT -> "Root filesystem"
        PrivilegeLevel.SHIZUKU -> "Shizuku (shell access)"
        PrivilegeLevel.UNPRIVILEGED -> "Shell"
    }

    override val rootPath: String = "/"

    override suspend fun capabilities(): ProviderCapabilities {
        val levels = shell.availableLevels()
        if (level !in levels) {
            return ProviderCapabilities.NONE.copy(
                limitation = when (level) {
                    PrivilegeLevel.ROOT ->
                        "Root is not available. No su binary on this device answered a " +
                            "uid probe, so NEXTERM will not pretend it has superuser access."

                    PrivilegeLevel.SHIZUKU ->
                        "Shizuku is not running, or has not granted NEXTERM permission. " +
                            "Start the Shizuku app and grant access, then reopen this tab."

                    PrivilegeLevel.UNPRIVILEGED -> null
                },
            )
        }

        return ProviderCapabilities(
            canList = true,
            canRead = true,
            canWrite = true,
            canDelete = true,
            canCreateDirectory = true,
            canRename = true,
            canChangePermissions = level == PrivilegeLevel.ROOT,
            limitation = when (level) {
                PrivilegeLevel.SHIZUKU ->
                    "Shizuku runs commands as the shell user (uid 2000), not as root. " +
                        "It can read most of the system but cannot write to another " +
                        "app's private data, change file ownership, or modify read-only " +
                        "partitions. Operations that exceed that will fail with the " +
                        "kernel's own error."

                else -> null
            },
        )
    }

    override suspend fun list(path: String): List<FileNode> = withContext(io) {
        // `stat` is asked for the name too, so a file that vanishes mid-listing
        // shifts nothing: every line describes itself. lstat is the default, which
        // is what a browser wants — a symlink should look like a symlink.
        val listing = exec(
            "cd ${quote(path)} 2>/dev/null || exit 66; " +
                "ls -a | tr '\\n' '\\0' | xargs -0 stat -c '%f|%s|%Y|%U|%G|%h|%n' 2>/dev/null",
        )
        if (listing.exitCode == 66) {
            throw FileOperationException(
                "Cannot open $path.",
                "The directory does not exist, or ${displayName.lowercase()} may not " +
                    "traverse into it.",
            )
        }

        val nodes = listing.stdout.lineSequence()
            .mapNotNull { parseStatLine(it, path) }
            .filter { it.name != "." && it.name != ".." }
            .toMutableList()

        if (nodes.isEmpty() && listing.stdout.isBlank() && listing.stderr.isNotBlank()) {
            throw FileOperationException("Cannot list $path.", listing.failureLine)
        }

        // Resolve link targets in a single extra call rather than one per link.
        val links = nodes.filter { it.isSymlink }
        if (links.isNotEmpty()) {
            val targets = exec(
                "readlink " + links.joinToString(" ") { quote(it.path) },
            ).stdout.lines()
            links.forEachIndexed { index, node ->
                val target = targets.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val at = nodes.indexOf(node)
                if (at >= 0) nodes[at] = node.copy(linkTarget = target)
            }
        }
        nodes
    }

    override suspend fun stat(path: String): FileNode? = withContext(io) {
        val result = exec("stat -c '%f|%s|%Y|%U|%G|%h|%n' ${quote(path)}")
        if (!result.isSuccess) return@withContext null
        parseStatLine(result.stdout.lineSequence().firstOrNull().orEmpty(), File(path).parent ?: "/")
    }

    override suspend fun openInput(path: String): InputStream = withContext(io) {
        val staged = stagingPath()
        val result = exec("cp -- ${quote(path)} ${quote(staged)} && chmod 666 ${quote(staged)}")
        if (!result.isSuccess) {
            exec("rm -f -- ${quote(staged)}")
            throw FileOperationException(
                "Cannot read ${File(path).name}.",
                result.failureLine ?: "Copying the file to a readable location failed.",
            )
        }
        val local = File(staged)
        object : java.io.FilterInputStream(local.inputStream()) {
            override fun close() {
                super.close()
                // Best effort: the staging copy must not outlive the read.
                runCatching { local.delete() }
            }
        }
    }

    override suspend fun openOutput(path: String, append: Boolean): OutputStream =
        withContext(io) {
            val staged = stagingPath()
            val local = File(staged)
            // Seed the staging file with the current contents when appending, so the
            // copy-back can stay a single whole-file replace.
            var seeded = false
            if (append) {
                seeded = exec(
                    "cp -- ${quote(path)} ${quote(staged)} && chmod 666 ${quote(staged)}",
                ).isSuccess
            }
            if (!seeded) {
                local.outputStream().close()
                exec("chmod 666 ${quote(staged)}")
            }

            object : FilterOutputStream(java.io.FileOutputStream(local, seeded)) {
                private var closed = false

                // FilterOutputStream's default write(byte[]) loops byte-by-byte
                // through write(int), which would make a large save unbearably slow.
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                override fun close() {
                    if (closed) return
                    closed = true
                    super.close()
                    // The OutputStream contract says the bytes are durable once
                    // close() returns, so the copy-back has to happen here. It runs
                    // on a fresh IO thread, so blocking this one cannot deadlock it.
                    val copy = kotlinx.coroutines.runBlocking {
                        val result = exec("cat ${quote(staged)} > ${quote(path)}")
                        exec("rm -f -- ${quote(staged)}")
                        result
                    }
                    runCatching { local.delete() }
                    if (!copy.isSuccess) {
                        throw FileOperationException(
                            "Could not save ${File(path).name}.",
                            copy.failureLine ?: "Writing back through the elevated shell failed.",
                        )
                    }
                }
            }
        }

    override suspend fun createDirectory(path: String) = withContext(io) {
        val result = exec("mkdir -p -- ${quote(path)}")
        if (!result.isSuccess) {
            throw FileOperationException(
                "Could not create ${File(path).name}.",
                result.failureLine,
            )
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(io) {
        // Guard rails, not decoration: a recursive delete of "/" through root would
        // brick the device, and the spec forbids running destructive commands blindly.
        val normalised = path.trimEnd('/').ifEmpty { "/" }
        if (normalised in PROTECTED_PATHS) {
            throw FileOperationException(
                "NEXTERM will not delete $normalised.",
                "Removing a system root would make the device unbootable. Delete " +
                    "individual entries inside it instead if that is really intended.",
            )
        }
        val result = exec(if (recursive) "rm -rf -- ${quote(path)}" else "rm -f -- ${quote(path)}")
        if (!result.isSuccess) {
            throw FileOperationException("Could not delete ${File(path).name}.", result.failureLine)
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(io) {
        val exists = exec("[ -e ${quote(to)} ] && echo yes || echo no").stdout.trim()
        if (exists == "yes") throw FileOperationException("${File(to).name} already exists.")
        val result = exec("mv -- ${quote(from)} ${quote(to)}")
        if (!result.isSuccess) {
            throw FileOperationException("Could not rename ${File(from).name}.", result.failureLine)
        }
    }

    override suspend fun setPermissions(path: String, mode: Int) = withContext(io) {
        val octal = Integer.toOctalString(mode).padStart(3, '0')
        val result = exec("chmod $octal -- ${quote(path)}")
        if (!result.isSuccess) {
            throw FileOperationException(
                "Could not change permissions on ${File(path).name}.",
                result.failureLine ?: "chmod was rejected at this privilege level.",
            )
        }
    }

    override suspend fun freeSpace(path: String): Long? = withContext(io) {
        val result = exec("df -kP ${quote(path)} 2>/dev/null | tail -n 1")
        if (!result.isSuccess) return@withContext null
        result.stdout.trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.times(1024L)
    }

    private suspend fun exec(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): ShellResult =
        try {
            shell.run(command, level, timeoutMs)
        } catch (e: PrivilegeUnavailableException) {
            throw FileOperationException(e.reason, e.detail, e)
        }

    /**
     * Parses one `stat -c '%f|%s|%Y|%U|%G|%h|%n'` line. `%f` is the raw st_mode in
     * hex, which carries both the file type and the permission bits, so a single
     * field decides whether an entry is a directory, a link or a regular file.
     */
    private fun parseStatLine(line: String, parent: String): FileNode? {
        if (line.isBlank()) return null
        val parts = line.split('|', limit = 7)
        if (parts.size < 7) return null

        val mode = parts[0].trim().toIntOrNull(16) ?: return null
        val size = parts[1].trim().toLongOrNull() ?: 0L
        val mtime = parts[2].trim().toLongOrNull() ?: 0L
        val owner = parts[3].trim()
        val group = parts[4].trim()
        val name = parts[6].substringAfterLast('/').ifEmpty { parts[6] }

        val type = mode and S_IFMT
        val isDirectory = type == S_IFDIR
        val isSymlink = type == S_IFLNK

        return FileNode(
            path = if (parent == "/") "/$name" else "$parent/$name",
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else size,
            lastModified = mtime * 1000L,
            isHidden = name.startsWith('.'),
            isSymlink = isSymlink,
            permissions = formatMode(mode),
            owner = owner.takeIf { it.isNotEmpty() },
            group = group.takeIf { it.isNotEmpty() },
            // At this privilege the shell can reach anything the kernel allows it to;
            // per-operation failures are reported from the command's own stderr.
            canRead = true,
            canWrite = level == PrivilegeLevel.ROOT,
            canExecute = mode and 0b001_001_001 != 0,
        )
    }

    private fun formatMode(mode: Int): String {
        val letters = "rwxrwxrwx"
        return buildString {
            for (i in 0 until 9) {
                val bit = 1 shl (8 - i)
                append(if (mode and bit != 0) letters[i] else '-')
            }
        }
    }

    /** A unique staging name; the counter avoids two concurrent reads colliding. */
    private fun stagingPath(): String =
        "$STAGING_DIR/nexterm-${context.packageName.hashCode().toUInt()}-${stagingCounter++}"

    /** Wraps a path in single quotes, escaping any single quote it contains. */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private companion object {
        var stagingCounter: Long = 0
        const val STAGING_DIR = "/data/local/tmp"
        const val COMMAND_TIMEOUT_MS = 20_000L
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000
        val PROTECTED_PATHS = setOf(
            "/", "/system", "/vendor", "/data", "/data/data", "/proc", "/sys", "/dev",
        )
    }
}

/** Factory so Hilt can supply the shared collaborators once per privilege level. */
class ElevatedProviderFactory @Inject constructor(
    private val context: Context,
    private val shell: PrivilegedShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun create(level: PrivilegeLevel): ElevatedFileSystemProvider =
        ElevatedFileSystemProvider(context, shell, level, io)
}
