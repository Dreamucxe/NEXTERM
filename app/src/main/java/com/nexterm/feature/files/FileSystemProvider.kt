package com.nexterm.feature.files

import java.io.InputStream
import java.io.OutputStream

/**
 * One entry in a directory listing, independent of which backend produced it.
 */
data class FileNode(
    /** Absolute path in the provider's own namespace. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    /** True when the entry is a symbolic link. */
    val isSymlink: Boolean = false,
    /** Where the link points, when known. */
    val linkTarget: String? = null,
    /** rwx bits as a string like "rwxr-xr-x", or null when the backend cannot say. */
    val permissions: String? = null,
    val owner: String? = null,
    val group: String? = null,
    val canRead: Boolean = true,
    val canWrite: Boolean = false,
    val canExecute: Boolean = false,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && !name.startsWith('.') }
            .orEmpty()
}

/** What a provider is actually allowed to do, so the UI never offers a dead action. */
data class ProviderCapabilities(
    val canList: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canDelete: Boolean,
    val canCreateDirectory: Boolean,
    val canRename: Boolean,
    val canChangePermissions: Boolean,
    /** Human-readable note shown in the browser when access is limited. */
    val limitation: String? = null,
) {
    companion object {
        val READ_ONLY = ProviderCapabilities(
            canList = true, canRead = true, canWrite = false, canDelete = false,
            canCreateDirectory = false, canRename = false, canChangePermissions = false,
        )
        val NONE = ProviderCapabilities(
            canList = false, canRead = false, canWrite = false, canDelete = false,
            canCreateDirectory = false, canRename = false, canChangePermissions = false,
        )
    }
}

/** Thrown for failures the user should see verbatim. */
class FileOperationException(
    val reason: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(reason, cause)

/**
 * A source of files.
 *
 * Deliberately not `java.io.File`-shaped: SAF trees, a Shizuku-elevated shell and a
 * proot rootfs all expose files, and none of them are reachable through plain file
 * I/O. Every implementation reports honestly through [capabilities] what it can do
 * *right now*, which is how the UI avoids the "root assumed" failure mode the spec
 * forbids.
 */
interface FileSystemProvider {
    /** Stable identifier used to route paths, e.g. "local", "saf", "root". */
    val id: String

    val displayName: String

    /** Where a browser should start when this provider is selected. */
    val rootPath: String

    /** Re-evaluated on demand; privilege can be granted or revoked while running. */
    suspend fun capabilities(): ProviderCapabilities

    suspend fun list(path: String): List<FileNode>

    suspend fun stat(path: String): FileNode?

    suspend fun openInput(path: String): InputStream

    suspend fun openOutput(path: String, append: Boolean = false): OutputStream

    suspend fun createDirectory(path: String)

    suspend fun delete(path: String, recursive: Boolean)

    suspend fun rename(from: String, to: String)

    /** @param mode octal permission bits, e.g. 0b111_101_101 for 755. */
    suspend fun setPermissions(path: String, mode: Int)

    /** Free bytes on the volume backing [path], or null when unknown. */
    suspend fun freeSpace(path: String): Long?
}
