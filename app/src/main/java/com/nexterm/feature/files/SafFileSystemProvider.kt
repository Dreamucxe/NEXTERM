package com.nexterm.feature.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.nexterm.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage reached through the Storage Access Framework.
 *
 * On Android 11 and later an app cannot write to shared storage with a plain path,
 * no matter which permission it holds. SAF is the supported way to get real write
 * access to a folder the user picks, so this provider exists to give the browser a
 * writable view of `/sdcard` and of removable volumes — the closest legitimate
 * equivalent to the unrestricted path access older Android allowed.
 *
 * Paths here are `content://` URIs rendered as strings, so the rest of the browser
 * can keep treating a location as an opaque string.
 */
@Singleton
class SafFileSystemProvider @Inject constructor(
    private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileSystemProvider {

    override val id: String = "saf"
    override val displayName: String = "Granted folders"

    /** Empty until the user grants a tree; the browser then shows the grant list. */
    override val rootPath: String get() = grantedTrees().firstOrNull()?.toString().orEmpty()

    /** The folders the user has granted, surviving reboots via persisted permissions. */
    fun grantedTrees(): List<Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .map { it.uri }

    /** Human label for a granted tree, taken from the provider rather than guessed. */
    fun treeLabel(uri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment
            ?: uri.toString()

    /** The intent that opens the system folder picker. */
    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /** Persists a grant returned by the picker so it survives a restart. */
    fun persistGrant(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Gives a folder back to the system; the browser confirms before calling this. */
    fun releaseGrant(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    override suspend fun capabilities(): ProviderCapabilities {
        if (grantedTrees().isEmpty()) {
            return ProviderCapabilities.NONE.copy(
                limitation = "No folder has been granted yet. Use \"Open folder\" to " +
                    "let Android give NEXTERM access to a directory.",
            )
        }
        return ProviderCapabilities(
            canList = true,
            canRead = true,
            canWrite = true,
            canDelete = true,
            canCreateDirectory = true,
            canRename = true,
            // SAF has no concept of a Unix mode; the provider on the other side may
            // not even be backed by a filesystem.
            canChangePermissions = false,
            limitation = "Granted folders use the Storage Access Framework, which has " +
                "no Unix permissions and cannot execute files.",
        )
    }

    override suspend fun list(path: String): List<FileNode> = withContext(io) {
        val directory = resolve(path)
        if (!directory.isDirectory) throw FileOperationException("${directory.name} is not a folder.")
        directory.listFiles().map { it.toNode() }
    }

    override suspend fun stat(path: String): FileNode? = withContext(io) {
        runCatching { resolve(path).toNode() }.getOrNull()
    }

    override suspend fun openInput(path: String): InputStream = withContext(io) {
        context.contentResolver.openInputStream(Uri.parse(path))
            ?: throw FileOperationException(
                "Cannot read this file.",
                "The document provider returned no stream for $path.",
            )
    }

    override suspend fun openOutput(path: String, append: Boolean): OutputStream =
        withContext(io) {
            // "wa" is honoured by the media/documents providers that support it and
            // ignored by those that do not, so append is best-effort by design.
            context.contentResolver.openOutputStream(Uri.parse(path), if (append) "wa" else "wt")
                ?: throw FileOperationException(
                    "Cannot write to this file.",
                    "The document provider refused to open $path for writing.",
                )
        }

    override suspend fun createDirectory(path: String) = withContext(io) {
        val parentPath = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        val parent = resolve(parentPath)
        parent.createDirectory(name)
            ?: throw FileOperationException(
                "Could not create $name.",
                "The document provider rejected the request.",
            )
        Unit
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(io) {
        val target = resolve(path)
        if (target.isDirectory && !recursive && target.listFiles().isNotEmpty()) {
            throw FileOperationException("${target.name} is not empty.")
        }
        if (!target.delete()) {
            throw FileOperationException(
                "Could not delete ${target.name}.",
                "The document provider rejected the deletion.",
            )
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(io) {
        val target = resolve(from)
        val newName = to.substringAfterLast('/')
        if (!target.renameTo(newName)) {
            throw FileOperationException(
                "Could not rename ${target.name}.",
                "The document provider rejected the rename, or a file called " +
                    "$newName already exists.",
            )
        }
    }

    override suspend fun setPermissions(path: String, mode: Int): Unit =
        throw FileOperationException(
            "Permissions cannot be changed here.",
            "The Storage Access Framework does not expose Unix file modes.",
        )

    override suspend fun freeSpace(path: String): Long? = null

    /**
     * Turns a stored path back into a document.
     *
     * A tree URI is not itself a document URI, so a bare grant has to be converted
     * before it can be listed; anything else is already a document.
     */
    private fun resolve(path: String): DocumentFile {
        val uri = Uri.parse(path)
        val document = if (DocumentsContract.isTreeUri(uri) && !isDocumentInTree(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)?.takeIf { it.isFile }
                ?: DocumentFile.fromTreeUri(context, uri)
        }
        return document ?: throw FileOperationException(
            "This location is no longer available.",
            "Android has revoked access to $path, or the storage was removed.",
        )
    }

    private fun isDocumentInTree(uri: Uri): Boolean =
        runCatching { DocumentsContract.getDocumentId(uri) != null }.getOrDefault(false)

    private fun DocumentFile.toNode(): FileNode {
        val label = name ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        return FileNode(
            path = uri.toString(),
            name = label,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            lastModified = lastModified(),
            isHidden = label.startsWith('.'),
            canRead = canRead(),
            canWrite = canWrite(),
            canExecute = false,
        )
    }
}
