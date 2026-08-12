package com.nexterm.feature.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexterm.core.terminal.SessionKind
import com.nexterm.feature.distros.EnvironmentsScreen
import com.nexterm.feature.files.FileBrowserScreen
import com.nexterm.feature.monitor.MonitorScreen
import com.nexterm.feature.palette.CommandPalette
import com.nexterm.feature.palette.PaletteCommand
import com.nexterm.feature.sessions.TerminalScreen
import com.nexterm.feature.sessions.WorkspaceViewModel
import com.nexterm.feature.settings.SettingsScreen
import com.nexterm.feature.ssh.SshScreen

/** The five places the app can be. Kept as an enum so the bar cannot drift from it. */
enum class Destination(val label: String, val icon: ImageVector) {
    TERMINAL("Terminal", Icons.Default.Terminal),
    FILES("Files", Icons.Default.Folder),
    ENVIRONMENTS("Linux", Icons.Default.Dns),
    MONITOR("Monitor", Icons.Default.Insights),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * The app shell.
 *
 * The terminal stays composed at all times rather than being swapped out when another
 * screen is showing. Removing it from the tree would dispose the view that owns the
 * renderer's binding to a live PTY, so the other screens are drawn over it on an
 * opaque surface instead — every session keeps running while the user reads settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NextermApp(workspaceViewModel: WorkspaceViewModel = hiltViewModel()) {
    var destination by remember { mutableStateOf(Destination.TERMINAL) }
    var paletteOpen by remember { mutableStateOf(false) }
    var quickCommandsOpen by remember { mutableStateOf(false) }
    var sshOpen by remember { mutableStateOf(false) }

    // Drives both the bottom bar and the pane insets below.
    val imeVisible = WindowInsets.isImeVisible

    val tabs by workspaceViewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by workspaceViewModel.activeTabId.collectAsStateWithLifecycle()
    val split by workspaceViewModel.split.collectAsStateWithLifecycle()
    val quickCommands by workspaceViewModel.quickCommands.collectAsStateWithLifecycle()
    val snippets by workspaceViewModel.snippets.collectAsStateWithLifecycle()

    // Back should retrace the user's steps through the shell before leaving the app.
    BackHandler(enabled = sshOpen || destination != Destination.TERMINAL) {
        if (sshOpen) sshOpen = false else destination = Destination.TERMINAL
    }

    Scaffold(
        topBar = {
            if (destination != Destination.TERMINAL) {
                TopAppBar(
                    title = { Text(destination.label, style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = { paletteOpen = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Command palette")
                        }
                    },
                )
            }
        },
        bottomBar = {
            // The keyboard covers this bar anyway, and a terminal needs every row it
            // can get while the user is typing, so it steps aside instead of being
            // padded around.
            if (!imeVisible) {
                NavigationBar {
                    Destination.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = destination == entry && !sshOpen,
                            onClick = { sshOpen = false; destination = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The window is edge-to-edge, so `adjustResize` no longer shrinks it and the
        // IME would otherwise sit on top of the bottom rows — the prompt included,
        // which reads as "typing does nothing". Padding for the keyboard here shrinks
        // the pane instead, which also makes the view report a smaller grid, so the
        // shell re-wraps to the space it actually has. The bottom inset is dropped
        // while the IME is up because the keyboard already covers that area.
        val layoutDirection = LocalLayoutDirection.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    bottom = if (imeVisible) 0.dp else padding.calculateBottomPadding(),
                )
                .imePadding(),
        ) {
            TerminalScreen(
                viewModel = workspaceViewModel,
                showQuickCommands = quickCommandsOpen,
                onQuickCommandsDismiss = { quickCommandsOpen = false },
            )

            when (destination) {
                Destination.TERMINAL -> Unit

                Destination.FILES -> Backdrop {
                    FileBrowserScreen(
                        onOpenInTerminal = { path ->
                            workspaceViewModel.newTab(workingDirectory = path)
                            destination = Destination.TERMINAL
                        },
                    )
                }

                Destination.ENVIRONMENTS -> Backdrop {
                    EnvironmentsScreen(
                        onLaunch = { distroId ->
                            workspaceViewModel.newTab(kind = SessionKind.PROOT, distroId = distroId)
                            destination = Destination.TERMINAL
                        },
                    )
                }

                Destination.MONITOR -> Backdrop { MonitorScreen() }

                Destination.SETTINGS -> Backdrop { SettingsScreen() }
            }

            if (sshOpen) {
                Backdrop {
                    SshScreen(
                        onConnect = { profileId ->
                            workspaceViewModel.newTab(kind = SessionKind.SSH, sshProfileId = profileId)
                            sshOpen = false
                            destination = Destination.TERMINAL
                        },
                    )
                }
            }
        }
    }

    if (paletteOpen) {
        val commands = remember(tabs, activeTabId, split, quickCommands, snippets) {
            buildList {
                add(
                    PaletteCommand(
                        id = "new-tab",
                        title = "New session",
                        group = "Session",
                        keywords = "tab shell terminal open",
                        run = { workspaceViewModel.newTab(); destination = Destination.TERMINAL },
                    ),
                )
                add(
                    PaletteCommand(
                        id = "ssh-hosts",
                        title = "SSH hosts",
                        group = "Session",
                        keywords = "remote connect server",
                        run = { sshOpen = true },
                    ),
                )
                activeTabId?.let { id ->
                    add(
                        PaletteCommand(
                            id = "restart-tab",
                            title = "Restart this session",
                            group = "Session",
                            keywords = "reload respawn",
                            run = { workspaceViewModel.restartTab(id) },
                        ),
                    )
                    add(
                        PaletteCommand(
                            id = "close-tab",
                            title = "Close this session",
                            group = "Session",
                            keywords = "kill quit exit",
                            run = { workspaceViewModel.closeTab(id) },
                        ),
                    )
                    add(
                        PaletteCommand(
                            id = "paste",
                            title = "Paste into the terminal",
                            group = "Session",
                            keywords = "clipboard",
                            run = { workspaceViewModel.paste(id) },
                        ),
                    )
                }
                add(
                    PaletteCommand(
                        id = "toggle-split",
                        title = if (split.enabled) "Close split view" else "Open split view",
                        group = "Layout",
                        keywords = "pane two side",
                        run = { workspaceViewModel.toggleSplit(); destination = Destination.TERMINAL },
                    ),
                )
                add(
                    PaletteCommand(
                        id = "split-orientation",
                        title = if (split.vertical) "Stack split panes" else "Place split panes side by side",
                        group = "Layout",
                        keywords = "horizontal vertical orientation",
                        run = { workspaceViewModel.setSplitOrientation(!split.vertical) },
                    ),
                )
                add(
                    PaletteCommand(
                        id = "font-bigger",
                        title = "Increase the font size",
                        group = "Appearance",
                        keywords = "zoom larger text",
                        run = { workspaceViewModel.changeFontSize(1f) },
                    ),
                )
                add(
                    PaletteCommand(
                        id = "font-smaller",
                        title = "Decrease the font size",
                        group = "Appearance",
                        keywords = "zoom smaller text",
                        run = { workspaceViewModel.changeFontSize(-1f) },
                    ),
                )
                add(
                    PaletteCommand(
                        id = "quick-commands",
                        title = "Quick commands and snippets",
                        group = "Run",
                        keywords = "saved template",
                        run = { destination = Destination.TERMINAL; quickCommandsOpen = true },
                    ),
                )

                Destination.entries.forEach { entry ->
                    add(
                        PaletteCommand(
                            id = "go-${entry.name.lowercase()}",
                            title = "Go to ${entry.label}",
                            group = "Navigate",
                            keywords = "open screen",
                            run = { sshOpen = false; destination = entry },
                        ),
                    )
                }

                // Switching to a specific tab by name is the palette's most-used job
                // once a user has more sessions than the strip can show at once.
                tabs.filter { it.tab.id != activeTabId }.forEach { item ->
                    add(
                        PaletteCommand(
                            id = "tab-${item.tab.id}",
                            title = "Switch to ${item.session?.title?.takeIf { t -> t.isNotBlank() } ?: item.tab.name}",
                            subtitle = item.tab.workingDirectory,
                            group = "Tabs",
                            run = {
                                workspaceViewModel.selectTab(item.tab.id)
                                destination = Destination.TERMINAL
                            },
                        ),
                    )
                }

                activeTabId?.let { id ->
                    quickCommands.forEach { command ->
                        add(
                            PaletteCommand(
                                id = "qc-${command.id}",
                                title = command.label,
                                subtitle = command.command,
                                group = "Run",
                                run = {
                                    workspaceViewModel.runCommand(id, command.command, command.runImmediately)
                                    destination = Destination.TERMINAL
                                },
                            ),
                        )
                    }
                    snippets.filter { it.placeholders.isEmpty() }.forEach { snippet ->
                        add(
                            PaletteCommand(
                                id = "sn-${snippet.id}",
                                title = snippet.name,
                                subtitle = snippet.template,
                                group = "Run",
                                run = {
                                    workspaceViewModel.runCommand(id, snippet.template, execute = false)
                                    destination = Destination.TERMINAL
                                },
                            ),
                        )
                    }
                }
            }
        }
        CommandPalette(commands = commands, onDismiss = { paletteOpen = false })
    }
}

/** An opaque layer, so the terminal held behind a screen never shows through it. */
@Composable
private fun Backdrop(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}
