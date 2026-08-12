package com.nexterm.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

/**
 * The file browser.
 *
 * Everything shown here came from a real directory read. When a location cannot be
 * read — no root, no SAF grant, a restricted `/Android/data` — the reason the provider
 * gave is displayed instead of an empty list pretending the folder is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onOpenInTerminal: (String) -> Unit,
    viewModel: FileBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    var newFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileNode?>(null) }
    var detailsTarget by remember { mutableStateOf<FileNode?>(null) }
    var editTarget by remember { mutableStateOf<FileNode?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onFolderPicked(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.providerName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.path.ifEmpty { "—" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goUp() }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Parent folder")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        BrowserOverflowMenu(
                            expanded = overflowOpen,
                            onDismiss = { overflowOpen = false },
                            showHidden = settings.showHiddenFiles,
                            sortOrder = settings.fileSortOrder,
                            canCreate = state.capabilities.canCreateDirectory,
                            onToggleHidden = { viewModel.setShowHidden(!settings.showHiddenFiles) },
                            onSort = { viewModel.setSortOrder(it) },
                            onNewFolder = { newFolderDialog = true },
                            onGrantFolder = { folderPicker.launch(null) },
                            onBookmark = { viewModel.bookmarkCurrent() },
                            onOpenInTerminal = {
                                viewModel.terminalPath()?.let(onOpenInTerminal)
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.isSelecting || state.clipboard != null) {
                SelectionBar(
                    state = state,
                    onCopy = { viewModel.copySelection(move = false) },
                    onCut = { viewModel.copySelection(move = true) },
                    onPaste = { viewModel.paste() },
                    onDelete = { viewModel.requestDelete() },
                    onClear = {
                        viewModel.clearSelection()
                        viewModel.clearClipboard()
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LocationChips(
                state = state,
                bookmarks = bookmarks.map { it.label to it.path },
                onProvider = { viewModel.switchProvider(it) },
                onShortcut = { viewModel.open(it) },
                onGrantFolder = { folderPicker.launch(null) },
            )

            state.capabilities.limitation?.let { note -> LimitationNote(note) }
            state.error?.let { message ->
                ErrorNote(message, state.errorDetail) { viewModel.dismissError() }
            }
            state.busyNote?.let { note -> BusyNote(note) }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.entries.isEmpty() && state.error == null -> Text(
                        text = "This folder is empty.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.entries, key = { it.path }) { node ->
                            FileRow(
                                node = node,
                                selected = node.path in state.selection,
                                selecting = state.isSelecting,
                                onClick = {
                                    when {
                                        state.isSelecting -> viewModel.toggleSelection(node.path)
                                        node.isDirectory -> viewModel.open(node.path)
                                        else -> detailsTarget = node
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(node.path) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (newFolderDialog) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            confirmLabel = "Create",
            onDismiss = { newFolderDialog = false },
            onConfirm = { name ->
                newFolderDialog = false
                viewModel.createDirectory(name)
            },
        )
    }

    renameTarget?.let { node ->
        TextPromptDialog(
            title = "Rename",
            label = "New name",
            initial = node.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                viewModel.rename(node, name)
            },
        )
    }

    state.pendingDelete?.let { request ->
        DeleteConfirmationDialog(
            request = request,
            onDismiss = { viewModel.cancelDelete() },
            onConfirm = { viewModel.confirmDelete() },
        )
    }

    detailsTarget?.let { node ->
        FileDetailsSheet(
            node = node,
            capabilities = state.capabilities,
            onDismiss = { detailsTarget = null },
            onRename = {
                detailsTarget = null
                renameTarget = node
            },
            onDelete = {
                detailsTarget = null
                viewModel.requestDelete(listOf(node))
            },
            onEdit = {
                detailsTarget = null
                editTarget = node
            },
            onPermissions = { mode -> viewModel.setPermissions(node, mode) },
        )
    }

    editTarget?.let { node ->
        TextEditorDialog(
            node = node,
            load = { viewModel.readText(node) },
            save = { text -> viewModel.writeText(node, text) },
            readOnly = !state.capabilities.canWrite,
            onDismiss = { editTarget = null },
        )
    }
}

@Composable
private fun LocationChips(
    state: BrowserState,
    bookmarks: List<Pair<String, String>>,
    onProvider: (String) -> Unit,
    onShortcut: (String) -> Unit,
    onGrantFolder: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.providers, key = { it.id }) { summary ->
            AssistChip(
                onClick = { if (summary.usable) onProvider(summary.id) else Unit },
                enabled = summary.usable,
                label = { Text(summary.displayName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Storage, null, Modifier.size(15.dp)) },
            )
        }
        item {
            AssistChip(
                onClick = onGrantFolder,
                label = { Text("Open folder", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null, Modifier.size(15.dp)) },
            )
        }
        items(state.shortcuts, key = { it.second }) { (label, path) ->
            AssistChip(
                onClick = { onShortcut(path) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
        items(bookmarks, key = { it.second }) { (label, path) ->
            AssistChip(
                onClick = { onShortcut(path.substringAfter(':', path)) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun FileRow(
    node: FileNode,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.primaryContainer else scheme.surface)
            .rowClick(onClick, onLongClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                node.isSymlink -> Icons.Default.Link
                node.isDirectory -> Icons.Default.Folder
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (node.isDirectory) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (node.canRead) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Text(
                text = subtitleFor(node),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selecting) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) scheme.primary else scheme.outlineVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

private fun subtitleFor(node: FileNode): String = buildString {
    node.permissions?.let { append(it).append("  ") }
    if (!node.isDirectory) append(formatSize(node.sizeBytes)).append("  ")
    append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(node.lastModified)))
    node.linkTarget?.let { append("  → ").append(it) }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
}

@Composable
private fun SelectionBar(
    state: BrowserState,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when {
                state.isSelecting -> "${state.selection.size} selected"
                state.clipboard != null -> "${state.clipboard.nodes.size} on clipboard"
                else -> ""
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (state.isSelecting) {
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy") }
            IconButton(onClick = onCut, enabled = state.capabilities.canDelete) {
                Icon(Icons.Default.ContentCut, "Cut")
            }
            IconButton(onClick = onDelete, enabled = state.capabilities.canDelete) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (state.clipboard != null) {
            IconButton(onClick = onPaste, enabled = state.capabilities.canWrite) {
                Icon(Icons.Default.ContentPaste, "Paste")
            }
        }
        TextButton(onClick = onClear) { Text("Done") }
    }
}

@Composable
private fun BrowserOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    showHidden: Boolean,
    sortOrder: com.nexterm.data.preferences.FileSortOrder,
    canCreate: Boolean,
    onToggleHidden: () -> Unit,
    onSort: (com.nexterm.data.preferences.FileSortOrder) -> Unit,
    onNewFolder: () -> Unit,
    onGrantFolder: () -> Unit,
    onBookmark: () -> Unit,
    onOpenInTerminal: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
            onClick = { onToggleHidden(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("New folder") },
            enabled = canCreate,
            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
            onClick = { onNewFolder(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Grant a folder…") },
            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
            onClick = { onGrantFolder(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Bookmark this folder") },
            onClick = { onBookmark(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Open in terminal") },
            leadingIcon = { Icon(Icons.Default.Terminal, null) },
            onClick = { onOpenInTerminal(); onDismiss() },
        )
        HorizontalDivider()
        for (order in com.nexterm.data.preferences.FileSortOrder.entries) {
            DropdownMenuItem(
                text = { Text("Sort by ${order.name.lowercase()}") },
                trailingIcon = { if (order == sortOrder) Icon(Icons.Default.Check, null) },
                onClick = { onSort(order); onDismiss() },
            )
        }
    }
}

@Composable
private fun LimitationNote(note: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun ErrorNote(message: String, detail: String?, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun BusyNote(note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(note, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The confirmation the spec requires before anything is removed.
 *
 * It names what will go and says plainly that it is permanent — Android has no
 * trash for arbitrary paths, so there is no undo to offer.
 */
@Composable
private fun DeleteConfirmationDialog(
    request: DeleteRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (request.nodes.size == 1) "Delete ${request.nodes.first().name}?" else "Delete ${request.nodes.size} items?") },
        text = {
            Column {
                Text(
                    text = if (request.recursive) {
                        "This removes the folders and everything inside them. It cannot be undone."
                    } else {
                        "This cannot be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                request.nodes.take(6).forEach { node ->
                    Text(
                        text = node.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (request.nodes.size > 6) {
                    Text("…and ${request.nodes.size - 6} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    confirmLabel: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Combined click confined to one helper so the experimental opt-in stays local. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.rowClick(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
