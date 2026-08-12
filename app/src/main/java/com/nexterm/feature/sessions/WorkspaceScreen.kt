package com.nexterm.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nexterm.core.terminal.ExitStatus
import com.nexterm.core.terminal.SessionKind
import com.nexterm.data.model.TerminalTheme
import com.nexterm.data.model.ToolbarKey
import com.nexterm.data.preferences.Settings
import com.nexterm.feature.terminal.KeyboardToolbar
import com.nexterm.feature.terminal.TerminalPane
import com.nexterm.feature.terminal.TerminalPaneCallbacks
import com.nexterm.feature.terminal.TerminalPaneState
import com.termux.terminal.TerminalEmulator

/** Everything the workspace body needs, collected so the screen stays declarative. */
data class WorkspaceRenderState(
    val tabs: List<TabWithSession>,
    val activeTabId: String?,
    val split: SplitState,
    val settings: Settings,
    val theme: TerminalTheme,
    val toolbarKeys: List<ToolbarKey>,
    val modifiers: Modifiers,
)

/** The actions a workspace pane can raise. Wired to the ViewModel by the caller. */
data class WorkspaceActions(
    val onResize: (tabId: String, columns: Int, rows: Int) -> Unit,
    val onText: (tabId: String, text: String) -> Unit,
    val onKey: (tabId: String, event: android.view.KeyEvent) -> Boolean,
    val onFontScale: (Float) -> Unit,
    val onSelection: (tabId: String, text: String?) -> Unit,
    val onPaneMenu: (tabId: String) -> Unit,
    val onToolbarKey: (tabId: String, key: ToolbarKey) -> Unit,
    val onSplitRatio: (Float) -> Unit,
    val emulatorFor: (tabId: String) -> TerminalEmulator?,
)

/**
 * The terminal workspace body: one pane, or two with a draggable divider.
 *
 * Both panes are real sessions with their own PTY. The split is a layout decision
 * only — nothing about a session changes when it moves between panes.
 */
@Composable
fun WorkspaceBody(
    state: WorkspaceRenderState,
    actions: WorkspaceActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val primary = state.tabs.firstOrNull { it.tab.id == state.activeTabId }
            val secondary = state.split.secondaryTabId
                ?.let { id -> state.tabs.firstOrNull { it.tab.id == id } }
                ?.takeIf { state.split.enabled }

            when {
                primary == null -> EmptyWorkspace()

                secondary == null -> SessionPane(
                    item = primary,
                    state = state,
                    actions = actions,
                    focused = true,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> SplitPanes(
                    primary = primary,
                    secondary = secondary,
                    state = state,
                    actions = actions,
                )
            }
        }

        if (state.settings.showKeyboardToolbar && state.activeTabId != null) {
            KeyboardToolbar(
                keys = state.toolbarKeys,
                modifiers = state.modifiers,
                onKey = { key -> actions.onToolbarKey(state.activeTabId, key) },
            )
        }
    }
}

@Composable
private fun SplitPanes(
    primary: TabWithSession,
    secondary: TabWithSession,
    state: WorkspaceRenderState,
    actions: WorkspaceActions,
) {
    val ratio = state.split.ratio.coerceIn(0.15f, 0.85f)
    // The divider reports its drag as a fraction, which needs the extent of the
    // container it is dividing — measured here, where that container actually is.
    var extentPx by remember { mutableFloatStateOf(1f) }
    val measure = Modifier.onSizeChanged { size ->
        extentPx = (if (state.split.vertical) size.width else size.height).toFloat().coerceAtLeast(1f)
    }

    if (state.split.vertical) {
        Row(modifier = Modifier.fillMaxSize().then(measure)) {
            SessionPane(primary, state, actions, focused = true, modifier = Modifier.weight(ratio).fillMaxHeight())
            SplitDivider(vertical = true) { delta -> actions.onSplitRatio(ratio + delta / extentPx) }
            SessionPane(secondary, state, actions, focused = false, modifier = Modifier.weight(1f - ratio).fillMaxHeight())
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().then(measure)) {
            SessionPane(primary, state, actions, focused = true, modifier = Modifier.weight(ratio).fillMaxWidth())
            SplitDivider(vertical = false) { delta -> actions.onSplitRatio(ratio + delta / extentPx) }
            SessionPane(secondary, state, actions, focused = false, modifier = Modifier.weight(1f - ratio).fillMaxWidth())
        }
    }
}

