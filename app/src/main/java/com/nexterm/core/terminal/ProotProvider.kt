package com.nexterm.core.terminal

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Locates a usable `proot` binary.
 *
 * Why this is not simply "download proot and run it": since API 29 Android enforces
 * W^X for apps, so a binary written into app-writable storage cannot be executed
 * (`execve` returns EACCES). The only app-owned directory that is both populated at
 * install time and executable is the native library directory, so a bundled proot has
 * to ship as `lib*.so` inside the APK.
 *
 * NEXTERM therefore bundles proot for arm64-v8a twice, each with the loader it was
 * built against. The loader matters as much as the binary: proot execve()s a small
 * helper ELF in place of each guest program, and left to itself it writes that helper
 * to a temporary file (or memfd) and executes *that* — precisely the operation Android
 * blocks. Pointing `PROOT_LOADER` at a bundled copy avoids the extraction path, and the
 * two have to be a matched pair. See [ProotProvider.environmentFor].
 *
 * The two builds are not interchangeable:
 *  • `libproot.so` is the Termux project's proot — the fork that is actually maintained
 *    against Android, carrying the patches an app process needs. It is tried first.
 *    Being dynamically linked it ships with `libtalloc.so` and `libandroid-shmem.so`
 *    beside it and needs a library search path to find them, which
 *    [ProotProvider.environmentFor] supplies.
 *  • `libproot-static.so` is upstream proot, statically linked, kept as a fallback for
 *    a device where the Termux build will not run at all.
 *
 * After those comes a proot already installed by Termux, which is only reachable if
 * that install left it world-executable — usually it has not, since Termux keeps its
 * prefix at 0700.
 *
 * When none exists this reports honestly rather than pretending a session started.
 */
class ProotLocator(private val context: Context) {

    /** Result of looking for an executable proot on this device. */
    sealed interface Result {
        /**
         * @param loaderPath the helper ELF to hand proot via `PROOT_LOADER`, or null
         *   when none was found next to the binary — in which case proot falls back to
         *   extracting its own, which W^X will usually refuse.
         */
        data class Found(
            val path: String,
            val source: Source,
            val loaderPath: String? = null,
        ) : Result

        data class NotFound(val checked: List<String>) : Result
    }

    enum class Source {
        /** The Termux project's Android-patched proot, bundled in NEXTERM's own APK. */
        BUNDLED_ANDROID,

        /** Upstream proot, bundled in NEXTERM's own APK as a static fallback. */
        BUNDLED,

        /** Provided by an installed Termux, reachable because it is world-executable. */
        TERMUX,
    }

    /**
     * Every proot on this device that can be executed, best first.
     *
     * More than one is worth having because "usable" cannot be settled by looking: a
     * binary that executes may still be unable to trace a guest under this device's
     * sandbox. [ProotProvider] chooses between them by trying them.
     */
    fun locateAll(): List<Result.Found> = buildList {
        // Bundled as native libraries. jniLibs entries are unpacked at install time
        // into a directory that is mounted executable, which is what makes this legal.
        val nativeDir = context.applicationInfo.nativeLibraryDir
        for (bundled in BUNDLED) {
            val binary = File(nativeDir, bundled.binary)
            if (!runCatching { binary.canExecute() }.getOrDefault(false)) continue
            add(
                Result.Found(
                    path = binary.absolutePath,
                    source = bundled.source,
                    // Each build gets the loader it was compiled against. Crossing them
                    // is not a degraded session, it is a guest that dies on exec.
                    loaderPath = File(nativeDir, bundled.loader)
                        .takeIf { it.canRead() }
                        ?.absolutePath,
                ),
            )
        }

        // A Termux install. Only usable if its files are world-executable, which
        // depends on the user's Termux setup; canExecute() is the real test.
        for (path in TERMUX_PATHS) {
            if (!runCatching { File(path).canExecute() }.getOrDefault(false)) continue
            add(
                Result.Found(
                    path = path,
                    source = Source.TERMUX,
                    // Termux's fork keeps its loader beside the prefix, and its own
                    // proot already defaults to it; only pass it on if it is readable.
                    loaderPath = TERMUX_LOADERS
                        .map(::File)
                        .firstOrNull { runCatching { it.canRead() }.getOrDefault(false) }
                        ?.absolutePath,
                ),
            )
        }
    }

