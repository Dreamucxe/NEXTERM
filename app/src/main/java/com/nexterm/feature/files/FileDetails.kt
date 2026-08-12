package com.nexterm.feature.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Everything the filesystem actually knows about one entry.
 *
 * The permission row is only offered when the backing provider says it can change
 * modes; on SAF it is absent entirely, because SAF has no concept of a mode bit and
 * a control that silently did nothing would be a lie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsSheet(
    node: FileNode,
    capabilities: ProviderCapabilities,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onPermissions: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(node.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = node.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow("Type", if (node.isDirectory) "Folder" else if (node.isSymlink) "Symbolic link" else "File")
            if (!node.isDirectory) DetailRow("Size", "${formatSize(node.sizeBytes)} (${node.sizeBytes} bytes)")
            DetailRow(
                "Modified",
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(node.lastModified)),
            )
            node.permissions?.let { DetailRow("Permissions", it) }
            node.owner?.let { DetailRow("Owner", it + (node.group?.let { g -> ":$g" } ?: "")) }
            node.linkTarget?.let { DetailRow("Links to", it) }
            DetailRow(
                "Access",
                buildList {
                    if (node.canRead) add("read")
                    if (node.canWrite) add("write")
                    if (node.canExecute) add("execute")
                }.joinToString(", ").ifEmpty { "none" },
            )

            if (capabilities.canChangePermissions && node.permissions != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                PermissionEditor(current = node.permissions, onApply = onPermissions)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!node.isDirectory && capabilities.canRead) {
                    TextButton(onClick = onEdit) { Text("Edit text") }
                }
                if (capabilities.canRename) TextButton(onClick = onRename) { Text("Rename") }
                if (capabilities.canDelete) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/**
 * A chmod grid.
 *
 * Nine checkboxes rather than a text field, because a typo in an octal mode can lock
 * a user out of their own file and there is no confirmation dialog that would catch it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionEditor(current: String, onApply: (Int) -> Unit) {
    var bits by remember(current) { mutableStateOf(parsePermissions(current)) }
    val labels = listOf("r", "w", "x")

    Text("Permissions", style = MaterialTheme.typography.labelLarge)
    for ((rowIndex, who) in listOf("Owner", "Group", "Other").withIndex()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(who, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
            for ((bitIndex, label) in labels.withIndex()) {
                val mask = 1 shl (8 - (rowIndex * 3 + bitIndex))
                FilterChip(
                    selected = bits and mask != 0,
                    onClick = { bits = bits xor mask },
                    label = { Text(label, fontFamily = FontFamily.Monospace) },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "0" + bits.toString(8).padStart(3, '0'),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = { onApply(bits) }) { Text("Apply") }
    }
}

/** Turns "rwxr-xr-x" (or "-rwxr-xr-x") into the nine low bits of a mode. */
internal fun parsePermissions(text: String): Int {
    val body = text.takeLast(9)
    if (body.length < 9) return 0
    var bits = 0
    for (i in 0 until 9) {
        if (body[i] != '-') bits = bits or (1 shl (8 - i))
    }
    return bits
}

/**
 * The built-in text editor.
 *
 * It loads through the same provider the listing came from, so it reads a Shizuku
 * path or a SAF document with no special casing. Loading is a real read that can
 * fail; the failure is shown rather than replaced with an empty buffer.
 */
@Composable
fun TextEditorDialog(
    node: FileNode,
    load: suspend () -> Result<String>,
    save: suspend (String) -> Result<Unit>,
    readOnly: Boolean,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(node.path) {
        load().onSuccess { content = it }.onFailure { error = it.message ?: "That file could not be read." }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(node.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (readOnly) "Read-only — this provider cannot write here" else node.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(if (dirty) "Discard" else "Close") }
                    if (!readOnly) {
                        TextButton(
                            enabled = content != null && !saving && dirty,
                            onClick = {
                                val body = content ?: return@TextButton
                                saving = true
                                error = null
                                scope.launch {
                                    save(body)
                                        .onSuccess {
                                            saving = false
                                            dirty = false
                                            onDismiss()
                                        }
                                        .onFailure {
                                            saving = false
                                            error = it.message ?: "The file could not be written."
                                        }
                                }
                            },
                        ) { Text(if (saving) "Saving…" else "Save") }
                    }
                }

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                when {
                    error != null && content == null -> Box(Modifier.fillMaxSize())

                    content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    else -> OutlinedTextField(
                        value = content.orEmpty(),
                        onValueChange = {
                            content = it
                            dirty = true
                        },
                        readOnly = readOnly,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
