package com.nexterm.feature.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexterm.core.terminal.ExitStatus
import com.nexterm.core.terminal.SessionKind
import com.nexterm.data.model.TabGroup
import com.nexterm.feature.sessions.TabWithSession

/** Everything the tab sheet can do, all of it a real operation on a real session. */
data class TabSheetActions(
    val onRename: (tabId: String, name: String) -> Unit,
    val onDuplicate: (tabId: String) -> Unit,
    val onRestart: (tabId: String) -> Unit,
    val onSplitWith: (tabId: String) -> Unit,
    val onSetGroup: (tabId: String, groupId: Long?) -> Unit,
    val onCreateGroup: (tabId: String) -> Unit,
    val onClose: (tabId: String) -> Unit,
)

/**
 * The long-press menu for a tab.
 *
 * A phone tab is too small for an inline menu, so the actions live in a sheet that
 * shows what the tab actually is — its kind, its working directory, whether the
 * process is still alive — before offering to act on it. Restart is offered whether
 * or not the session is running, because a wedged shell looks identical to a live one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSheet(
    item: TabWithSession,
    groups: List<TabGroup>,
    actions: TabSheetActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val tabId = item.tab.id

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = item.session?.title?.takeIf { it.isNotBlank() } ?: item.tab.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.tab.workingDirectory,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        item.session == null -> "Not started yet."
                        item.isRunning -> "Running · pid ${item.session?.pid ?: "?"}"
                        // The decoded headline, not the raw number: 255 from proot in
                        // particular means something quite different from what it looks
                        // like. The full explanation is in the pane's exit banner.
                        else -> ExitStatus.describe(
                            status = item.exitStatus ?: 0,
                            kind = item.session?.kind ?: SessionKind.LOCAL,
                        ).headline
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetAction(Icons.Default.Edit, "Rename") { renaming = true }
            SheetAction(Icons.Default.ContentCopy, "Duplicate") {
                onDismiss(); actions.onDuplicate(tabId)
            }
            SheetAction(Icons.Default.Refresh, "Restart the session") {
                onDismiss(); actions.onRestart(tabId)
            }
            SheetAction(Icons.Default.VerticalSplit, "Show in the second pane") {
                onDismiss(); actions.onSplitWith(tabId)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Group",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            groups.forEach { group ->
                GroupRow(
                    group = group,
                    selected = item.tab.groupId == group.id,
                    onClick = { actions.onSetGroup(tabId, group.id) },
                )
            }
            if (item.tab.groupId != null) {
                SheetAction(Icons.Default.Folder, "Remove from its group") {
                    actions.onSetGroup(tabId, null)
                }
            }
            SheetAction(Icons.Default.Folder, "New group with this tab") {
                onDismiss(); actions.onCreateGroup(tabId)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetAction(
                icon = Icons.Default.Close,
                label = "Close this tab",
                tint = MaterialTheme.colorScheme.error,
            ) {
                if (item.isRunning) confirmClose = true else { onDismiss(); actions.onClose(tabId) }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(item.tab.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename tab") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    onDismiss()
                    actions.onRename(tabId, name)
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    // A running process is about to be killed. That is worth one tap of friction.
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close this tab?") },
            text = {
                Text(
                    "The shell running in it will be terminated. Anything it is in " +
                        "the middle of doing will stop.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onDismiss()
                    actions.onClose(tabId)
                }) { Text("Close", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun GroupRow(group: TabGroup, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(group.color)))
        Text(
            text = group.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "current",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
