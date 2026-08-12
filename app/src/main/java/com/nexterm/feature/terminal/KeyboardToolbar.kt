package com.nexterm.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexterm.data.model.ToolbarKey
import com.nexterm.feature.sessions.Modifiers

/**
 * The extra key row a soft keyboard does not give you.
 *
 * Esc, Tab, Ctrl and the arrows are not optional on a terminal, and no Android
 * keyboard offers them. Ctrl/Alt/Shift latch — they stay lit until the next key —
 * because a touchscreen cannot hold two keys at once.
 */
@Composable
fun KeyboardToolbar(
    keys: List<ToolbarKey>,
    modifiers: Modifiers,
    onKey: (ToolbarKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (keys.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(keys, key = { it.id }) { key ->
                ToolbarKeyButton(
                    key = key,
                    latched = key.isLatched(modifiers),
                    onClick = { onKey(key) },
                )
            }
        }
    }
}

private fun ToolbarKey.isLatched(modifiers: Modifiers): Boolean = when (action) {
    com.nexterm.data.model.ToolbarAction.CTRL -> modifiers.ctrl
    com.nexterm.data.model.ToolbarAction.ALT -> modifiers.alt
    com.nexterm.data.model.ToolbarAction.SHIFT -> modifiers.shift
    else -> false
}

@Composable
private fun ToolbarKeyButton(key: ToolbarKey, latched: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val background = if (latched) scheme.primary else scheme.surfaceContainerHighest
    val foreground = if (latched) scheme.onPrimary else scheme.onSurface

    Box(
        modifier = Modifier
            .height(36.dp)
            .widthForLabel(key.label)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = key.label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (latched) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Keys stay finger-sized: short labels get a square, longer ones grow with the text. */
private fun Modifier.widthForLabel(label: String): Modifier =
    if (label.length <= 2) this.width(40.dp) else this.widthIn(min = 48.dp).padding(horizontal = 12.dp)
