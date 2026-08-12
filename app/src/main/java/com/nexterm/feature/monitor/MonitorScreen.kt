package com.nexterm.feature.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MonitorViewModel @Inject constructor(monitor: SystemMonitor) : ViewModel() {
    /** WhileSubscribed: sampling stops when the screen goes away, so it costs nothing idle. */
    val snapshot: StateFlow<SystemSnapshot?> = monitor.samples()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), null)
}

/**
 * Live device metrics.
 *
 * Everything on this screen is read from /proc, ActivityManager, StatFs and
 * BatteryManager at the moment it is shown. Where the platform refuses a read, the
 * row says so instead of showing a plausible-looking zero.
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val sample = snapshot

    if (sample == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Reading device state…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            MetricCard("CPU") {
                if (sample.cpuPercent == null) {
                    Text(
                        text = sample.note ?: "CPU usage is not readable on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Bar("Total", sample.cpuPercent / 100f, "%.1f%%".format(sample.cpuPercent))
                }
                sample.perCorePercent.forEachIndexed { index, percent ->
                    Bar("cpu$index", percent / 100f, "%.0f%%".format(percent))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stat("Cores", sample.coreCount.toString())
                    sample.loadAverage?.let { (one, five, fifteen) ->
                        Stat("Load", "%.2f %.2f %.2f".format(one, five, fifteen))
                    }
                }
            }
        }

        item {
            MetricCard("Memory") {
                val total = sample.memoryTotalKb
                val available = sample.memoryAvailableKb
                if (total != null && available != null && total > 0) {
                    val used = (total - available).coerceAtLeast(0)
                    Bar("Used", used.toFloat() / total, "${kb(used)} / ${kb(total)}")
                }
                val swapTotal = sample.swapTotalKb
                val swapFree = sample.swapFreeKb
                if (swapTotal != null && swapFree != null && swapTotal > 0) {
                    Bar("Swap", (swapTotal - swapFree).toFloat() / swapTotal, "${kb(swapTotal - swapFree)} / ${kb(swapTotal)}")
                }
                Bar(
                    label = "App heap",
                    fraction = sample.appHeapUsedKb.toFloat() / sample.appHeapMaxKb.coerceAtLeast(1),
                    value = "${kb(sample.appHeapUsedKb)} / ${kb(sample.appHeapMaxKb)}",
                )
            }
        }

        item {
            MetricCard("Storage") {
                val total = sample.internalTotalBytes
                if (total > 0) {
                    val used = total - sample.internalFreeBytes
                    Bar("Internal", used.toFloat() / total, "${bytes(used)} / ${bytes(total)}")
                }
            }
        }

        item {
            MetricCard("Device") {
                sample.uptimeSeconds?.let { Stat("Uptime", uptime(it)) }
                sample.processCount?.let { Stat("Processes", it.toString()) }
                sample.batteryPercent?.let { Stat("Battery", "$it%") }
                sample.batteryTemperatureC?.let { Stat("Battery temp", "%.1f °C".format(it)) }
                Stat("ABI", sample.abi)
                Text(
                    text = sample.kernel,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Box(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Bar(label: String, fraction: Float, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun kb(value: Long): String = when {
    value < 1024 -> "$value KiB"
    value < 1024 * 1024 -> "%.1f MiB".format(value / 1024.0)
    else -> "%.2f GiB".format(value / (1024.0 * 1024))
}

private fun bytes(value: Long): String = when {
    value < 1024 * 1024 -> "%.1f KiB".format(value / 1024.0)
    value < 1024L * 1024 * 1024 -> "%.1f MiB".format(value / (1024.0 * 1024))
    else -> "%.2f GiB".format(value / (1024.0 * 1024 * 1024))
}

private fun uptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}
