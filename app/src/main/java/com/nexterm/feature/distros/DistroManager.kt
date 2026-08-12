package com.nexterm.feature.distros

import android.content.Context
import com.nexterm.core.common.IoDispatcher
import com.nexterm.data.database.DistroDao
import com.nexterm.data.database.DistroEntity
import com.nexterm.data.model.Distro
import com.nexterm.data.model.DistroStatus
import com.nexterm.data.model.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Live progress of one install, for the environments screen. */
data class InstallProgress(
    val distroId: String,
    val phase: Phase,
    /** 0f..1f, or null when the server sent no Content-Length. */
    val fraction: Float?,
    val bytesProcessed: Long,
    val totalBytes: Long?,
    val entriesWritten: Int = 0,
) {
    enum class Phase { DOWNLOADING, VERIFYING, EXTRACTING, FINALISING }
}

/** A failure the user needs to read, with the technical cause kept separate. */
class DistroInstallException(
    val reason: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(reason, cause)

/**
 * Installs and removes proot Linux environments.
 *
 * The whole pipeline is real: an HTTPS download with resume, a SHA-256 check against
 * the pinned digest in [DistroCatalog], streaming xz/gzip decompression, and a tar
 * extraction that preserves symlinks and executable bits. Nothing is written into
 * the live rootfs directory until the checksum matches, and an interrupted install
 * leaves a partial file that the next attempt resumes from rather than a half-built
 * environment that appears usable.
 *
 * Note the split of responsibilities with [com.nexterm.core.terminal.ProotProvider]:
 * an environment can be fully installed here and still not be *runnable*, because
 * running it needs a proot binary that Android's W^X policy may not let this app
 * supply. The two states are reported separately so the UI never conflates them.
 */
@Singleton
class DistroManager @Inject constructor(
    private val context: Context,
    private val distroDao: DistroDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())

    /** Progress per distro id; an id disappears when its install ends. */
    val progress: StateFlow<Map<String, InstallProgress>> = _progress.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** The architecture rootfs must match, or null on an ABI we have no mapping for. */
    val architecture: DistroCatalog.Architecture? = DistroCatalog.Architecture.current()

    /**
     * Catalog entries joined with their persisted install state.
     *
     * The catalog is the source of truth for what exists; the database only records
     * what happened to it. A distro dropped from the catalog therefore disappears
     * from the list without needing a migration.
     */
    fun observeDistros(): Flow<List<InstallableDistro>> =
        combine(distroDao.observeAll(), _progress) { rows, progressById ->
            val byId = rows.associateBy { it.id }
            DistroCatalog.entries.map { entry ->
                val stored = byId[entry.id]?.toModel() ?: Distro(
                    id = entry.id,
                    displayName = entry.displayName,
                    version = entry.version,
                    status = DistroStatus.NOT_INSTALLED,
                    rootfsPath = null,
                    installedAt = null,
                    sizeBytes = null,
                )
                InstallableDistro(
                    catalog = entry,
                    state = stored,
                    progress = progressById[entry.id],
                    supportedHere = architecture != null &&
                        entry.rootfsFor(architecture) != null,
                )
            }
        }

    /** Root of a distro's extracted filesystem, or null when it is not installed. */
    fun rootfsDirectory(distroId: String): File? {
        val directory = rootfsPath(distroId)
        // The marker is written only after a complete extraction, so a directory left
        // behind by a killed install is not mistaken for a usable environment.
        return if (File(directory, INSTALL_MARKER).isFile) directory else null
    }

    private fun rootfsPath(distroId: String) = File(distrosRoot(), distroId)

    /**
     * Distros live in filesDir, not the cache or external storage: the cache can be
     * cleared by the system mid-session, and external storage is mounted `noexec`.
     */
    private fun distrosRoot() = File(context.filesDir, "distros").apply { mkdirs() }

    /**
     * Downloads, verifies and extracts a distro. Suspends until finished.
     *
     * Cancelling the calling coroutine aborts the install; the partial download is
     * kept so a retry resumes, and the incomplete rootfs is deleted.
     */
    suspend fun install(distroId: String) = withContext(io) {
        val entry = DistroCatalog.byId(distroId)
            ?: throw DistroInstallException("Unknown environment '$distroId'.")

        val architecture = architecture ?: throw DistroInstallException(
            "NEXTERM does not have a Linux rootfs for this device's CPU.",
            "Android reports ABIs ${android.os.Build.SUPPORTED_ABIS.joinToString()}, " +
                "which does not map to any architecture published by proot-distro.",
        )

        val rootfs = entry.rootfsFor(architecture) ?: throw DistroInstallException(
            "${entry.displayName} is not published for ${architecture.displayName}.",
            "Available for: " + entry.supportedArchitectures.joinToString { it.displayName },
            )

        jobs[distroId] = currentCoroutineContext()[Job] ?: Job()
        val target = rootfsPath(distroId)
        val download = File(distrosRoot(), "$distroId.tar.download")

        try {
            markStatus(entry, DistroStatus.DOWNLOADING)

            val digest = downloadWithResume(rootfs, download) { received, total ->
                publish(
                    InstallProgress(
                        distroId = distroId,
                        phase = InstallProgress.Phase.DOWNLOADING,
                        fraction = total?.let { (received.toDouble() / it).toFloat() },
                        bytesProcessed = received,
                        totalBytes = total,
                    ),
                )
            }

            publish(
                InstallProgress(
                    distroId, InstallProgress.Phase.VERIFYING, null, download.length(),
                    download.length(),
                ),
            )
            if (!digest.equals(rootfs.sha256, ignoreCase = true)) {
                // A mismatch means a corrupted transfer or a substituted file. Either
                // way this is executable code, so it is deleted rather than unpacked.
                download.delete()
                throw DistroInstallException(
                    "The downloaded ${entry.displayName} image failed its integrity check.",
                    "Expected SHA-256 ${rootfs.sha256}, got $digest. The partial " +
                        "download has been discarded; try again on a different network.",
                )
            }

            markStatus(entry, DistroStatus.EXTRACTING)
            // Any leftovers from an aborted attempt go before the new extraction, or
            // stale files would survive underneath the new rootfs.
            if (target.exists()) target.deleteRecursively()

            val entries = extract(download, target, rootfs.stripComponents) { bytes, written ->
                publish(
                    InstallProgress(
                        distroId = distroId,
                        phase = InstallProgress.Phase.EXTRACTING,
                        // The tar's uncompressed size is unknown up front, so this is
                        // reported as a count rather than a fake percentage.
                        fraction = null,
                        bytesProcessed = bytes,
                        totalBytes = null,
                        entriesWritten = written,
                    ),
                )
            }

            publish(InstallProgress(distroId, InstallProgress.Phase.FINALISING, null, 0, null))
            configure(target)
            File(target, INSTALL_MARKER).writeText(
                "id=$distroId\narch=${architecture.id}\nsha256=${rootfs.sha256}\nentries=$entries\n",
            )
            download.delete()

            distroDao.upsert(
                DistroEntity(
                    id = entry.id,
                    displayName = entry.displayName,
                    version = entry.version,
                    status = DistroStatus.INSTALLED.name,
                    rootfsPath = target.absolutePath,
                    installedAt = System.currentTimeMillis(),
                    sizeBytes = directorySize(target),
                ),
            )
        } catch (cancellation: CancellationException) {
            // The download is kept for resume; the half-written rootfs is not.
            target.deleteRecursively()
            markStatus(entry, DistroStatus.NOT_INSTALLED)
            throw cancellation
        } catch (error: Throwable) {
            target.deleteRecursively()
            markStatus(entry, DistroStatus.BROKEN)
            throw when (error) {
                is DistroInstallException -> error
                else -> DistroInstallException(
                    "Installing ${entry.displayName} failed.",
                    error.message,
                    error,
                )
            }
        } finally {
            jobs.remove(distroId)
            _progress.update { it - distroId }
        }
    }

    /** Cancels an install in flight. The partial download survives for a retry. */
    fun cancelInstall(distroId: String) {
        jobs.remove(distroId)?.cancel()
    }

    /**
     * Deletes an installed environment. Destructive and irreversible, so the caller
     * is responsible for confirming with the user first (spec §51).
     */
    suspend fun uninstall(distroId: String) = withContext(io) {
        val target = rootfsPath(distroId)
        // A rootfs contains symlinks pointing at absolute guest paths (/bin/busybox).
        // deleteRecursively walks with File.listFiles, which does not follow links,
        // so this cannot escape the directory.
        if (target.exists() && !target.deleteRecursively()) {
            throw DistroInstallException(
                "Could not fully remove ${target.name}.",
                "Some files under ${target.absolutePath} could not be deleted.",
            )
        }
        File(distrosRoot(), "$distroId.tar.download").delete()
        DistroCatalog.byId(distroId)?.let { markStatus(it, DistroStatus.NOT_INSTALLED) }
    }

    /** On-disk footprint of an installed environment, or 0 when absent. */
    suspend fun installedSize(distroId: String): Long = withContext(io) {
        rootfsDirectory(distroId)?.let { directorySize(it) } ?: 0L
    }

    /**
     * Downloads to [destination], resuming a partial file with a Range request, and
     * returns the SHA-256 of the complete file as lowercase hex.
     */
    private suspend fun downloadWithResume(
        rootfs: DistroCatalog.Rootfs,
        destination: File,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): String {
        var url = URL(rootfs.url)
        var redirects = 0
        var existing = if (destination.isFile) destination.length() else 0L

        while (true) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Redirects are followed manually: HttpURLConnection drops the Range
                // header across a redirect, and GitHub always redirects to a CDN.
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "NEXTERM")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308,
                    -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw DistroInstallException("The download server sent an invalid redirect.")
                        if (++redirects > MAX_REDIRECTS) {
                            throw DistroInstallException("The download server redirected too many times.")
                        }
                        url = URL(url, location)
                        continue
                    }

                    HttpURLConnection.HTTP_OK -> {
                        // The server ignored the Range header; start over.
                        existing = 0
                    }

                    HttpURLConnection.HTTP_PARTIAL -> Unit

                    HttpURLConnection.HTTP_NOT_FOUND -> throw DistroInstallException(
                        "The rootfs image is no longer available at its published address.",
                        "HTTP 404 for ${url.host}${url.path}",
                    )

                    416 -> {
                        // Requested range not satisfiable: the local file is already
                        // complete (or longer than the remote). Re-fetch to be sure.
                        destination.delete()
                        existing = 0
                        continue
                    }

                    else -> throw DistroInstallException(
                        "The download failed with HTTP $code.",
                        "${connection.responseMessage} from ${url.host}",
                    )
                }

                val remaining = connection.contentLengthLong.takeIf { it >= 0 }
                val total = remaining?.let { it + existing } ?: rootfs.downloadBytes
                var received = existing

                FileOutputStream(destination, existing > 0).use { output ->
                    connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastPublished = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            received += n
                            // Throttled so a fast connection does not flood the UI.
                            if (received - lastPublished >= PROGRESS_STEP_BYTES) {
                                lastPublished = received
                                onProgress(received, total)
                            }
                        }
                    }
                    output.fd.sync()
                }
                onProgress(received, total)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw DistroInstallException(
                    "The download could not be completed.",
                    "${error.javaClass.simpleName}: ${error.message}. " +
                        "Progress is kept, so retrying resumes from " +
                        "${destination.length() / 1024} KB.",
                    error,
                )
            } finally {
                connection.disconnect()
            }

            return sha256(destination)
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (stream.read(buffer) < 0) break
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(
        archive: File,
        target: File,
        stripComponents: Int,
        onProgress: (bytes: Long, entries: Int) -> Unit,
    ): Int = archive.inputStream().buffered(BUFFER_SIZE).use { raw ->
        decompress(raw, archive).use { stream ->
            TarExtractor.extract(
                input = stream,
                destination = target,
                stripComponents = stripComponents,
                progress = { bytes, entries -> onProgress(bytes, entries) },
            )
        }
    }

    /**
     * Picks the decompressor from the file's magic bytes rather than its name, so a
     * server that serves a `.tar.xz` as gzip still installs.
     */
    private fun decompress(input: InputStream, archive: File): InputStream {
        val buffered = BufferedInputStream(input, BUFFER_SIZE)
        val magic = ByteArray(6)
        buffered.mark(magic.size)
        var read = 0
        while (read < magic.size) {
            val n = buffered.read(magic, read, magic.size - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()

        return when {
            read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                GZIPInputStream(buffered, BUFFER_SIZE)

            read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == '7'.code.toByte() &&
                magic[2] == 'z'.code.toByte() && magic[3] == 'X'.code.toByte() &&
                magic[4] == 'Z'.code.toByte() -> XZInputStream(buffered)

            // Uncompressed tar is legal and some mirrors serve it.
            else -> if (read >= 6) buffered else throw DistroInstallException(
                "The downloaded image is not a readable archive.",
                "${archive.name} is ${archive.length()} bytes and has no known " +
                    "compression signature.",
            )
        }
    }

    /**
     * Post-extraction fixes a rootfs needs to work under proot on Android.
     *
     * Kept minimal and non-destructive: only files the guest would otherwise be
     * unable to write are touched, and existing content is never overwritten
     * except where the rootfs ships a placeholder that is wrong on Android.
     */
    private fun configure(rootfs: File) {
        File(rootfs, "etc").mkdirs()

        // Android has no /etc/resolv.conf to bind-mount, and the guest cannot read
        // the system DNS properties, so name resolution fails without this.
        File(rootfs, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
        )

        // Several distros' /etc/hosts omit localhost, which breaks package tooling.
        File(rootfs, "etc/hosts").let { hosts ->
            if (!hosts.isFile || hosts.length() == 0L) {
                hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
            }
        }

        // proot maps the guest to uid 0, so a home directory must exist for it.
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply { mkdirs(); setReadable(true, false); setWritable(true, false) }

        // A rootfs with no shell cannot start a session; catching it here gives a
        // clear error instead of an opaque proot failure later.
        val shells = listOf("bin/sh", "usr/bin/sh", "bin/bash", "bin/busybox")
        if (shells.none { File(rootfs, it).exists() }) {
            throw DistroInstallException(
                "The extracted environment has no shell and cannot be started.",
                "None of ${shells.joinToString()} exist under ${rootfs.absolutePath}.",
            )
        }
    }

    private suspend fun markStatus(entry: DistroCatalog.Entry, status: DistroStatus) {
        distroDao.upsert(
            DistroEntity(
                id = entry.id,
                displayName = entry.displayName,
                version = entry.version,
                status = status.name,
                rootfsPath = if (status == DistroStatus.INSTALLED) {
                    rootfsPath(entry.id).absolutePath
                } else {
                    null
                },
                installedAt = distroDao.get(entry.id)?.installedAt,
                sizeBytes = distroDao.get(entry.id)?.sizeBytes,
            ),
        )
    }

    private fun publish(progress: InstallProgress) {
        _progress.update { it + (progress.distroId to progress) }
    }

    /** Walks with listFiles, which does not traverse symlinks, so links count once. */
    private fun directorySize(directory: File): Long {
        var total = 0L
        val stack = ArrayDeque(listOf(directory))
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory && !isSymlink(child)) stack.addLast(child)
                else total += child.length()
            }
        }
        return total
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { file.canonicalFile != file.absoluteFile }.getOrDefault(false)

    private companion object {
        const val INSTALL_MARKER = ".nexterm-installed"
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}

/** A catalog entry joined with its install state, as the environments screen needs it. */
data class InstallableDistro(
    val catalog: DistroCatalog.Entry,
    val state: Distro,
    val progress: InstallProgress?,
    /** False when no rootfs exists for this device's CPU. */
    val supportedHere: Boolean,
) {
    val id: String get() = catalog.id
    val isInstalled: Boolean get() = state.status == DistroStatus.INSTALLED
    val isBusy: Boolean get() = progress != null
}