/**
 * The draggable seam between panes.
 *
 * 10dp wide: thin enough to read as a divider, thick enough to grab with a thumb.
 * The drag is handed upward in pixels and converted against the measured container,
 * so this composable never needs to know how large the workspace is.
 */
@Composable
private fun SplitDivider(vertical: Boolean, onDrag: (deltaPx: Float) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .then(if (vertical) Modifier.width(10.dp).fillMaxHeight() else Modifier.height(10.dp).fillMaxWidth())
            .background(scheme.surfaceContainerHighest)
            .pointerInput(vertical) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(if (vertical) dragAmount.x else dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (vertical) Modifier.width(2.dp).height(28.dp) else Modifier.height(2.dp).width(28.dp))
                .background(scheme.outline),
        )
    }
}

@Composable
private fun SessionPane(
    item: TabWithSession,
    state: WorkspaceRenderState,
    actions: WorkspaceActions,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val tabId = item.tab.id
    val paneState = TerminalPaneState(
        emulator = actions.emulatorFor(tabId),
        revision = item.session?.revision ?: 0L,
        theme = state.theme,
        fontSizeSp = state.settings.fontSizeSp,
        lineSpacing = state.settings.lineSpacing,
        cursorStyle = state.settings.cursorStyle,
        cursorBlink = state.settings.cursorBlink,
        isRunning = item.isRunning,
    )
    val callbacks = remember(tabId) {
        TerminalPaneCallbacks(
            onResize = { columns, rows -> actions.onResize(tabId, columns, rows) },
            onText = { text -> actions.onText(tabId, text) },
            onKey = { event -> actions.onKey(tabId, event) },
            onFontScale = actions.onFontScale,
            onSelectionChanged = { text -> actions.onSelection(tabId, text) },
            onContextMenu = { actions.onPaneMenu(tabId) },
        )
    }

    Column(modifier = modifier) {
        TerminalPane(
            state = paneState,
            callbacks = callbacks,
            requestFocus = focused,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        item.exitStatus?.let { status ->
            ExitBanner(
                status = status,
                kind = item.session?.kind ?: SessionKind.LOCAL,
                output = item.session?.exitOutput,
                launchCommand = item.session?.launchCommand.orEmpty(),
            )
        }
    }
}

/**
 * Says plainly why the process ended, and shows what it said. Never hidden.
 *
 * A bare "status 255" is not debuggable, so the number is decoded and the session's
 * own last output is kept next to it. The launch details sit behind a toggle because
 * they are long and only wanted when something has actually gone wrong.
 */
@Composable
private fun ExitBanner(
    status: Int,
    kind: SessionKind,
    output: String?,
    launchCommand: List<String>,
) {
    val scheme = MaterialTheme.colorScheme
    val description = remember(status, kind) { ExitStatus.describe(status, kind) }
    var expanded by remember { mutableStateOf(false) }
    val container = if (description.failed) scheme.errorContainer else scheme.surfaceContainerHighest
    val onContainer = if (description.failed) scheme.onErrorContainer else scheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = description.headline,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer,
        )
        description.explanation?.let { explanation ->
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        val hasDetails = !output.isNullOrBlank() || launchCommand.isNotEmpty()
        if (hasDetails) {
            Text(
                text = if (expanded) "Hide details" else "Show what the session printed",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!output.isNullOrBlank()) {
                    DetailBlock("Last output", output, onContainer)
                }
                if (launchCommand.isNotEmpty()) {
                    DetailBlock(
                        title = "Launched",
                        // One argument per line: a proot argv is far too long to read
                        // wrapped, and the bind mounts are the part worth checking.
                        body = launchCommand.joinToString("\n"),
                        color = onContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, body: String, color: Color) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

@Composable
private fun EmptyWorkspace() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No session open.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
