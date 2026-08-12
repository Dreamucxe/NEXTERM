package com.nexterm.feature.terminal

import android.view.KeyEvent
import com.nexterm.core.terminal.TerminalHandle
import com.nexterm.data.model.ToolbarAction
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator

/**
 * Turns key presses into the byte sequences a terminal expects.
 *
 * Getting this right is what separates a terminal from a text box. The same physical
 * key produces different bytes depending on emulator state — an up-arrow is `ESC [ A`
 * normally but `ESC O A` once an application (vim, less) enables cursor-key
 * application mode — so every translation is resolved against the live emulator
 * rather than a fixed table.
 *
 * Termux's [KeyHandler] owns that state-dependent table; this class supplies the
 * modifier bookkeeping, the control-character arithmetic, and the mapping from
 * NEXTERM's toolbar actions onto keycodes.
 */
class TerminalInput(private val handle: TerminalHandle?, private val emulator: TerminalEmulator?) {

    /**
     * Sends a printable character, applying latched Ctrl/Alt.
     *
     * Ctrl is not a lookup table: control codes are the letter's low five bits, which
     * is why Ctrl+C is 0x03 and Ctrl+[ is ESC. Alt is sent as an ESC prefix, the
     * convention every shell and readline build expects.
     */
    fun sendCodePoint(codePoint: Int, ctrl: Boolean = false, alt: Boolean = false) {
        val handle = handle ?: return
        var value = codePoint

        if (ctrl) {
            value = when (codePoint) {
                in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                ' '.code, '@'.code -> 0
                '['.code -> 27
                '\\'.code -> 28
                ']'.code -> 29
                '^'.code -> 30
                '_'.code, '?'.code -> 31
                else -> codePoint
            }
        }

        val bytes = if (value < 0x80) {
            byteArrayOf(value.toByte())
        } else {
            String(Character.toChars(value)).toByteArray(Charsets.UTF_8)
        }

        if (alt) {
            handle.write(byteArrayOf(0x1B) + bytes)
        } else {
            handle.write(bytes)
        }
    }

    /**
     * Sends a non-printable key (arrows, function keys, Home/End...).
     *
     * @return true if the key produced output, so the caller can consume the event.
     */
    fun sendKeyCode(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): Boolean {
        val handle = handle ?: return false
        val emulator = emulator ?: return false

        var modifiers = 0
        if (ctrl) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (alt) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (shift) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT

        val code = KeyHandler.getCode(
            keyCode,
            modifiers,
            emulator.isCursorKeysApplicationMode,
            emulator.isKeypadApplicationMode,
        ) ?: return false

        handle.write(code)
        return true
    }

    /** Sends the bytes for a toolbar key. Modifier keys latch instead and send nothing. */
    fun sendToolbarAction(
        action: ToolbarAction,
        literal: String?,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): Boolean {
        if (action.isModifier) return false
        val handle = handle ?: return false

        return when (action) {
            ToolbarAction.LITERAL -> {
                val text = literal.orEmpty()
                if (text.isEmpty()) return false
                if (ctrl || alt) {
                    text.codePoints().toArray().forEach { sendCodePoint(it, ctrl, alt) }
                } else {
                    handle.write(text)
                }
                true
            }

            ToolbarAction.ESC -> { handle.write(byteArrayOf(0x1B)); true }
            ToolbarAction.TAB -> sendKeyCode(KeyEvent.KEYCODE_TAB, ctrl, alt, shift)
            ToolbarAction.ENTER -> { handle.write(byteArrayOf(0x0D)); true }
            ToolbarAction.BACKSPACE -> { handle.write(byteArrayOf(0x7F)); true }
            else -> keyCodeFor(action)?.let { sendKeyCode(it, ctrl, alt, shift) } ?: false
        }
    }

    /** Pastes text, using bracketed paste when the application asked for it. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val emulator = emulator
        if (emulator != null) {
            // Routing through the emulator honours bracketed-paste mode, which stops
            // editors from auto-indenting pasted code into a staircase.
            emulator.paste(text)
        } else {
            handle?.write(text)
        }
    }

    private fun keyCodeFor(action: ToolbarAction): Int? = when (action) {
        ToolbarAction.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
        ToolbarAction.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        ToolbarAction.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        ToolbarAction.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        ToolbarAction.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        ToolbarAction.END -> KeyEvent.KEYCODE_MOVE_END
        ToolbarAction.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        ToolbarAction.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        ToolbarAction.INSERT -> KeyEvent.KEYCODE_INSERT
        ToolbarAction.DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
        ToolbarAction.F1 -> KeyEvent.KEYCODE_F1
        ToolbarAction.F2 -> KeyEvent.KEYCODE_F2
        ToolbarAction.F3 -> KeyEvent.KEYCODE_F3
        ToolbarAction.F4 -> KeyEvent.KEYCODE_F4
        ToolbarAction.F5 -> KeyEvent.KEYCODE_F5
        ToolbarAction.F6 -> KeyEvent.KEYCODE_F6
        ToolbarAction.F7 -> KeyEvent.KEYCODE_F7
        ToolbarAction.F8 -> KeyEvent.KEYCODE_F8
        ToolbarAction.F9 -> KeyEvent.KEYCODE_F9
        ToolbarAction.F10 -> KeyEvent.KEYCODE_F10
        ToolbarAction.F11 -> KeyEvent.KEYCODE_F11
        ToolbarAction.F12 -> KeyEvent.KEYCODE_F12
        else -> null
    }

    /**
     * Handles a hardware/soft-keyboard key event.
     *
     * @return true when the event was consumed.
     */
    fun onKeyDown(event: KeyEvent, latchedCtrl: Boolean, latchedAlt: Boolean): Boolean {
        val handle = handle ?: return false
        val ctrl = latchedCtrl || event.isCtrlPressed
        val alt = latchedAlt || event.isAltPressed
        val shift = event.isShiftPressed

        // Enter and Backspace have fixed encodings that KeyHandler leaves alone.
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                handle.write(byteArrayOf(0x0D))
                return true
            }

            KeyEvent.KEYCODE_DEL -> {
                handle.write(if (alt) byteArrayOf(0x1B, 0x7F) else byteArrayOf(0x7F))
                return true
            }
        }

        if (sendKeyCode(event.keyCode, ctrl, alt, shift)) return true

        // Fall back to the printable character the keyboard layout produces.
        val metaState = (if (shift) KeyEvent.META_SHIFT_ON else 0)
        val unicode = event.getUnicodeChar(metaState)
        if (unicode != 0) {
            sendCodePoint(unicode, ctrl, alt)
            return true
        }
        return false
    }
}
