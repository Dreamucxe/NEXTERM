package com.nexterm.feature.tabs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexterm.core.terminal.SessionKind
import com.nexterm.feature.sessions.TabWithSession

/**
 * The Chrome-style tab strip.
 *
 * Tabs scroll horizontally and the strip follows the selection, which is the only
 * behaviour that stays usable once a user has fifteen sessions open on a phone.
 * State lives entirely in the caller — this composable decides nothing.
 */
@Composable
fun TabStrip(
    tabs: List<TabWithSession>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    secondaryTabId: String? = null,
) {
    val listState = rememberLazyListState()

    // Selecting a tab that is off screen — from the palette, or by closing its
    // neighbour — must bring it into view, or the strip appears to do nothing.
    LaunchedEffect(activeTabId, tabs.size) {
        val index = tabs.indexOfFirst { it.tab.id == activeTabId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(tabs, key = { _, item -> item.tab.id }) { _, item ->
                TabChip(
                    item = item,
                    isActive = item.tab.id == activeTabId,
                    isSecondary = item.tab.id == secondaryTabId,
                    onSelect = { onSelect(item.tab.id) },
                    onClose = { onClose(item.tab.id) },
                    onLongPress = { onLongPress(item.tab.id) },
                )
            }
        }

        IconButton(onClick = onNewTab, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "New tab", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TabChip(
    item: TabWithSession,
    isActive: Boolean,
    isSecondary: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = when {
            isActive -> scheme.surfaceContainerHighest
            isSecondary -> scheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "tabBackground",
    )

    Row(
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = 108.dp, max = 190.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .then(
                if (isActive) {
                    Modifier.border(1.dp, scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                } else {
                    Modifier
                },
            )
            .combinedClick(onClick = onSelect, onLongClick = onLongPress)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(item)
        Spacer(Modifier.size(7.dp))
        Text(
            text = item.session?.title?.takeIf { it.isNotBlank() } ?: item.tab.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close ${item.tab.name}",
                modifier = Modifier.size(14.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one-glance status marker: kind on the left, and a colour that says whether the
 * session is alive, has unread output, rang the bell, or has exited.
 */
@Composable
private fun StatusDot(item: TabWithSession) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        item.session == null -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
        !item.isRunning -> scheme.error
        item.hasBell -> scheme.tertiary
        item.hasActivity -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    val icon = when (item.tab.kind) {
        SessionKind.LOCAL -> Icons.Default.Terminal
        SessionKind.PROOT -> Icons.Default.Folder
        SessionKind.SSH -> Icons.Default.Terminal
    }
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

/** Small wrapper so the experimental combined-click API is confined to one place. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClick(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
