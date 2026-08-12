package com.nexterm.feature.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.nexterm.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's own storage, reached through ordinary file I/O.
 *
 * This is the only provider that is guaranteed to work on every device with no
 * permission at all, because everything under `filesDir` belongs to this app's UID.
 * Paths outside it are still listed when the kernel permits it — `/system` and much
 * of `/data` are readable — and the per-node `canRead`/`canWrite` flags carry the
 * truth rather than an assumption.
 */
@Singleton
class LocalFileSystemProvider @Inject constructor(
    private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileSystemProvider {

    override val id: String = "local"
    override val displayName: String = "Device storage"
    override val rootPath: String get() = context.filesDir.absolutePath

    /** Directories worth offering as jump targets, with only the reachable ones kept. */
    fun shortcuts(): List<Pair<String, String>> = buildList {
        add("App home" to context.filesDir.absolutePath)
        context.getExternalFilesDir(null)?.let { add("App external" to it.absolutePath) }
        Environment.getExternalStorageDirectory()?.takeIf { it.canRead() }
            ?.let { add("Internal storage" to it.absolutePath) }
        listOf(
            "Downloads" to Environment.DIRECTORY_DOWNLOADS,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
            "Pictures" to Environment.DIRECTORY_PICTURES,
        ).forEach { (label, type) ->
            Environment.getExternalStoragePublicDirectory(type)
                ?.takeIf { it.canRead() }
                ?.let { add(label to it.absolutePath) }
        }
        add("Root of filesystem" to "/")
    }.distinctBy { it.second }

    override suspend fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        canList = true,
        canRead = true,
        canWrite = true,
        canDelete = true,
        canCreateDirectory = true,
        canRename = true,
        canChangePermissions = true,
        limitation = when {
            // Scoped storage: from API 30 the app can read shared storage but can
            // only write inside its own directories without SAF.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "Outside NEXTERM's own folders, Android's scoped storage allows " +
                    "reading but not writing. Use \"Open folder\" to grant write " +
                    "access to a specific directory."

            else -> null
        },
    )

    override suspend fun list(path: String): List<FileNode> = withContext(io) {
        val directory = File(path)
        if (!directory.exists()) {
            throw FileOperationException("${directory.name} no longer exists.")
        }
        if (!directory.isDirectory) {
            throw FileOperationException("${directory.name} is not a folder.")
        }
        val children = directory.listFiles()
            ?: throw FileOperationException(
                "Cannot open ${directory.name}.",
                "The system denied access to ${directory.absolutePath}. On Android 11 " +
                    "and later, /Android/data and /Android/obb are restricted even " +
                    "with storage permission granted.",
            )
        children.map { it.toNode() }
    }

    override suspend fun stat(path: String): FileNode? = withContext(io) {
        File(path).takeIf { it.exists() }?.toNode()
    }

    override suspend fun openInput(path: String): InputStream = withContext(io) {
        runCatching { File(path).inputStream() }.getOrElse {
            throw FileOperationException("Cannot read ${File(path).name}.", it.message, it)
        }
    }

    override suspend fun openOutput(path: String, append: Boolean): OutputStream =
        withContext(io) {
            runCatching { FileOutputStream(path, append) }.getOrElse {
                throw FileOperationException("Cannot write to ${File(path).name}.", it.message, it)
            }
        }

    override suspend fun createDirectory(path: String) = withContext(io) {
        val directory = File(path)
        if (directory.exists()) throw FileOperationException("${directory.name} already exists.")
        if (!directory.mkdirs()) {
            throw FileOperationException(
                "Could not create ${directory.name}.",
                "mkdir failed in ${directory.parent}. The folder is probably read-only " +
                    "for this app.",
            )
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(io) {
        val target = File(path)
        if (!target.exists() && !isDanglingSymlink(target)) {
            throw FileOperationException("${target.name} no longer exists.")
        }
        val deleted = when {
            // deleteRecursively walks with listFiles(), which does not follow
            // symlinks, so a link into another tree is unlinked rather than emptied.
            target.isDirectory && recursive -> target.deleteRecursively()
            else -> target.delete()
        }
        if (!deleted) {
            throw FileOperationException(
                "Could not delete ${target.name}.",
                "unlink failed for ${target.absolutePath}.",
            )
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(io) {
        val source = File(from)
        val destination = File(to)
        if (destination.exists()) {
            throw FileOperationException("${destination.name} already exists.")
        }
        if (!source.renameTo(destination)) {
            // rename(2) cannot cross filesystems; a copy+delete is the honest fallback.
            val copied = runCatching {
                source.copyRecursively(destination, overwrite = false)
            }.getOrDefault(false)
            if (!copied) {
                throw FileOperationException(
                    "Could not rename ${source.name}.",
                    "rename failed and the fallback copy did not succeed. Source and " +
                        "destination may be on different filesystems.",
                )
            }
            source.deleteRecursively()
        }
    }

    override suspend fun setPermissions(path: String, mode: Int) = withContext(io) {
        try {
            Os.chmod(path, mode)
        } catch (e: ErrnoException) {
            throw FileOperationException(
                "Could not change permissions on ${File(path).name}.",
                "chmod returned ${e.message}. Only the owning app can change a file's " +
                    "mode without elevated privilege.",
                e,
            )
        }
    }

    override suspend fun freeSpace(path: String): Long? = withContext(io) {
        runCatching { File(path).usableSpace }.getOrNull()
    }

    /**
     * Reads metadata through `lstat` so a symlink is reported as itself rather than
     * as whatever it points at — a rootfs is full of links, and following them would
     * make `/bin/sh` look like a 900 KB executable in every listing.
     */
    private fun File.toNode(): FileNode {
        val stat: StructStat? = runCatching { Os.lstat(absolutePath) }.getOrNull()
        val isLink = stat?.let { OsConstants.S_ISLNK(it.st_mode) } ?: false
        val linkTarget = if (isLink) runCatching { Os.readlink(absolutePath) }.getOrNull() else null
        // For a link, size and mtime describe the link itself; for anything else the
        // java.io values are correct and cheaper.
        val directory = if (isLink) {
            runCatching { File(absolutePath).isDirectory }.getOrDefault(false)
        } else {
            isDirectory
        }

        return FileNode(
            path = absolutePath,
            name = name,
            isDirectory = directory,
            sizeBytes = if (directory) 0L else (stat?.st_size ?: length()),
            lastModified = stat?.let { it.st_mtime * 1000L } ?: lastModified(),
            isHidden = name.startsWith('.'),
            isSymlink = isLink,
            linkTarget = linkTarget,
            permissions = stat?.let { formatMode(it.st_mode) },
            canRead = canRead(),
            canWrite = canWrite(),
            canExecute = canExecute(),
        )
    }

    private fun isDanglingSymlink(file: File): Boolean =
        runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)

    private fun formatMode(mode: Int): String {
        val bits = charArrayOf('-', '-', '-', '-', '-', '-', '-', '-', '-')
        val flags = intArrayOf(
            OsConstants.S_IRUSR, OsConstants.S_IWUSR, OsConstants.S_IXUSR,
            OsConstants.S_IRGRP, OsConstants.S_IWGRP, OsConstants.S_IXGRP,
            OsConstants.S_IROTH, OsConstants.S_IWOTH, OsConstants.S_IXOTH,
        )
        val letters = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
        for (i in flags.indices) if (mode and flags[i] != 0) bits[i] = letters[i]
        return String(bits)
    }
}
