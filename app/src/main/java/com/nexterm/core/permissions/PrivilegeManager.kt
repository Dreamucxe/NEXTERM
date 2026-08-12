package com.nexterm.core.permissions

import android.content.Context
import android.content.pm.PackageManager
import com.nexterm.core.terminal.PrivilegeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What NEXTERM can currently do, and how it found out. */
data class PrivilegeState(
    val level: PrivilegeLevel = PrivilegeLevel.UNPRIVILEGED,
    val rootAvailable: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_INSTALLED,
    /** Path of the su binary that answered, for the details expander. */
    val suPath: String? = null,
)

/** Shizuku's lifecycle, which is more granular than "installed or not". */
enum class ShizukuState {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_PERMISSION_DENIED,
    RUNNING_PERMISSION_GRANTED,
}

/**
 * Detects the privilege NEXTERM actually has. Nothing here assumes elevation: every
 * check is a real probe, and the UI shows exactly what these probes found (spec §19).
 *
 * Root detection deliberately *runs* `su -c id` rather than merely looking for a
 * binary on disk, because the presence of `/system/xbin/su` says nothing about
 * whether this app is authorised to use it. A prompt may appear on the device the
 * first time; that is the user granting access, which is the point.
 */
@Singleton
class PrivilegeManager @Inject constructor(
    private val context: Context,
) {
    private val _state = MutableStateFlow(PrivilegeState())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    /** Re-probes everything. Safe to call on resume; it does no work on the main thread. */
    suspend fun refresh(): PrivilegeState = withContext(Dispatchers.IO) {
        val shizuku = detectShizuku()
        val (rootAvailable, suPath) = detectRoot()

        val level = when {
            rootAvailable -> PrivilegeLevel.ROOT
            shizuku == ShizukuState.RUNNING_PERMISSION_GRANTED -> PrivilegeLevel.SHIZUKU
            else -> PrivilegeLevel.UNPRIVILEGED
        }

        PrivilegeState(level, rootAvailable, shizuku, suPath).also { _state.value = it }
    }

    /**
     * Probes for usable root by executing `id` through su and checking for uid 0.
     * A short timeout keeps a hung or absent su daemon from blocking the caller.
     */
    private fun detectRoot(): Pair<Boolean, String?> {
        val candidates = SU_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        if (candidates.isEmpty()) return false to null

        for (path in candidates) {
            val granted = runCatching {
                val process = ProcessBuilder(path, "-c", "id -u")
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(ROOT_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@runCatching false
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.exitValue() == 0 && output.lineSequence().any { it.trim() == "0" }
            }.getOrDefault(false)

            if (granted) return true to path
        }
        return false to null
    }

    /**
     * Shizuku is reached reflectively so NEXTERM installs and runs normally on
     * devices without it — a hard link would make the class unresolvable at runtime.
     */
    private fun detectShizuku(): ShizukuState {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        if (!installed) return ShizukuState.NOT_INSTALLED

        return runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val pingBinder = shizuku.getMethod("pingBinder")
            val alive = pingBinder.invoke(null) as? Boolean ?: false
            if (!alive) return ShizukuState.INSTALLED_NOT_RUNNING

            val checkPermission = shizuku.getMethod("checkSelfPermission")
            val result = checkPermission.invoke(null) as? Int ?: PackageManager.PERMISSION_DENIED
            if (result == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.RUNNING_PERMISSION_GRANTED
            } else {
                ShizukuState.RUNNING_PERMISSION_DENIED
            }
        }.getOrDefault(ShizukuState.INSTALLED_NOT_RUNNING)
    }

    /** Asks Shizuku for permission. No-op when Shizuku is not running. */
    fun requestShizukuPermission(requestCode: Int = SHIZUKU_REQUEST_CODE) {
        runCatching {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            shizuku.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
        }
    }

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/debug_ramdisk/su",
        )
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val ROOT_PROBE_TIMEOUT_MS = 3_000L
        const val SHIZUKU_REQUEST_CODE = 4919
    }
}
