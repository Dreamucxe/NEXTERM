package com.nexterm.feature.distros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexterm.core.permissions.ShizukuState
import com.nexterm.core.terminal.PrivilegeLevel
import com.nexterm.data.model.DistroStatus

/**
 * Linux environments and the privilege the device actually grants.
 *
 * The two belong on one screen because they answer the same question: what can this
 * app really run here? Every claim on this screen is the result of a live probe —
 * an `su` that was actually executed, a Shizuku binder that was actually pinged, a
 * proot binary that was actually found on disk.
 */
@Composable
fun EnvironmentsScreen(
    onLaunch: (distroId: String) -> Unit,
    viewModel: EnvironmentsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val distros by viewModel.distros.collectAsStateWithLifecycle()
    val privilege by viewModel.privilege.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { PrivilegeCard(privilege, onRequestShizuku = viewModel::requestShizuku, onRefresh = viewModel::refresh) }
        item { ProotCard(ui) }

        ui.message?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        ui.messageDetail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                    }
                }
            }
        }

        item {
            Text(
                text = "Available environments",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
        }

        items(distros, key = { it.id }) { distro ->
            DistroCard(
                distro = distro,
                prootAvailable = ui.proot?.available == true,
                onInstall = { viewModel.install(distro.id) },
                onCancel = { viewModel.cancel(distro.id) },
                onUninstall = { viewModel.requestUninstall(distro.id) },
                onLaunch = { onLaunch(distro.id) },
            )
        }

        item { Box(Modifier.height(24.dp)) }
    }

    ui.pendingUninstall?.let { id ->
        val name = distros.firstOrNull { it.id == id }?.catalog?.displayName ?: id
        AlertDialog(
            onDismissRequest = viewModel::cancelUninstall,
            title = { Text("Remove $name?") },
            text = {
                Text(
                    "This deletes the whole root filesystem, including anything you " +
                        "installed or wrote inside it. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUninstall) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelUninstall) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PrivilegeCard(
    privilege: com.nexterm.core.permissions.PrivilegeState,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Privilege", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (privilege.level) {
                    PrivilegeLevel.ROOT -> "Root granted"
                    PrivilegeLevel.SHIZUKU -> "Shizuku connected"
                    PrivilegeLevel.UNPRIVILEGED -> "Running as this app only"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                text = if (privilege.rootAvailable) {
                    "An su binary at ${privilege.suPath} answered a privilege check."
                } else {
                    "No su binary on this device answered a privilege check."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Text(
                text = when (privilege.shizuku) {
                    ShizukuState.NOT_INSTALLED -> "Shizuku is not installed."
                    ShizukuState.INSTALLED_NOT_RUNNING -> "Shizuku is installed but not running."
                    ShizukuState.RUNNING_PERMISSION_DENIED -> "Shizuku is running but has not granted NEXTERM permission."
                    ShizukuState.RUNNING_PERMISSION_GRANTED -> "Shizuku is running and has granted NEXTERM permission."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            // The single most commonly misrepresented fact in terminal apps.
            Text(
                text = "Shizuku runs commands as the shell user (uid 2000), not as root. " +
                    "It can reach places this app cannot, such as /data/local/tmp and " +
                    "many system settings, but it is not unrestricted root access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (privilege.shizuku == ShizukuState.RUNNING_PERMISSION_DENIED) {
                    Button(onClick = onRequestShizuku) { Text("Request Shizuku") }
                }
                OutlinedButton(onClick = onRefresh) { Text("Re-check") }
            }
        }
    }
}

@Composable
private fun ProotCard(ui: EnvironmentsUiState) {
    val proot = ui.proot ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("proot", style = MaterialTheme.typography.titleSmall)
            if (proot.available) {
                Text(
                    text = when (proot.source) {
                        com.nexterm.core.terminal.ProotLocator.Source.BUNDLED_ANDROID ->
                            "The Termux project's proot, bundled with NEXTERM as a " +
                                "native library — the only place Android permits an app " +
                                "to execute a binary from."

                        com.nexterm.core.terminal.ProotLocator.Source.BUNDLED ->
                            "Upstream proot, bundled with NEXTERM as a statically " +
                                "linked fallback."

                        com.nexterm.core.terminal.ProotLocator.Source.TERMUX ->
                            "Provided by an installed Termux."

                        null -> "Found."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = proot.path.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The loader decides whether guest programs can start at all, so its
                // absence is reported here instead of surfacing later as a session
                // that dies immediately for no visible reason.
                Text(
                    text = proot.loaderPath
                        ?.let { "Loader: $it" }
                        ?: "No loader was found beside this proot. It will try to " +
                            "unpack its own, which an app sandbox usually refuses, " +
                            "so sessions may fail to start.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (proot.loaderPath != null) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = "No executable proot was found, so environments can be " +
                        "installed and browsed but not entered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Android has refused to execute binaries from app-writable " +
                        "storage since Android 10, so NEXTERM cannot fetch one at " +
                        "runtime. It ships proot inside the APK as a native library " +
                        "for 64-bit ARM (arm64-v8a); on any other CPU the only " +
                        "remaining source is an installed Termux whose copy is " +
                        "readable by other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (proot.checkedPaths.isNotEmpty()) {
                    Text(
                        text = "Checked:\n" + proot.checkedPaths.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            ui.architecture?.let {
                Text(
                    text = "Device architecture: ${it.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DistroCard(
    distro: InstallableDistro,
    prootAvailable: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    onLaunch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${distro.catalog.displayName} ${distro.catalog.version}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = distro.catalog.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = when {
                        distro.isInstalled -> "Installed"
                        distro.state.status == DistroStatus.BROKEN -> "Broken"
                        !distro.supportedHere -> "Unsupported"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (distro.state.status == DistroStatus.BROKEN) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            distro.progress?.let { progress ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        text = buildString {
                            append(
                                when (progress.phase) {
                                    InstallProgress.Phase.DOWNLOADING -> "Downloading"
                                    InstallProgress.Phase.VERIFYING -> "Verifying checksum"
                                    InstallProgress.Phase.EXTRACTING -> "Extracting"
                                    InstallProgress.Phase.FINALISING -> "Finalising"
                                },
                            )
                            progress.totalBytes?.let {
                                append("  ${mib(progress.bytesProcessed)} / ${mib(it)}")
                            }
                            if (progress.entriesWritten > 0) append("  ${progress.entriesWritten} files")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }

            if (!distro.supportedHere) {
                Text(
                    text = "No root filesystem is published for this device's CPU " +
                        "(${distro.catalog.supportedArchitectures.joinToString { it.displayName }} only).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                when {
                    distro.isBusy -> OutlinedButton(onClick = onCancel) { Text("Cancel") }

                    distro.isInstalled -> {
                        Button(onClick = onLaunch, enabled = prootAvailable) { Text("Open session") }
                        OutlinedButton(onClick = onUninstall) { Text("Remove") }
                    }

                    else -> Button(onClick = onInstall, enabled = distro.supportedHere) {
                        Text("Install · ${mib(distro.catalog.rootfs.values.firstOrNull()?.downloadBytes ?: 0)}")
                    }
                }
            }

            if (distro.isInstalled && !prootAvailable) {
                Text(
                    text = "Installed, but no proot is available to enter it on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun mib(bytes: Long): String =
    if (bytes <= 0) "—" else "%.0f MiB".format(bytes / (1024.0 * 1024))