    fun locate(): Result = locateAll().firstOrNull() ?: Result.NotFound(checkedPaths())

    /** Everywhere [locateAll] looks, for reporting a device where nothing was found. */
    private fun checkedPaths(): List<String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return BUNDLED.map { File(nativeDir, it.binary).absolutePath } + TERMUX_PATHS
    }

    /** A bundled proot and the loader ELF built to go with it. */
    private data class Bundled(val binary: String, val loader: String, val source: Source)

    private companion object {
        /**
         * The bundled builds, in the order they are tried.
         *
         * Android only unpacks files matching `lib*.so` from an APK, so everything
         * bundled here — the proot binaries, their loaders, and proot's own shared
         * libraries — has to be named that way whatever it actually is. That is also
         * why `libtalloc.so.2` ships as `libtalloc.so`, with proot's `DT_NEEDED` entry
         * patched to match.
         */
        val BUNDLED = listOf(
            Bundled("libproot.so", "libproot-loader.so", Source.BUNDLED_ANDROID),
            Bundled("libproot-static.so", "libproot-static-loader.so", Source.BUNDLED),
        )

        val TERMUX_PATHS = listOf(
            "/data/data/com.termux/files/usr/bin/proot",
            "/data/user/0/com.termux/files/usr/bin/proot",
        )

        val TERMUX_LOADERS = listOf(
            "/data/data/com.termux/files/usr/libexec/proot/loader",
            "/data/user/0/com.termux/files/usr/libexec/proot/loader",
        )
    }
}

/**
 * Runs a shell inside a proot Linux rootfs.
 *
 * proot re-implements chroot/bind-mounts in userspace via ptrace, so a full distro
 * runs unprivileged. The PTY, emulator and I/O plumbing are identical to a local
 * session: this provider only assembles the proot command line and delegates to
 * [LocalPtyProvider].
 */
