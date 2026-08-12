package com.nexterm.feature.terminal

import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexterm.core.terminal.TerminalPalette
import com.nexterm.data.model.TerminalTheme
import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Everything the pane needs in order to draw and to talk back to a session. */
data class TerminalPaneState(
    val emulator: TerminalEmulator?,
    /** Bumped by the session manager on every screen change; drives redraws. */
    val revision: Long,
    val theme: TerminalTheme,
    val fontSizeSp: Float,
    val lineSpacing: Float,
    val cursorStyle: Int,
    val cursorBlink: Boolean,
    val isRunning: Boolean,
)

/** Callbacks a host screen wires to its ViewModel. */
data class TerminalPaneCallbacks(
    val onResize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    val onText: (String) -> Unit = {},
    val onKey: (android.view.KeyEvent) -> Boolean = { false },
    val onFontScale: (Float) -> Unit = {},
    val onSelectionChanged: (String?) -> Unit = {},
    val onContextMenu: () -> Unit = {},
)

/**
 * Hosts [TerminalSurfaceView] inside Compose.
 *
 * The screen buffer is never lifted into Compose state. `revision` changes on every
 * chunk of output, and turning each of those into a recomposition would make `yes`
 * or a kernel build unwatchable. Instead the effect below calls `invalidate()`, so
 * output costs one draw pass and no composition at all.
 */
@Composable
fun TerminalPane(
    state: TerminalPaneState,
    callbacks: TerminalPaneCallbacks,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { state.fontSizeSp.sp.toPx() }
    var view by remember { mutableStateOf<TerminalSurfaceView?>(null) }

    // Held in an updated state so the View's long-lived listeners always call the
    // latest lambdas without being re-registered on every recomposition.
    val currentCallbacks by rememberUpdatedState(callbacks)

    val palette = remember(state.theme) { TerminalPalette.build(state.theme) }
    val renderer = remember(textSizePx, state.lineSpacing) {
        TerminalRenderer(
            textSizePx = textSizePx,
            typeface = Typeface.MONOSPACE,
            lineSpacingMultiplier = state.lineSpacing,
        )
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                TerminalSurfaceView(context).also { created ->
                    created.onGridChanged = { columns, rows ->
                        currentCallbacks.onResize(columns, rows)
                    }
                    created.onText = { text -> currentCallbacks.onText(text) }
                    created.onKey = { event -> currentCallbacks.onKey(event) }
                    created.onFontScale = { factor -> currentCallbacks.onFontScale(factor) }
                    created.onSelectionChanged = { text ->
                        currentCallbacks.onSelectionChanged(text)
                    }
                    created.onContextMenu = { currentCallbacks.onContextMenu() }
                    created.onTapped = { created.showKeyboard() }
                    view = created
                }
            },
            update = { target ->
                target.renderer = renderer
                target.palette = palette
                target.cursorColor = state.theme.cursor
                target.cursorStyle = state.cursorStyle
                if (target.emulator !== state.emulator) {
                    target.emulator = state.emulator
                }
                target.setBackgroundColor(state.theme.background)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Output → redraw. Reading `revision` here is what subscribes this effect; the
    // View pulls the new contents straight out of the emulator when it draws.
    LaunchedEffect(view, state.revision) {
        val target = view ?: return@LaunchedEffect
        // New output always returns the viewport to the live screen, matching every
        // other terminal; scrolling back is only sticky while the user is dragging.
        if (state.revision > 0L) target.scrollToBottom()
        target.invalidate()
    }

    LaunchedEffect(view, requestFocus) {
        if (requestFocus) view?.requestFocus()
    }

    // The cursor blink runs here rather than in the View so a paused screen stops
    // waking the CPU; the effect is cancelled with the composition.
    LaunchedEffect(view, state.cursorBlink, state.isRunning) {
        val target = view ?: return@LaunchedEffect
        if (!state.cursorBlink || !state.isRunning) {
            target.cursorVisible = state.isRunning
            return@LaunchedEffect
        }
        while (coroutineContext.isActive) {
            target.cursorVisible = !target.cursorVisible
            delay(CURSOR_BLINK_MS)
        }
    }

    // The view is captured here, not read inside onDispose. `view` starts null, so the
    // first run of this effect is keyed on null; when the factory assigns the created
    // view the key changes and the null-keyed teardown runs. Reading the state there
    // would see the *live* view and strip its callbacks — leaving a terminal that draws
    // (update() re-attaches the emulator) but can never receive a tap or a keystroke.
    val attached = view
    DisposableEffect(attached) {
        onDispose {
            attached?.let { target ->
                target.onGridChanged = null
                target.onText = null
                target.onKey = null
                target.onFontScale = null
                target.onSelectionChanged = null
                target.onContextMenu = null
                target.onTapped = null
                target.emulator = null
            }
        }
    }
}

private const val CURSOR_BLINK_MS = 530L
