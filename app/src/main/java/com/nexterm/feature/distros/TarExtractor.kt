package com.nexterm.feature.distros

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Extracts a Linux rootfs from a tar stream.
 *
 * Written directly against the tar format rather than pulled from a library on
 * purpose. A rootfs is not an ordinary archive — roughly a fifth of its entries are
 * symbolic links, `/bin`, `/lib` and friends are usually links themselves, and the
 * executable bits decide whether the distro boots at all. Apache Commons Compress
 * handles all of that, but its classes carry `java.nio.file.Path` in their
 * signatures, which is a verification risk on API 24/25 where that package does not
 * exist. The format itself is a fixed 512-byte header, so parsing it here costs less
 * than working around the library.
 *
 * Supported: regular files, directories, symlinks, hardlinks, GNU long names/links
 * (`L`/`K`) and PAX extended headers (`x`) for paths. Device and FIFO nodes are
 * skipped — an unprivileged app cannot create them, and proot bind-mounts the real
 * `/dev` over that directory anyway.
 */
object TarExtractor {

    private const val BLOCK = 512

    /** Trim targets for NUL-padded header text. */
    private const val NUL = '\u0000'
    private const val NEWLINE = '\n'

    /** Progress callback: bytes of the *stream* consumed so far. */
    fun interface Progress {
        fun onProgress(bytesRead: Long, entriesWritten: Int)
    }

    /**
     * Unpacks [input] into [destination].
     *
     * @param stripComponents leading path components to drop. proot-distro tarballs
     *   wrap everything in a `debian-aarch64/` directory; Alpine's do not.
     * @return number of entries written.
     * @throws IOException on a malformed archive, or if an entry tries to escape
     *   [destination] (a "tar slip" — an archive containing `../../` paths).
     */
    fun extract(
        input: InputStream,
        destination: File,
        stripComponents: Int = 0,
        progress: Progress? = null,
    ): Int {
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("Cannot create ${destination.absolutePath}")
        }
        val canonicalRoot = destination.canonicalPath

        val header = ByteArray(BLOCK)
        val buffer = ByteArray(64 * 1024)
        // Hardlinks and symlinks may point at files that appear later in the stream,
        // so they are collected and replayed at the end.
        val deferredLinks = mutableListOf<Triple<File, String, Boolean>>()
        // Directory permissions are applied last: a read-only directory cannot have
        // children written into it.
        val directoryModes = mutableListOf<Pair<File, Int>>()

        var bytesRead = 0L
        var written = 0
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var emptyBlocks = 0

        while (true) {
            if (!input.readFully(header)) break
            bytesRead += BLOCK

            if (header.all { it == 0.toByte() }) {
                // Two consecutive zero blocks mark end-of-archive.
                if (++emptyBlocks >= 2) break
                continue
            }
            emptyBlocks = 0

            if (!header.checksumValid()) {
                throw IOException("Damaged archive: bad header checksum at byte $bytesRead")
            }

            val size = header.octal(124, 12)
            val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK
            // Very old tars write a NUL type byte for a regular file.
            val type = header[156].toInt().toChar().let { if (it == NUL) '0' else it }

            // GNU long-name and PAX headers carry their payload as an entry body that
            // describes the *next* entry. readString consumes the body; only the
            // block padding is left to skip.
            when (type) {
                'L', 'K', 'x', 'X', 'g' -> {
                    val payload = input.readString(size, buffer)
                    input.skipFully(padded - size)
                    bytesRead += padded
                    when (type) {
                        'L' -> pendingLongName = payload
                        'K' -> pendingLongLink = payload
                        'x', 'X' -> {
                            paxValue(payload, "path")?.let { pendingLongName = it }
                            paxValue(payload, "linkpath")?.let { pendingLongLink = it }
                        }
                        // 'g' is an archive-wide default; nothing here needs it.
                    }
                    continue
                }
            }

            val rawName = pendingLongName ?: header.name()
            val rawLink = pendingLongLink ?: header.string(157, 100)
            pendingLongName = null
            pendingLongLink = null

            val relative = strip(rawName, stripComponents)
            if (relative.isEmpty()) {
                input.skipFully(padded)
                bytesRead += padded
                continue
            }

            val target = File(destination, relative)
            // Reject anything that would land outside the rootfs.
            if (!target.canonicalPath.startsWith(canonicalRoot)) {
                throw IOException("Archive entry escapes the target directory: $rawName")
            }

            val mode = header.octal(100, 8).toInt()

            when (type) {
                '5' -> {
                    target.mkdirs()
                    if (mode != 0) directoryModes += target to mode
                }

                // '7' is a contiguous file, which every modern tar treats as regular.
                '0', '7' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        var remaining = size
                        while (remaining > 0) {
                            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                            val n = input.read(buffer, 0, chunk)
                            if (n < 0) throw IOException("Archive truncated inside $rawName")
                            out.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                    input.skipFully(padded - size)
                    bytesRead += padded
                    // Without this the distro's binaries are not executable and
                    // nothing inside the rootfs will run.
                    if (mode != 0) chmod(target, mode)
                    written++
                }

                '1' -> {
                    deferredLinks += Triple(target, strip(rawLink, stripComponents), false)
                    written++
                }

                '2' -> {
                    // Symlink targets are guest-absolute (/usr/bin/sh) and are resolved
                    // by proot inside the rootfs, so they are stored verbatim.
                    deferredLinks += Triple(target, rawLink, true)
                    written++
                }

                else -> {
                    // Character/block devices, FIFOs and sockets: not creatable without
                    // root, and supplied by proot's bind mounts instead.
                    input.skipFully(padded)
                    bytesRead += padded
                }
            }

            progress?.onProgress(bytesRead, written)
        }

