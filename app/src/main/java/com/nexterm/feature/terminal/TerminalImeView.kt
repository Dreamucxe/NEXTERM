package com.nexterm.feature.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * A zero-size, invisible [View] whose only job is to own the input connection.
 *
 * Android soft keyboards do not deliver key events for ordinary characters — they
 * call `commitText` on an [InputConnection]. Compose exposes no public API for
 * claiming one outside a text field, so a terminal that wants real IME support
 * (including CJK composition and swipe typing) has to bridge through a View. Only
 * input lives here; every pixel is drawn by Compose.
 *
 * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` is deliberate: it is the standard way to
 * ask a keyboard to disable autocorrect, capitalisation and word suggestions, none
 * of which belong between the user and a shell prompt.
 */
open class TerminalImeView(context: Context) : View(context) {

    /** Receives committed text from the keyboard. */
    var onText: ((String) -> Unit)? = null

    /** Receives key events; return true to consume. */
    var onKey: ((KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        return object : BaseInputConnection(this, true) {
            /**
             * The composing region is where an IME assembles a character before it is
             * final (pinyin, kana, gesture typing). A terminal has nowhere to show
             * it, so text is forwarded only once the IME commits or finishes.
             */
            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // A keyboard asking to delete backwards means Backspace was pressed.
                val delete = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                repeat(beforeLength) { onKey?.invoke(delete) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onKey?.invoke(event)
                    return true
                }
                return super.sendKeyEvent(event)
            }

            private fun flush() {
                val content = editable ?: return
                if (content.isEmpty()) return
                onText?.invoke(content.toString())
                content.clear()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        onKey?.invoke(event) ?: super.onKeyDown(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        // Some keyboards batch repeated characters into KEYCODE_UNKNOWN + a string.
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.takeIf { it.isNotEmpty() }?.let {
                onText?.invoke(it)
                return true
            }
        }
        return onKey?.invoke(event) ?: super.onKeyMultiple(keyCode, repeatCount, event)
    }

    /**
     * Pulls up the soft keyboard and takes focus.
     *
     * The flag is 0 rather than [InputMethodManager.SHOW_IMPLICIT]: an implicit request
     * is advisory, and current Android drops it in ordinary cases (split screen, or a
     * device that believes a hardware keyboard is attached). A tap on the terminal is
     * the user explicitly asking, so it is sent as an explicit show.
     */
    fun showKeyboard() {
        // An unfocused view has no input connection for the IME to bind to, so the
        // show would be a no-op. Bail rather than pretending it worked.
        if (!isFocused && !requestFocus()) return

        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return

        if (!manager.showSoftInput(this, 0)) {
            // On the first tap after attach the window may not hold IME focus yet.
            // One retry on the next loop turn covers that without a polling loop.
            post { manager.showSoftInput(this, 0) }
        }
    }

    fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
    }
}
