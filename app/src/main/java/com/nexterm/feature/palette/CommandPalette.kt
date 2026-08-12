package com.nexterm.feature.palette

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** One thing the palette can do. [run] performs the real action; nothing is a stub. */
data class PaletteCommand(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val group: String,
    /** Extra words that should match this entry without being displayed. */
    val keywords: String = "",
    val run: () -> Unit,
)

/**
 * The command palette.
 *
 * A phone has no menu bar and no keyboard shortcuts, so a searchable list of every
 * action is the only way a power feature stays reachable without burying it three
 * menus deep. Matching is subsequence-based, the way editors do it: "nsh" finds
 * "New SSH session".
 */
@Composable
fun CommandPalette(
    commands: List<PaletteCommand>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val matches = remember(commands, query) { rank(commands, query) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type a command") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )

                if (matches.isEmpty()) {
                    Text(
                        text = "Nothing matches \"$query\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                        items(matches, key = { it.id }) { command ->
                            CommandRow(command) {
                                onDismiss()
                                command.run()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: PaletteCommand, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            command.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = command.group,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

/**
 * Subsequence match with a score, so exact prefixes beat scattered letters.
 *
 * Deliberately not a fuzzy library: this runs on every keystroke over a list that
 * grows with the user's saved commands, and a linear scan with an early exit is
 * both faster and easier to reason about than an edit-distance matrix.
 */
internal fun rank(commands: List<PaletteCommand>, query: String): List<PaletteCommand> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return commands

    return commands
        .mapNotNull { command ->
            val haystack = "${command.title} ${command.subtitle.orEmpty()} ${command.keywords}".lowercase()
            score(haystack, needle)?.let { command to it }
        }
        .sortedByDescending { it.second }
        .map { it.first }
}

private fun score(haystack: String, needle: String): Int? {
    if (haystack.startsWith(needle)) return 1_000
    val direct = haystack.indexOf(needle)
    if (direct >= 0) return 500 - direct.coerceAtMost(400)

    var index = 0
    var score = 0
    var lastMatch = -1
    for (char in needle) {
        val found = haystack.indexOf(char, index)
        if (found < 0) return null
        // Adjacent letters are worth more than letters strewn across the string.
        score += if (found == lastMatch + 1) 8 else 2
        // A letter starting a word is a strong signal ("nt" → "New Tab").
        if (found == 0 || haystack[found - 1] == ' ') score += 6
        lastMatch = found
        index = found + 1
    }
    return score
}
