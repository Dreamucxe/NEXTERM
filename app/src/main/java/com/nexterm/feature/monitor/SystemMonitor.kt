package com.nexterm.feature.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.nexterm.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One sample of the device's real state. Every field comes from a live read. */
data class SystemSnapshot(
    val cpuPercent: Float?,
    val perCorePercent: List<Float>,
    val coreCount: Int,
    val loadAverage: Triple<Double, Double, Double>?,
    val memoryTotalKb: Long?,
    val memoryAvailableKb: Long?,
    val swapTotalKb: Long?,
    val swapFreeKb: Long?,
    val appHeapUsedKb: Long,
    val appHeapMaxKb: Long,
    val internalFreeBytes: Long,
    val internalTotalBytes: Long,
    val uptimeSeconds: Long?,
    val processCount: Int?,
    val batteryPercent: Int?,
    val batteryTemperatureC: Float?,
    val kernel: String,
    val abi: String,
    /** Set when the kernel would not tell us something, so the UI can say why. */
    val note: String? = null,
)

/** A cumulative /proc/stat CPU line, in jiffies. */
private data class CpuTimes(val busy: Long, val total: Long)

/**
 * Reads real device metrics.
 *
 * Android 8 onward blocks /proc/stat and most of /proc for third-party apps under
 * the "hidepid"-style SELinux policy, and on many devices these reads simply fail.
 * Nothing here fabricates a number when that happens: an unreadable file becomes
 * `null` and the UI says the kernel would not answer, which is the honest report.
 */
@Singleton
class SystemMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var previousAggregate: CpuTimes? = null
    private var previousCores: List<CpuTimes> = emptyList()

    /** Emits a fresh sample every [intervalMs]. Cold: sampling stops with collection. */
    fun samples(intervalMs: Long = 2_000L): Flow<SystemSnapshot> = flow {
        while (true) {
            emit(sample())
            delay(intervalMs)
        }
    }.flowOn(io)

    fun sample(): SystemSnapshot {
        val statLines = readLines("/proc/stat")
        val cpuLines = statLines.filter { it.startsWith("cpu") }
        val aggregate = cpuLines.firstOrNull { it.startsWith("cpu ") }?.let(::parseCpuLine)
        val cores = cpuLines.filter { it.matches(CPU_CORE.toRegex()) }.mapNotNull(::parseCpuLine)

        val cpuPercent = aggregate?.let { now ->
            previousAggregate?.let { before -> percentBetween(before, now) }.also { previousAggregate = now }
        }
        val perCore = if (cores.isEmpty()) emptyList() else {
            val before = previousCores
            previousCores = cores
            if (before.size == cores.size) {
                cores.mapIndexed { index, now -> percentBetween(before[index], now) ?: 0f }
            } else {
                emptyList()
            }
        }

        val meminfo = readKeyValues("/proc/meminfo")
        val memoryInfo = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }

        val runtime = Runtime.getRuntime()
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)

        val note = when {
            statLines.isEmpty() -> "The kernel does not allow this app to read /proc/stat on this device, so CPU figures are unavailable."
            aggregate != null && cpuPercent == null -> "Collecting the first CPU sample…"
            else -> null
        }

        return SystemSnapshot(
            cpuPercent = cpuPercent,
            perCorePercent = perCore,
            coreCount = if (cores.isNotEmpty()) cores.size else runtime.availableProcessors(),
            loadAverage = readLoadAverage(),
            // /proc/meminfo is readable far more often than /proc/stat, but when it is
            // not, ActivityManager still reports totals — so fall back rather than lie.
            memoryTotalKb = meminfo["MemTotal"] ?: (memoryInfo.totalMem / 1024),
            memoryAvailableKb = meminfo["MemAvailable"] ?: (memoryInfo.availMem / 1024),
            swapTotalKb = meminfo["SwapTotal"],
            swapFreeKb = meminfo["SwapFree"],
            appHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            appHeapMaxKb = runtime.maxMemory() / 1024,
            internalFreeBytes = statFs.availableBytes,
            internalTotalBytes = statFs.totalBytes,
            uptimeSeconds = readLines("/proc/uptime").firstOrNull()
                ?.substringBefore(' ')?.toDoubleOrNull()?.toLong(),
            processCount = File("/proc").listFiles()
                ?.count { it.isDirectory && it.name.all(Char::isDigit) },
            batteryPercent = batteryPercent(),
            batteryTemperatureC = batteryTemperature(),
            kernel = readLines("/proc/version").firstOrNull()?.take(120)
                ?: "Linux ${System.getProperty("os.version").orEmpty()}",
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            note = note,
        )
    }

    private fun percentBetween(before: CpuTimes, now: CpuTimes): Float? {
        val totalDelta = now.total - before.total
        if (totalDelta <= 0) return null
        val busyDelta = now.busy - before.busy
        return (busyDelta.toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    private fun parseCpuLine(line: String): CpuTimes? {
        val fields = line.trim().split(WHITESPACE.toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        // user nice system idle iowait irq softirq steal…  Idle and iowait are the
        // only non-busy columns; everything else counts as work.
        val idle = fields[3] + (fields.getOrNull(4) ?: 0)
        val total = fields.sum()
        return CpuTimes(busy = total - idle, total = total)
    }

    private fun readLoadAverage(): Triple<Double, Double, Double>? {
        val parts = readLines("/proc/loadavg").firstOrNull()?.trim()?.split(' ') ?: return null
        if (parts.size < 3) return null
        val one = parts[0].toDoubleOrNull() ?: return null
        val five = parts[1].toDoubleOrNull() ?: return null
        val fifteen = parts[2].toDoubleOrNull() ?: return null
        return Triple(one, five, fifteen)
    }

    private fun batteryPercent(): Int? = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
    }.getOrNull()

    private fun batteryTemperature(): Float? = runCatching {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        @Suppress("DEPRECATION")
        val intent = context.registerReceiver(null, filter) ?: return null
        intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
    }.getOrNull()

    private fun readLines(path: String): List<String> = runCatching {
        File(path).takeIf { it.canRead() }?.readLines().orEmpty()
    }.getOrDefault(emptyList())

    private fun readKeyValues(path: String): Map<String, Long> = readLines(path).mapNotNull { line ->
        val key = line.substringBefore(':', "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val value = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            ?: return@mapNotNull null
        key to value
    }.toMap()

    private companion object {
        const val WHITESPACE = "\\s+"
        const val CPU_CORE = "^cpu\\d+\\s.*"
    }
}