        for ((link, targetPath, symbolic) in deferredLinks) {
            link.parentFile?.mkdirs()
            // A previous, interrupted install may have left something here.
            if (link.exists() || isDanglingSymlink(link)) link.delete()
            try {
                if (symbolic) {
                    Os.symlink(targetPath, link.absolutePath)
                } else {
                    Os.link(File(destination, targetPath).absolutePath, link.absolutePath)
                }
            } catch (e: ErrnoException) {
                if (symbolic) throw IOException("Cannot create symlink ${link.name}", e)
                // Filesystems that refuse hardlinks (rare, but some vendor overlays do)
                // still give a working rootfs if the content is copied instead.
                val source = File(destination, targetPath)
                if (source.isFile) source.copyTo(link, overwrite = true) else throw IOException(
                    "Cannot create hardlink ${link.name}", e,
                )
            }
        }

        // Applied last so children could be written first.
        for ((directory, mode) in directoryModes.asReversed()) chmod(directory, mode)

        return written
    }

    private fun chmod(file: File, mode: Int) {
        try {
            // The archive's mode is the guest's intent; the owner must keep write
            // access or an update/uninstall cannot remove the file.
            Os.chmod(file.absolutePath, mode or OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        } catch (_: ErrnoException) {
            // Fall back to the java.io bits, which cover the case that matters most.
            if (mode and OsConstants.S_IXUSR != 0) file.setExecutable(true, true)
            file.setReadable(true, true)
        }
    }

    /** `File.exists()` follows symlinks, so a broken link reports as absent. */
    private fun isDanglingSymlink(file: File): Boolean = try {
        Os.lstat(file.absolutePath)
        true
    } catch (_: ErrnoException) {
        false
    }

    private fun strip(path: String, components: Int): String {
        var normalized = path.trimStart('/')
        if (normalized.startsWith("./")) normalized = normalized.substring(2)
        if (components <= 0) return normalized.trimEnd('/')
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        return parts.drop(components).joinToString("/")
    }

    /** Reads `key=value` out of a PAX extended header record. */
    private fun paxValue(record: String, key: String): String? {
        for (line in record.split('\n')) {
            val space = line.indexOf(' ')
            if (space < 0) continue
            val entry = line.substring(space + 1)
            val equals = entry.indexOf('=')
            if (equals > 0 && entry.substring(0, equals) == key) {
                return entry.substring(equals + 1).trimEnd('\n')
            }
        }
        return null
    }

    /** ustar splits long paths across a 155-byte prefix and the 100-byte name. */
    private fun ByteArray.name(): String {
        val name = string(0, 100)
        val prefix = if (string(257, 6).startsWith("ustar")) string(345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * Numeric fields are octal ASCII. GNU writes values too large for the field
     * (files > 8 GB, high uids) as base-256 with the top bit of byte 0 set.
     */
    private fun ByteArray.octal(offset: Int, length: Int): Long {
        if (this[offset].toInt() and 0x80 != 0) {
            var value = (this[offset].toLong() and 0x7F)
            for (i in 1 until length) value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
            return value
        }
        var value = 0L
        for (i in offset until offset + length) {
            val c = this[i].toInt()
            if (c == 0 || c == ' '.code) {
                if (value != 0L) break else continue
            }
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
        }
        return value
    }

    /** The header checksum is computed with the checksum field itself read as spaces. */
    private fun ByteArray.checksumValid(): Boolean {
        val stored = octal(148, 8)
        if (stored == 0L) return false
        var signed = 0L
        var unsigned = 0L
        for (i in indices) {
            val byte = if (i in 148 until 156) ' '.code.toByte() else this[i]
            signed += byte.toLong()
            unsigned += byte.toLong() and 0xFF
        }
        // Historic tars disagreed on signedness; accept either reading.
        return stored == unsigned || stored == signed
    }

    /** Fills [into] completely. Returns false only at a clean end of stream. */
    private fun InputStream.readFully(into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val n = read(into, offset, into.size - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw IOException("Archive truncated")
            }
            offset += n
        }
        return true
    }

    /**
     * Reads an entry body as text, always consuming exactly [size] bytes.
     *
     * Only the first `buffer.size` bytes are kept - a path longer than 64 KB is not
     * a real path - but the rest is still drained so the stream stays aligned.
     */
    private fun InputStream.readString(size: Long, buffer: ByteArray): String {
        val kept = size.coerceAtMost(buffer.size.toLong()).toInt()
        var offset = 0
        while (offset < kept) {
            val n = read(buffer, offset, kept - offset)
            if (n < 0) break
            offset += n
        }
        skipFully(size - offset)
        // GNU long-name payloads are NUL-terminated.
        return String(buffer, 0, offset, Charsets.UTF_8)
            .trimEnd(NUL, ' ', NEWLINE)
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        val scratch = ByteArray(BLOCK)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() may legitimately return 0; fall back to reading.
            val n = read(scratch, 0, minOf(remaining, BLOCK.toLong()).toInt())
            if (n < 0) return
            remaining -= n
        }
    }
}
