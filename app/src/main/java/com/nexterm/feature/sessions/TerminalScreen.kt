package com.nexterm.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexterm.data.model.Snippet
import com.nexterm.data.preferences.TabPosition
import com.nexterm.feature.tabs.TabSheet
import com.nexterm.feature.tabs.TabSheetActions
import com.nexterm.feature.tabs.TabStrip

/**
 * The terminal screen: tab strip, panes, keyboard row, and the sheets that act on them.
 *
 * This composable only arranges things and holds which sheet is open. Every action it
 * raises goes to [WorkspaceViewModel], which owns the sessions — no composable here
 * starts a process, writes to a PTY or touches the database.
 */
@Composable
fun TerminalScreen(
    viewModel: WorkspaceViewModel,
    showQuickCommands: Boolean,
    onQuickCommandsDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val split by viewModel.split.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val toolbarKeys by viewModel.toolbarKeys.collectAsStateWithLifecycle()
    val modifiers by viewModel.modifiers.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val quickCommands by viewModel.quickCommands.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()

    var sheetTabId by remember { mutableStateOf<String?>(null) }
    var groupNameFor by remember { mutableStateOf<String?>(null) }
    var snippetToFill by remember { mutableStateOf<Snippet?>(null) }

    val renderState = WorkspaceRenderState(
        tabs = tabs,
        activeTabId = activeTabId,
        split = split,
        settings = settings,
        theme = theme,
        toolbarKeys = toolbarKeys,
        modifiers = modifiers,
    )
    val actions = remember(viewModel) {
        WorkspaceActions(
            onResize = viewModel::resize,
            onText = viewModel::sendText,
            onKey = viewModel::sendKeyEvent,
            onFontScale = viewModel::scaleFontSize,
            onSelection = { _, _ -> },
            onPaneMenu = { tabId -> sheetTabId = tabId },
            onToolbarKey = viewModel::sendToolbarKey,
            onSplitRatio = viewModel::setSplitRatio,
            emulatorFor = viewModel::emulator,
        )
    }

    val strip = @Composable {
        TabStrip(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelect = viewModel::selectTab,
            onClose = viewModel::closeTab,
            onNewTab = { viewModel.newTab() },
            onLongPress = { tabId -> sheetTabId = tabId },
            secondaryTabId = split.secondaryTabId.takeIf { split.enabled },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (settings.tabPosition == TabPosition.TOP) strip()

        Box(Modifier.weight(1f)) {
            WorkspaceBody(state = renderState, actions = actions)

            // Errors stack at the top of the pane rather than replacing it, so a
            // failed session is visible without the working ones disappearing.
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                messages.takeLast(3).forEach { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                            message.detail?.let {
                                // Capped and scrollable: a launch failure reports what
                                // the failed program actually printed, which can run to
                                // many lines, and an uncapped card would push its own
                                // Dismiss button off the bottom of the screen.
                                Column(
                                    Modifier
                                        .heightIn(max = 180.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            TextButton(onClick = { viewModel.dismissMessage(message.id) }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }

        if (settings.tabPosition == TabPosition.BOTTOM) strip()
    }

    sheetTabId?.let { id ->
        tabs.firstOrNull { it.tab.id == id }?.let { item ->
            TabSheet(
                item = item,
                groups = groups,
                onDismiss = { sheetTabId = null },
                actions = TabSheetActions(
                    onRename = viewModel::renameTab,
                    onDuplicate = viewModel::duplicateTab,
                    onRestart = viewModel::restartTab,
                    onSplitWith = viewModel::setSplitSecondary,
                    onSetGroup = viewModel::setTabGroup,
                    onCreateGroup = { tabId -> groupNameFor = tabId },
                    onClose = viewModel::closeTab,
                ),
            )
        } ?: run { sheetTabId = null }
    }

    groupNameFor?.let { tabId ->
        var name by remember(tabId) { mutableStateOf("Group") }
        AlertDialog(
            onDismissRequest = { groupNameFor = null },
            title = { Text("New group") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createGroup(name.trim().ifEmpty { "Group" }, GROUP_COLORS.random(), tabId)
                    groupNameFor = null
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { groupNameFor = null }) { Text("Cancel") } },
        )
    }

    if (showQuickCommands) {
        QuickCommandSheet(
            quickCommands = quickCommands,
            snippets = snippets,
            enabled = activeTabId != null,
            onRun = { command, execute ->
                activeTabId?.let { viewModel.runCommand(it, command, execute) }
                onQuickCommandsDismiss()
            },
            onFillSnippet = { snippet -> snippetToFill = snippet },
            onDismiss = onQuickCommandsDismiss,
        )
    }

    snippetToFill?.let { snippet ->
        SnippetDialog(
            snippet = snippet,
            onDismiss = { snippetToFill = null },
            onRun = { rendered ->
                activeTabId?.let { viewModel.runCommand(it, rendered, execute = false) }
                snippetToFill = null
                onQuickCommandsDismiss()
            },
        )
    }
}

/** Saved commands and snippets, run against the tab that is currently on screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickCommandSheet(
    quickCommands: List<com.nexterm.data.model.QuickCommand>,
    snippets: List<Snippet>,
    enabled: Boolean,
    onRun: (command: String, execute: Boolean) -> Unit,
    onFillSnippet: (Snippet) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(bottom = 20.dp)) {
            if (!enabled) {
                item {
                    Text(
                        text = "Open a session first — there is nothing to send this to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            item {
                Text(
                    text = "Quick commands",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            items(quickCommands, key = { "q${it.id}" }) { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onRun(command.command, command.runImmediately) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(command.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = command.command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!command.runImmediately) {
                        Text(
                            text = "types only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (snippets.isNotEmpty()) {
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                item {
                    Text(
                        text = "Snippets",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                    )
                }
                items(snippets, key = { "s${it.id}" }) { snippet ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                if (snippet.placeholders.isEmpty()) {
                                    onRun(snippet.template, false)
                                } else {
                                    onFillSnippet(snippet)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(snippet.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = snippet.template,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fills a snippet's `$PLACEHOLDER` values before it is typed into the shell.
 *
 * The result is typed but never executed: a snippet is a template the user is about
 * to edit, and pressing Enter for them would run a half-finished command.
 */
@Composable
private fun SnippetDialog(
    snippet: Snippet,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    val placeholders = remember(snippet.id) { snippet.placeholders }
    val values = remember(snippet.id) {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            placeholders.forEach { put(it, "") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(snippet.name) },
        text = {
            Column {
                snippet.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                placeholders.forEach { name ->
                    OutlinedTextField(
                        value = values[name].orEmpty(),
                        onValueChange = { values[name] = it },
                        label = { Text(name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                Text(
                    text = snippet.render(values.toMap()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(snippet.render(values.toMap())) }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Group colours, matching the Material tonal range the tab strip draws against. */
private val GROUP_COLORS = listOf(
    0xFF4F8DF7.toInt(), 0xFF3DBE8B.toInt(), 0xFFE0A33E.toInt(),
    0xFFE0655B.toInt(), 0xFFA277E0.toInt(), 0xFF44BDCF.toInt(),
)
