package com.nexterm.feature.files

import com.nexterm.feature.distros.DistroManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The inside of an installed Linux environment.
 *
 * No elevation and no proot process are involved: the rootfs is an ordinary
 * directory under the app's own `filesDir`, so plain file I/O reaches it. What this
 * provider adds is the *guest* view — paths are shown as the distro sees them
 * (`/etc/hosts`, not `/data/user/0/com.nexterm/files/distros/debian/etc/hosts`) —
 * and a guarantee that a path cannot escape the rootfs.
 *
 * A distro that is not installed yields [ProviderCapabilities.NONE] with a reason,
 * because listing a directory that does not exist is not a useful error.
 */
class ProotFileSystemProvider(
    private val distroId: String,
    private val distroDisplayName: String,
    private val distroManager: DistroManager,
    private val local: LocalFileSystemProvider,
) : FileSystemProvider {

    override val id: String = "proot:$distroId"
    override val displayName: String = distroDisplayName
    override val rootPath: String = "/"

    private val rootfs: File? get() = distroManager.rootfsDirectory(distroId)

    override suspend fun capabilities(): ProviderCapabilities {
        val root = rootfs ?: return ProviderCapabilities.NONE.copy(
            limitation = "$distroDisplayName is not installed. Install it from the " +
                "Environments screen and this view will fill in.",
        )
        return ProviderCapabilities(
            canList = true,
            canRead = true,
            canWrite = root.canWrite(),
            canDelete = root.canWrite(),
            canCreateDirectory = root.canWrite(),
            canRename = root.canWrite(),
            canChangePermissions = true,
            limitation = "Files here belong to NEXTERM, so ownership inside the " +
                "environment is emulated by proot at run time rather than stored on " +
                "disk. Changing an owner has no effect outside a proot session.",
        )
    }

    override suspend fun list(path: String): List<FileNode> =
        local.list(host(path)).map { it.toGuest() }

    override suspend fun stat(path: String): FileNode? = local.stat(host(path))?.toGuest()

    override suspend fun openInput(path: String): InputStream = local.openInput(host(path))

    override suspend fun openOutput(path: String, append: Boolean): OutputStream =
        local.openOutput(host(path), append)

    override suspend fun createDirectory(path: String) = local.createDirectory(host(path))

    override suspend fun delete(path: String, recursive: Boolean) =
        local.delete(host(path), recursive)

    override suspend fun rename(from: String, to: String) = local.rename(host(from), host(to))

    override suspend fun setPermissions(path: String, mode: Int) =
        local.setPermissions(host(path), mode)

    override suspend fun freeSpace(path: String): Long? = local.freeSpace(host(path))

    /**
     * Maps a guest path onto the host, refusing anything that would leave the rootfs.
     *
     * `canonicalPath` resolves `..` *and* symlinks, so a link planted inside the
     * rootfs that points at `/data` cannot be used to walk out of it either.
     */
    private fun host(guestPath: String): String {
        val root = rootfs ?: throw FileOperationException(
            "$distroDisplayName is not installed.",
            "There is no rootfs directory for $distroId yet.",
        )
        val rootCanonical = root.canonicalPath
        val relative = guestPath.trimStart('/')
        val candidate = File(root, relative)
        val canonical = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)

        if (canonical != rootCanonical && !canonical.startsWith("$rootCanonical/")) {
            throw FileOperationException(
                "That path is outside $distroDisplayName.",
                "$guestPath resolves to $canonical, which is not inside the " +
                    "environment's root directory.",
            )
        }
        return canonical
    }

    /** Rewrites a host path back into the guest namespace for display. */
    private fun FileNode.toGuest(): FileNode {
        val root = rootfs?.canonicalPath ?: return this
        val guest = when {
            path == root -> "/"
            path.startsWith("$root/") -> path.removePrefix(root)
            else -> path
        }
        return copy(path = guest)
    }
}