class ProotProvider(
    private val context: Context,
    private val locator: ProotLocator,
    private val local: LocalPtyProvider,
    /** Resolves a distro id to its extracted rootfs directory. */
    private val rootfsResolver: (distroId: String) -> File?,
) : TerminalProvider {

    override val kind: SessionKind = SessionKind.PROOT

    override fun availability(): ProviderAvailability = when (val found = locator.locate()) {
        // A proot session is still a local PTY underneath, so the engine has to be usable
        // too; report its failure rather than a misleading proot error.
        is ProotLocator.Result.Found -> local.availability()

        is ProotLocator.Result.NotFound -> ProviderAvailability.Unavailable(
            reason = "Linux environments need a proot binary, and none is available " +
                "for this device's CPU.",
            detail = "NEXTERM bundles proot as a native library for arm64-v8a, which " +
                "is the only place Android lets an app execute a binary from: " +
                "app-writable storage is mounted W^X (enforced since API 29), so a " +
                "downloaded proot could never be run.\n\nThis device reports ABIs: " +
                Build.SUPPORTED_ABIS.joinToString(", ") +
                "\n\nChecked:\n" + found.checked.joinToString("\n") { "  • $it" },
            remedy = listOf(
                "Use NEXTERM on a 64-bit ARM device, which the bundled proot supports",
                "Install Termux and make its proot readable, and NEXTERM will reuse it",
                "Rootfs downloads and management still work; only starting a " +
                    "session inside one requires proot",
            ),
        )
    }

    /**
     * Which binary and interception mode were found to work, keyed by rootfs path.
     *
     * The answer is a property of the device, not of the rootfs, but a rootfs is what a
     * probe can actually be run against — and keying it this way means a second distro
     * still gets checked rather than inheriting a verdict from a different tree.
     */
    private val launchers = ConcurrentHashMap<String, Launcher>()

    override suspend fun createSession(
        request: SessionRequest,
        listener: TerminalListener,
    ): TerminalHandle {
        (availability() as? ProviderAvailability.Unavailable)?.let {
            throw TerminalStartException(it.reason, it.detail)
        }

        val distroId = request.distroId
            ?: throw TerminalStartException("No Linux environment was selected.")
        val rootfs = rootfsResolver(distroId)
            ?: throw TerminalStartException(
                "That Linux environment is not installed.",
                "No rootfs directory was found for '$distroId'.",
            )
        if (!File(rootfs, "bin").isDirectory && !File(rootfs, "usr/bin").isDirectory) {
            throw TerminalStartException(
                "That Linux environment looks incomplete and may need reinstalling.",
                "${rootfs.absolutePath} has no bin/ or usr/bin/ directory.",
            )
        }

        val candidates = locator.locateAll()
        if (candidates.isEmpty()) {
            throw TerminalStartException("The proot binary is no longer available.")
        }

        val guestShell = request.executable.ifBlank { "/bin/sh" }
        val guestHome = "/root"

        // Which binary and which interception mode this device accepts is measured, not
        // assumed. See [resolveLauncher].
        val launcher = resolveLauncher(
            candidates = candidates,
            rootfs = rootfs,
            request = request,
            guestShell = guestShell,
            guestHome = guestHome,
        )

        return local.createSession(
            request.copy(
                kind = SessionKind.LOCAL,
                executable = launcher.found.path,
                args = buildArgs(
                    rootfs = rootfs,
                    request = request,
                    guestHome = guestHome,
                    guestProgram = listOf(guestShell) + request.args,
                ),
                environment = environmentFor(launcher.found, request, launcher.noSeccomp),
                workingDirectory = context.filesDir.absolutePath,
            ),
            listener,
        )
    }

    /**
     * The environment proot itself runs in. Not the guest's — `env -i` replaces that.
     *
     * `PROOT_TMP_DIR` must be app-writable. `PROOT_LOADER` matters as much as the binary
     * itself: proot execve()s a helper ELF in place of every guest program, and by
     * default it materialises that helper itself (temp file, or memfd) and executes it —
     * which W^X refuses in an app sandbox. proot reads `PROOT_LOADER` in preference to
     * extracting (src/execve/enter.c), so pointing it at the copy bundled in the native
     * library directory keeps execution on the one path Android permits. If no loader
     * was found, proot is left to its own fallback rather than handed an empty path.
     *
     * `LD_LIBRARY_PATH` is what makes the Termux build runnable at all. It is
     * dynamically linked against libtalloc and libandroid-shmem, which ship beside it,
     * but a binary started with `execve` gets none of the search paths the platform sets
     * up for a library loaded from Java: bionic's linker looks at `DT_RUNPATH`,
     * `LD_LIBRARY_PATH` and the system directories, and this binary's own RUNPATH points
     * into Termux's private prefix, which is not readable here. So the directory has to
     * be named explicitly. It is harmless to the guest, whose own dynamic linker never
     * sees it: `env -i` clears the environment before the guest program starts.
     *
     * @param noSeccomp the interception mode to force, or null to leave whatever the
     *   request asked for untouched.
     */
    private fun environmentFor(
        found: ProotLocator.Result.Found,
        request: SessionRequest,
        noSeccomp: Boolean?,
    ): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
        put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
        found.loaderPath?.let { put("PROOT_LOADER", it) }
        putAll(request.environment)
        when (noSeccomp) {
            true -> put(NO_SECCOMP, "1")
            false -> remove(NO_SECCOMP)
            null -> Unit
        }
    }

    /** A proot binary, and the interception mode it was found to work in. */
    private data class Launcher(val found: ProotLocator.Result.Found, val noSeccomp: Boolean)

    /**
     * How much of a real launch a command line sets up.
     *
     * A failure is localised by taking capability away rather than by guessing: one that
     * disappears at [TRANSLATED] blames a bind mount, and one that is still there at
     * [BARE] blames the proot binary, its loader, or the rootfs itself.
     */
    private enum class LaunchStage(val label: String) {
        /** A new root and nothing else — the fewest moving parts that can still run. */
        BARE("rootfs only"),

        /** Adds the id and path translation a distro expects, still with no bind mounts. */
        TRANSLATED("rootfs + translation"),

        /** Everything a real session gets. */
        FULL("full session"),
    }

    /**
     * The proot command line, minus the binary itself.
     *
     * -0 presents the guest as uid 0 (package managers expect it), -r sets the new root,
     * and the bind mounts expose the kernel interfaces a distro needs. The guest program
     * is reached through `env -i` so it starts from a known environment rather than
     * inheriting the app's.
     *
     * These options are the intersection of what both bundled builds accept, since the
     * same argv is tried against each. Deliberately absent: --sysvipc and -L, which
     * proot-distro passes — both are Termux-fork extensions, and upstream rejects unknown
     * options outright, so a shared argv cannot carry them. Also absent: -k/--kernel-release,
     * which activates the syscall-rewriting "kompat" extension purely to change what
     * uname reports: not worth another active code path inside an already-fragile launch.
     */
    private fun buildArgs(
        rootfs: File,
        request: SessionRequest,
        guestHome: String,
        guestProgram: List<String>,
        stage: LaunchStage = LaunchStage.FULL,
        verbose: Boolean = false,
    ): List<String> = buildList {
        // -v 1 is the whole difference between a diagnosable failure and a bare number.
        // proot announces a tracee that died on a signal through VERBOSE at level 1 and
        // says nothing at level 0 — and a signal death is exactly the case where it
        // returns its uninitialised status. The silence and the useless 255 have one
        // shared cause, so raising the level is what makes such a launch speak.
        if (verbose) {
            add("-v"); add("1")
        }

        add("-r"); add(rootfs.absolutePath)

        if (stage != LaunchStage.BARE) {
            add("-0")
            add("-w"); add(guestWorkingDirectory(rootfs, request.workingDirectory, guestHome))
            add("--link2symlink")
            add("--kill-on-exit")
        }

        if (stage == LaunchStage.FULL) {
            for (bind in bindMounts(rootfs)) {
                add("-b"); add(bind)
            }
        }

        // At BARE the guest program is exec'd directly: `env` is one more guest binary
        // that could itself be what fails, so the narrowest launch does not depend on it.
        if (stage != LaunchStage.BARE) {
            add("/usr/bin/env")
            add("-i")
            add("HOME=$guestHome")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            add("LANG=C.UTF-8")
            add("USER=root")
        }
        addAll(guestProgram)
    }

    /**
     * Picks the proot binary and interception mode that actually work here, by trying them.
     *
     * Two things are unknowable from inside the app, so both are measured.
     *
     * The binary: `libproot.so` is Termux's fork, the build that is maintained against
     * Android and the one expected to answer. `libproot-static.so` is upstream, kept
     * because a dynamically linked binary has more ways to fail — a linker that cannot
     * find its libraries, a bionic that rejects it — and a statically linked fallback
     * costs nothing until it is needed.
     *
     * The mode: proot accelerates syscall interception with a seccomp filter returning
     * SECCOMP_RET_TRACE, and does so by default. That is expected to be the wrong choice
     * inside an app: Android's zygote has already installed its own filter on this
     * process and every child inherits it, the kernel applies the most restrictive
     * verdict across all installed filters, and TRACE is the weakest — so for any syscall
     * Android's policy rejects, its SIGSYS wins and the guest dies before proot can
     * rewrite the call. `PROOT_NO_SECCOMP=1` falls back to PTRACE_SYSCALL, which the
     * kernel reports *before* seccomp evaluation, leaving proot able to translate. proot
     * documents the variable as exactly this workaround. But that is a prediction about a
     * sandbox this code cannot inspect, and the opposite outcome is real: where proot is
     * itself already traced, PTRACE_SYSCALL segfaults the guest while seccomp works.
     *
     * Both failures look identical from outside — a tracee killed by a signal, the one
     * case where proot never records an exit status and returns its uninitialised -1,
     * surfacing as 255 with nothing printed. So each combination runs `sh -c 'exit 0'`
     * through the real argv, and the first that exits 0 is kept for this rootfs. It costs
     * a few short-lived processes on the first session, and it is the only way to be
     * right on a device this build cannot be tested against. If none works the session is
     * not started and [diagnose] takes over, because by then neither was the question.
     */
    private suspend fun resolveLauncher(
        candidates: List<ProotLocator.Result.Found>,
        rootfs: File,
        request: SessionRequest,
        guestShell: String,
        guestHome: String,
    ): Launcher {
        launchers[rootfs.absolutePath]?.let { return it }

        // The lightest guest program that still exercises the whole launch: proot's
        // loader, the exec, and the guest's own dynamic linker.
        val exitZero = listOf(guestShell, "-c", "exit 0")
        val modes = interceptionModes(request)
        val attempts = mutableListOf<Attempt>()

        for (found in candidates) {
            for (noSeccomp in modes) {
                val outcome = probe(
                    proot = found.path,
                    args = buildArgs(
                        rootfs = rootfs,
                        request = request,
                        guestHome = guestHome,
                        guestProgram = exitZero,
                        stage = LaunchStage.FULL,
                        verbose = true,
                    ),
                    environment = environmentFor(found, request, noSeccomp),
                )
                if (outcome.status == 0) {
                    return Launcher(found, noSeccomp)
                        .also { launchers[rootfs.absolutePath] = it }
                }
                attempts += Attempt(found, LaunchStage.FULL, noSeccomp, outcome)
            }
        }

        throw TerminalStartException(
            "This Linux environment would not start.",
            diagnose(candidates, rootfs, request, guestShell, guestHome, modes, attempts),
        )
    }

    /**
     * The interception modes worth trying: filter-off first, since it is the documented
     * Android workaround. An explicit `PROOT_NO_SECCOMP` in the request is obeyed rather
     * than measured around — someone who set it is answering this question themselves.
     */
    private fun interceptionModes(request: SessionRequest): List<Boolean> =
        request.environment[NO_SECCOMP]
            ?.let { listOf(it == "1") }
            ?: listOf(true, false)

    /**
     * Reports why nothing could start a session, in enough detail to act on.
     *
     * Three questions, ordered by how fast they narrow: what did proot actually say, how
     * little of the launch still fails, and do the binaries and the rootfs match this
     * CPU. The signal number is the pivot — it separates the sandbox refusing a system
     * call from a binary that cannot run on this device at all — and every launch here is
     * verbose precisely so that number exists.
     *
     * The narrowing ladder is run against the first binary only. It is the one expected
     * to work, and repeating four more launches per fallback would say little that the
     * full-session attempts above have not already said.
     */
    private suspend fun diagnose(
        candidates: List<ProotLocator.Result.Found>,
        rootfs: File,
        request: SessionRequest,
        guestShell: String,
        guestHome: String,
        modes: List<Boolean>,
        attempts: List<Attempt>,
    ): String {
        val primary = candidates.first()
        val exitZero = listOf(guestShell, "-c", "exit 0")
        val narrowed = mutableListOf<Attempt>()

        // Less capability each time. A stage that works accuses whatever the next stage
        // up adds; a stage that fails in every mode clears its own additions.
        for (stage in listOf(LaunchStage.TRANSLATED, LaunchStage.BARE)) {
            for (noSeccomp in modes) {
                val outcome = probe(
                    proot = primary.path,
                    args = buildArgs(
                        rootfs = rootfs,
                        request = request,
                        guestHome = guestHome,
                        guestProgram = exitZero,
                        stage = stage,
                        verbose = true,
                    ),
                    environment = environmentFor(primary, request, noSeccomp),
                )
                narrowed += Attempt(primary, stage, noSeccomp, outcome)
                if (outcome.status == 0) break
            }
        }

        val environment = environmentFor(primary, request, null)
        val version = probe(primary.path, listOf("-V"), environment)

        return buildString {
            append("proot could not run `sh -c 'exit 0'` inside ${rootfs.absolutePath} ")
            append("with any bundled binary, in any syscall-interception mode. ")
            append("Everything below is verbatim.")

            append("\n\n── every launch that was tried ──")
            (attempts + narrowed).forEach { append("\n\n").append(describe(it)) }

            append("\n\n── does the proot binary run at all ──\nproot -V → ")
            append(describeOutcome(version))
            if (version.output.isNotBlank()) append("\n").append(tail(version.output))

            append("\n\n── what is installed ──\n")
            append(facts(candidates, rootfs, guestShell, environment).joinToString("\n"))

            append("\n\n── how to read this ──\n")
            append(
                "`terminated with signal 31` (SIGSYS) means Android's own seccomp filter " +
                    "rejected a system call the guest made: the sandbox refused it, and " +
                    "nothing about the rootfs is wrong. `signal 11` (SIGSEGV) means the " +
                    "program proot exec'd crashed, which accuses the loader or binaries " +
                    "built for another CPU. A `CANNOT LINK EXECUTABLE` line is the " +
                    "device's linker refusing proot itself, before any of this starts. A " +
                    "`proot error:` line with status 1 is proot's own complaint and " +
                    "already says what it could not do.",
            )
        }
    }

    /** One launch that was tried, and what came of it. */
    private data class Attempt(
        val found: ProotLocator.Result.Found,
        val stage: LaunchStage,
        val noSeccomp: Boolean,
        val outcome: ProbeOutcome,
    )

    private fun describe(attempt: Attempt): String = buildString {
        append(File(attempt.found.path).name)
        append(", ")
        append(attempt.stage.label)
        append(", PROOT_NO_SECCOMP=")
        append(if (attempt.noSeccomp) "1" else "unset")
        append(" → ")
        append(describeOutcome(attempt.outcome))
        if (attempt.outcome.output.isNotBlank()) append("\n").append(tail(attempt.outcome.output))
    }

    private fun describeOutcome(outcome: ProbeOutcome): String = when (val status = outcome.status) {
        null -> "no exit status (timed out after ${PROBE_TIMEOUT_MS}ms, or could not start)"
        0 -> "worked"
        else -> ExitStatus.describe(status, SessionKind.PROOT).headline
    }

    /** Keeps the end of [text]: proot reports how a launch died on its last line. */
    private fun tail(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length <= MAX_OUTPUT_CHARS) trimmed
        else "…" + trimmed.takeLast(MAX_OUTPUT_CHARS)
    }

    /** The facts a launch depends on, none of which require running anything. */
    private fun facts(
        candidates: List<ProotLocator.Result.Found>,
        rootfs: File,
        guestShell: String,
        environment: Map<String, String>,
    ): List<String> = buildList {
        add(
            "device: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString("/")}",
        )
        for (found in candidates) {
            add("proot: ${found.path} (${found.source}) — ${elfSummary(File(found.path))}")
            add(
                "  loader: " + when (val loader = found.loaderPath) {
                    // Worth stating outright: without it proot materialises its own
                    // loader and execs that, the one thing an app sandbox always refuses.
                    null -> "NOT FOUND — proot will try to extract its own, which W^X " +
                        "blocks in an app sandbox"

                    else -> "$loader — ${elfSummary(File(loader))}"
                },
            )
        }
        // The Termux build is dynamically linked, so a library missing here is a launch
        // that dies in the device's linker before any of proot's own code runs.
        add("LD_LIBRARY_PATH: ${environment["LD_LIBRARY_PATH"]}")
        for (library in PROOT_LIBRARIES) {
            add("  $library: ${elfSummary(File(context.applicationInfo.nativeLibraryDir, library))}")
        }
        add("PROOT_TMP_DIR: ${environment["PROOT_TMP_DIR"]}")
        add("rootfs: ${rootfs.absolutePath}")
        for (guestPath in listOf(guestShell, "/usr/bin/env")) {
            val target = resolveInGuest(rootfs, guestPath)
            add(
                "guest $guestPath: " + when (target) {
                    null -> "MISSING inside the rootfs"
                    else -> elfSummary(target)
                },
            )
        }
    }

    /**
     * What CPU an ELF file was built for, read straight from its header.
     *
     * A guest binary for the wrong architecture is one of the few causes of an immediate
     * signal death that can be identified without running anything, and it is otherwise
     * invisible: the rootfs unpacks perfectly and only dies on exec.
     */
    private fun elfSummary(file: File): String = runCatching {
        if (!file.exists()) return "missing"
        val header = ByteArray(ELF_HEADER_BYTES)
        val read = file.inputStream().use { it.read(header) }
        if (read < ELF_HEADER_BYTES) return "too short to be an ELF file (${file.length()} bytes)"
        if (header[0] != ELF_MAGIC_0 || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return "not an ELF file"
        }
        val bits = if (header[EI_CLASS] == ELF_CLASS_64) "64-bit" else "32-bit"
        // e_machine is little-endian on every architecture Android runs on.
        val machine = (header[E_MACHINE].toInt() and 0xFF) or
            ((header[E_MACHINE + 1].toInt() and 0xFF) shl 8)
        "$bits ${ELF_MACHINES[machine] ?: "machine 0x${machine.toString(16)}"}, " +
            "${file.length()} bytes"
    }.getOrElse { "unreadable (${it.message})" }

    /**
     * Follows a guest path to the file the guest would actually exec.
     *
     * `/bin/sh` is a symlink in every Debian-family rootfs, and an absolute target has to
     * be resolved against the rootfs rather than the host — resolving it the host's way
     * would silently inspect Android's own binary instead of the guest's, and report that
     * everything is fine.
     */
    private fun resolveInGuest(rootfs: File, guestPath: String): File? {
        var current = File(rootfs, guestPath.removePrefix("/"))
        repeat(SYMLINK_HOPS) {
            val target = runCatching { Os.readlink(current.absolutePath) }.getOrNull()
                ?: return current.takeIf { it.exists() }
            current = if (target.startsWith('/')) {
                File(rootfs, target.removePrefix("/"))
            } else {
                File(current.parentFile ?: rootfs, target)
            }
        }
        return current.takeIf { it.exists() }
    }

    /**
     * Runs proot once, without a PTY, and reports what happened.
     *
     * A plain [ProcessBuilder] child inherits the app's seccomp policy exactly as the
     * PTY-backed session does, so it answers the question this asks — which binary and
     * mode the sandbox tolerates — and it lets the status and output be read directly
     * instead of through a terminal emulator. stderr is merged in because that is where
     * proot's own complaints go.
     *
     * It is not the real launch, and one difference has bitten already: ProcessBuilder
     * synthesises argv[0] from the command, while the PTY path passes its array to
     * execvp() verbatim. A probe can therefore pass with an argv the real session would
     * mangle. What keeps the two honest is that both take the same [buildArgs] output
     * paired with the same binary path — so they stay equivalent as long as neither side
     * edits the argv on its way through. See [LocalPtyProvider]'s launchCommand.
     */
    private suspend fun probe(
        proot: String,
        args: List<String>,
        environment: Map<String, String>,
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf(proot) + args).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = runCatching { builder.start() }
            .getOrElse { return@withContext ProbeOutcome(null, it.message ?: it.toString()) }

        // stdin is closed so a guest that unexpectedly reads it sees EOF and ends,
        // rather than blocking until the timeout.
        runCatching { process.outputStream.close() }

        // Process.waitFor(timeout) is API 26 and this build supports 24, so the deadline
        // is polled instead.
        var status: Int? = null
        val deadline = System.nanoTime() + PROBE_TIMEOUT_MS * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            status = runCatching { process.exitValue() }.getOrNull()
            if (status != null) break
            delay(PROBE_POLL_MS)
        }
        // Killed on timeout, which also closes the write end of the pipe below. Reading
        // only after the process is known to be gone keeps this from being able to hang
        // a session start, at the cost of capping capture at the pipe buffer — ample for
        // a probe, and a probe that somehow filled it would have hit the timeout anyway.
        if (status == null) process.destroy()

        val output = runCatching { process.inputStream.readBytes().decodeToString() }
            .getOrDefault("")
        ProbeOutcome(status, output.trim())
    }

    /** What one probe launch did: its status, or null if it never reported one. */
    private data class ProbeOutcome(val status: Int?, val output: String)

    /**
     * The directory to start in, as a path inside the guest.
     *
     * Whatever the tab was carrying arrives here, and for a freshly opened tab that is
     * a *host* path in the app sandbox. proot would warn and fall back, so a requested
     * path is only used once it is confirmed to exist inside the rootfs.
     */
    private fun guestWorkingDirectory(rootfs: File, requested: String, fallback: String): String {
        if (requested.isBlank() || !requested.startsWith('/')) return fallback
        val exists = runCatching { File(rootfs, requested.removePrefix("/")).isDirectory }
            .getOrDefault(false)
        return if (exists) requested else fallback
    }

    /**
     * Bind mounts a Linux userland expects on Android.
     *
     * Only paths that actually exist are passed. The set mirrors what proot-distro
     * binds for a Termux login, minus the entries that only make sense inside Termux's
     * own prefix, because that is the configuration Linux guests are known to boot in.
     */
    private fun bindMounts(rootfs: File): List<String> {
        val mounts = mutableListOf<String>()

        // The kernel interfaces every userland expects.
        listOf("/dev", "/proc", "/sys").forEach { if (File(it).exists()) mounts += it }

        // Android's /dev carries no fd symlink and no stdin/stdout/stderr, so a guest
        // shell loses here-strings, process substitution and /dev/stdin redirection
        // without these.
        if (!File("/dev/fd").exists() && File("/proc/self/fd").isDirectory) {
            mounts += "/proc/self/fd:/dev/fd"
        }
        listOf(0 to "stdin", 1 to "stdout", 2 to "stderr").forEach { (fd, name) ->
            if (!File("/dev/$name").exists() && File("/proc/self/fd/$fd").exists()) {
                mounts += "/proc/self/fd/$fd:/dev/$name"
            }
        }
        // /dev/random can block indefinitely on Android builds where urandom does not.
        if (File("/dev/urandom").exists()) mounts += "/dev/urandom:/dev/random"

        // Android refuses to let an app read its SELinux tree. An empty directory lets
        // guests that probe it (systemd's detectors, some package scripts) see nothing
        // rather than get an error they treat as fatal.
        File(context.filesDir, "proot/empty").let { empty ->
            if (empty.isDirectory || empty.mkdirs()) {
                mounts += "${empty.absolutePath}:/sys/fs/selinux"
            }
        }

        // /dev/shm is frequently absent on Android; give the guest a writable stand-in
        // with the sticky, world-writable mode programs expect of a shared tmp.
        File(rootfs, "tmp").let { tmp ->
            if (tmp.isDirectory || tmp.mkdirs()) {
                runCatching { Os.chmod(tmp.absolutePath, "1777".toInt(radix = 8)) }
                mounts += "${tmp.absolutePath}:/dev/shm"
            }
        }

        // Android's own trees, so a guest can still reach host binaries (getprop, am,
        // pm) and the linker configuration they need to load.
        listOf("/system", "/vendor", "/apex", "/linkerconfig", "/product")
            .forEach { if (File(it).isDirectory) mounts += it }

        // Let the guest reach app storage and, when granted, shared storage.
        mounts += "${context.filesDir.absolutePath}:/nexterm"
        Environment.getExternalStorageDirectory()?.takeIf { it.canRead() }?.let {
            mounts += "${it.absolutePath}:/sdcard"
        }
        return mounts
    }

    private companion object {
        const val NO_SECCOMP = "PROOT_NO_SECCOMP"

        /**
         * The shared libraries the Termux build is linked against, bundled beside it.
         * Reported when a launch fails, because a linker error is otherwise
         * indistinguishable from any other immediate death.
         */
        val PROOT_LIBRARIES = listOf("libtalloc.so", "libandroid-shmem.so")

        /**
         * Long enough for a cold rootfs on a slow device to start a shell and exit,
         * short enough that two failed probes do not read as the app hanging.
         */
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_POLL_MS = 25L
        const val NANOS_PER_MILLI = 1_000_000L

        /** Per-launch output cap, keeping the tail where proot reports how it died. */
        const val MAX_OUTPUT_CHARS = 1_200

        /** Symlink hops to follow inside a rootfs before giving up on a guest path. */
        const val SYMLINK_HOPS = 8

        /** Enough of an ELF header to reach e_machine, which sits at offset 18. */
        const val ELF_HEADER_BYTES = 20
        const val EI_CLASS = 4
        const val E_MACHINE = 18
        const val ELF_CLASS_64: Byte = 2
        const val ELF_MAGIC_0: Byte = 0x7F

        val ELF_MACHINES = mapOf(
            0x03 to "x86",
            0x28 to "ARM (32-bit)",
            0x3E to "x86-64",
            0xB7 to "AArch64",
            0xF3 to "RISC-V",
        )
    }
}
