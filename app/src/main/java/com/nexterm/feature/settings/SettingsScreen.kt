package com.nexterm.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexterm.data.preferences.FileSortOrder
import com.nexterm.data.preferences.Settings
import com.nexterm.data.preferences.TabPosition
import com.nexterm.data.preferences.WakeLockMode
import com.nexterm.data.repository.LicenseDocument

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themes by viewModel.themes.collectAsStateWithLifecycle()
    val license by viewModel.license.collectAsStateWithLifecycle()
    var shellDialog by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SectionHeader("Terminal") }
        item {
            SliderRow(
                label = "Font size",
                value = settings.fontSizeSp,
                range = 6f..32f,
                display = "%.0f sp".format(settings.fontSizeSp),
                onChange = viewModel::setFontSize,
            )
        }
        item {
            SliderRow(
                label = "Line spacing",
                value = settings.lineSpacing,
                range = 0.8f..2f,
                display = "%.2f×".format(settings.lineSpacing),
                onChange = viewModel::setLineSpacing,
            )
        }
        item {
            ChoiceRow(
                label = "Cursor",
                options = listOf("Block" to 0, "Underline" to 1, "Bar" to 2),
                selected = settings.cursorStyle,
                onSelect = viewModel::setCursorStyle,
            )
        }
        item { SwitchRow("Blinking cursor", settings.cursorBlink, viewModel::setCursorBlink) }
        item {
            ChoiceRow(
                label = "Scrollback",
                options = Settings.SCROLLBACK_CHOICES.map { "${it / 1000}k" to it },
                selected = settings.scrollbackLines,
                onSelect = viewModel::setScrollback,
                note = "Applies to sessions started from now on; a running PTY keeps the buffer it was created with.",
            )
        }
        item { SwitchRow("Terminal bell", settings.bellEnabled, viewModel::setBell) }
        item { SwitchRow("Vibrate on bell", settings.bellVibrate, viewModel::setBellVibrate, enabled = settings.bellEnabled) }
        item {
            ActionRow(
                label = "Login shell",
                value = settings.shell.ifEmpty { "Automatic" },
                onClick = { shellDialog = true },
            )
        }

        item { SectionHeader("Appearance") }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    themes.take(8).forEach { theme ->
                        ThemeSwatch(
                            name = theme.name,
                            background = Color(theme.background),
                            foreground = Color(theme.foreground),
                            accent = Color(theme.accent),
                            selected = theme.id == settings.themeId,
                            onClick = { viewModel.setThemeId(theme.id) },
                        )
                    }
                }
            }
        }
        item {
            SliderRow(
                label = "Corner radius",
                value = settings.cornerRadiusDp,
                range = 0f..28f,
                display = "%.0f dp".format(settings.cornerRadiusDp),
                onChange = viewModel::setCornerRadius,
            )
        }
        item { SwitchRow("Reduce motion", settings.reducedMotion, viewModel::setReducedMotion) }

        item { SectionHeader("Tabs") }
        item {
            ChoiceRow(
                label = "Tab bar position",
                options = TabPosition.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) to it },
                selected = settings.tabPosition,
                onSelect = viewModel::setTabPosition,
            )
        }
        item { SwitchRow("Confirm before closing a tab", settings.confirmTabClose, viewModel::setConfirmTabClose) }
        item { SwitchRow("Name tabs from the running command", settings.tabAutoNaming, viewModel::setTabAutoNaming) }

        item { SectionHeader("Input") }
        item { SwitchRow("Extra key row", settings.showKeyboardToolbar, viewModel::setKeyboardToolbar) }
        item { SwitchRow("Gestures", settings.gesturesEnabled, viewModel::setGestures) }
        item { SwitchRow("Pinch to change font size", settings.pinchToZoom, viewModel::setPinchZoom, enabled = settings.gesturesEnabled) }
        item { SwitchRow("Swipe to switch tabs", settings.swipeToSwitchTabs, viewModel::setSwipeTabs, enabled = settings.gesturesEnabled) }
        item {
            ActionRow(
                label = "Reset the extra key row",
                value = "Restores the default keys",
                onClick = viewModel::resetToolbar,
            )
        }

        item { SectionHeader("Sessions") }
        item {
            SwitchRow(
                label = "Restore tabs on launch",
                checked = settings.persistSessions,
                onChange = viewModel::setPersistSessions,
                note = "Tabs and their working directories come back. The shell processes themselves cannot survive the app being killed — Android ends them with the process.",
            )
        }
        item {
            SwitchRow(
                label = "Keep sessions alive in the background",
                checked = settings.foregroundService,
                onChange = viewModel::setForegroundService,
                note = "Shows a notification while shells are running. Without it, Android will freeze and eventually kill them.",
            )
        }
        item {
            ChoiceRow(
                label = "Wake lock",
                options = WakeLockMode.entries.map { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) to it },
                selected = settings.wakeLockMode,
                onSelect = viewModel::setWakeLockMode,
            )
        }

        item { SectionHeader("Security") }
        item {
            SwitchRow(
                label = "Warn before running destructive commands as root",
                checked = settings.warnOnRootCommands,
                onChange = viewModel::setRootWarnings,
                note = "Matches rm -rf, mkfs, dd and similar before they are sent to an elevated shell.",
            )
        }

        item { SectionHeader("File browser") }
        item { SwitchRow("Show hidden files", settings.showHiddenFiles, viewModel::setShowHiddenFiles) }
        item { SwitchRow("Folders first", settings.foldersFirst, viewModel::setFoldersFirst) }
        item {
            ChoiceRow(
                label = "Sort by",
                options = FileSortOrder.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) to it },
                selected = settings.fileSortOrder,
                onSelect = viewModel::setFileSortOrder,
            )
        }
        item {
            SwitchRow(
                label = "Calculate folder sizes",
                checked = settings.calculateFolderSizes,
                onChange = viewModel::setCalculateFolderSizes,
                note = "Walks every child to total a folder. Slow on large trees, so it is off by default.",
            )
        }

        item { SectionHeader("About and licences") }
        item {
            Text(
                text = "NEXTERM runs Linux environments with PRoot, which is bundled " +
                    "in this APK under the GNU GPL v2. The notices below carry the " +
                    "attribution and the offer of source code that licence requires.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(LicenseDocument.entries) { document ->
            ActionRow(
                label = document.title,
                value = "Read",
                onClick = { viewModel.openLicense(document) },
                note = document.subtitle,
            )
        }

        item { Box(Modifier.height(32.dp)) }
    }

    license?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::closeLicense,
            title = { Text(text.document.title, style = MaterialTheme.typography.titleMedium) },
            text = {
                // Licence texts are long and hard-wrapped by their authors, so they
                // scroll and are shown in a monospaced face rather than reflowed.
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = text.body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeLicense) { Text("Close") } },
        )
    }

    if (shellDialog) {
        var value by remember { mutableStateOf(settings.shell) }
        AlertDialog(
            onDismissRequest = { shellDialog = false },
            title = { Text("Login shell") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        placeholder = { Text("/system/bin/sh") },
                    )
                    Text(
                        text = "Leave empty to pick the best shell available on this " +
                            "device automatically. Android only ships /system/bin/sh; " +
                            "bash and zsh come from an installed environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setShell(value.trim())
                    shellDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { shellDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 10.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    note: String? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (text, option) ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    note: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A theme preview that shows the actual colours rather than a name in a list. */
@Composable
private fun ThemeSwatch(
    name: String,
    background: Color,
    foreground: Color,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$", color = foreground, style = MaterialTheme.typography.labelMedium)
                Box(Modifier.size(14.dp, 3.dp).clip(CircleShape).background(accent))
            }
        }
        Text(
            text = name.take(9),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
