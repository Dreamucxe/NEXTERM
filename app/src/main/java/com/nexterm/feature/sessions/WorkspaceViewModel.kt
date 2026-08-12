package com.nexterm.feature.sessions

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexterm.core.permissions.PrivilegeManager
import com.nexterm.core.terminal.PrivilegeLevel
import com.nexterm.core.terminal.SessionKind
import com.nexterm.core.terminal.SessionRequest
import com.nexterm.core.terminal.SessionState
import com.nexterm.core.terminal.TerminalSessionManager
import com.nexterm.core.terminal.TerminalStartException
import com.nexterm.data.model.QuickCommand
import com.nexterm.data.model.Snippet
import com.nexterm.data.model.TabGroup
import com.nexterm.data.model.TabModel
import com.nexterm.data.model.TerminalTheme
import com.nexterm.data.model.ToolbarAction
import com.nexterm.data.model.ToolbarKey
import com.nexterm.data.preferences.Settings
import com.nexterm.data.preferences.SettingsRepository
import com.nexterm.data.repository.ContentRepository
import com.nexterm.data.repository.TabRepository
import com.nexterm.feature.terminal.TerminalInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A tab plus the live session bound to it, which may not exist yet. */
data class TabWithSession(
    val tab: TabModel,
    val session: SessionState?,
) {
    val isRunning: Boolean get() = session?.isRunning == true
    val hasActivity: Boolean get() = session?.hasUnseenActivity == true
    val hasBell: Boolean get() = session?.hasUnseenBell == true
    val exitStatus: Int? get() = session?.exitStatus
}

/** A message the UI must show; never swallowed, per the spec's error-handling rule. */
data class UserMessage(
    val text: String,
    val detail: String? = null,
    val id: Long = nextId++,
) {
    private companion object {
        var nextId = 0L
    }
}

/** Which pane a split is showing, and whether a split is active at all. */
data class SplitState(
    val enabled: Boolean = false,
    val secondaryTabId: String? = null,
    val vertical: Boolean = true,
    /** Fraction of the axis given to the first pane. */
    val ratio: Float = 0.5f,
)

/**
 * The single source of truth for the terminal workspace.
 *
 * Everything the terminal screen does passes through here: tabs come from Room,
 * sessions come from the session manager, and the two are joined by tab id. No
 * composable owns any of it, which is what keeps business logic out of the UI layer.
 */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    application: Application,
    private val tabRepository: TabRepository,
    private val contentRepository: ContentRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: TerminalSessionManager,
    private val privilegeManager: PrivilegeManager,
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    private val _split = MutableStateFlow(SplitState())
    val split: StateFlow<SplitState> = _split

    private val _messages = MutableStateFlow<List<UserMessage>>(emptyList())
    val messages: StateFlow<List<UserMessage>> = _messages

    private val _starting = MutableStateFlow<Set<String>>(emptySet())

    /** Latched Ctrl/Alt/Shift from the keyboard toolbar; cleared after the next key. */
    private val _modifiers = MutableStateFlow(Modifiers())
    val modifiers: StateFlow<Modifiers> = _modifiers

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val toolbarKeys: StateFlow<List<ToolbarKey>> = contentRepository.toolbarKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quickCommands: StateFlow<List<QuickCommand>> = contentRepository.quickCommands
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val snippets: StateFlow<List<Snippet>> = contentRepository.snippets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<TabGroup>> = tabRepository.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The active colour scheme, falling back to a built-in until Room answers. */
    val theme: StateFlow<TerminalTheme> =
        combine(contentRepository.themes, settingsRepository.settings) { themes, prefs ->
            themes.firstOrNull { it.id == prefs.themeId }
                ?: themes.firstOrNull()
                ?: TerminalTheme.FALLBACK
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalTheme.FALLBACK)

    val tabs: StateFlow<List<TabWithSession>> =
        combine(tabRepository.tabs, sessionManager.states) { tabs, sessions ->
            tabs.map { TabWithSession(it, sessions[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Clipboard bridges the emulator's OSC 52 to the system clipboard both ways.
        sessionManager.clipboardSink = { text -> copyToClipboard(text) }
        sessionManager.pasteSource = { clipboardText() }

        viewModelScope.launch {
            contentRepository.seedDefaults()
            privilegeManager.refresh()

            val existing = tabRepository.all()
            if (existing.isEmpty()) {
                newTab()
            } else {
                // Sessions do not survive process death, so a restored tab starts
                // detached and reattaches lazily when the user selects it.
                _activeTabId.value = existing.maxByOrNull { it.lastActiveAt }?.id
                _activeTabId.value?.let { ensureSession(it) }
            }
        }
    }

    // ---- Tabs ----

    fun selectTab(id: String) {
        _activeTabId.value = id
        sessionManager.setForeground(id)
        viewModelScope.launch {
            tabRepository.touch(id)
            ensureSession(id)
        }
    }

    /**
     * Creates a tab and starts its session. The row is written first so the tab
     * appears immediately; a session that fails to start leaves a visible tab
     * carrying the error, rather than a tab that silently never existed.
     */
    fun newTab(
        kind: SessionKind = SessionKind.LOCAL,
        distroId: String? = null,
        sshProfileId: Long? = null,
        workingDirectory: String? = null,
        groupId: Long? = null,
    ) {
        viewModelScope.launch {
            val prefs = settingsRepository.settings.first()
            val tab = tabRepository.create(
                name = defaultName(kind, distroId),
                kind = kind,
                shell = prefs.shell,
                workingDirectory = workingDirectory ?: defaultWorkingDirectory(kind),
                distroId = distroId,
                sshProfileId = sshProfileId,
                groupId = groupId,
            )
            _activeTabId.value = tab.id
            sessionManager.setForeground(tab.id)
            startSession(tab)
        }
    }

    fun closeTab(id: String) {
        viewModelScope.launch {
            sessionManager.closeSession(id)
            tabRepository.delete(id)

            val remaining = tabRepository.all()
            if (_split.value.secondaryTabId == id) {
                _split.update { it.copy(secondaryTabId = null, enabled = false) }
            }
            if (_activeTabId.value == id) {
                val next = remaining.maxByOrNull { it.lastActiveAt }
                _activeTabId.value = next?.id
                if (next == null) newTab() else ensureSession(next.id)
            }
        }
    }

    fun renameTab(id: String, name: String) {
        viewModelScope.launch { tabRepository.rename(id, name.trim().ifEmpty { "Terminal" }) }
    }

    fun reorderTabs(orderedIds: List<String>) {
        viewModelScope.launch { tabRepository.reorder(orderedIds) }
    }

    fun duplicateTab(id: String) {
        viewModelScope.launch {
            val source = tabRepository.all().firstOrNull { it.id == id } ?: return@launch
            newTab(
                kind = source.kind,
                distroId = source.distroId,
                sshProfileId = source.sshProfileId,
                workingDirectory = source.workingDirectory,
                groupId = source.groupId,
            )
        }
    }

    /** Restarts a finished session in place, keeping the tab and its name. */
    fun restartTab(id: String) {
        viewModelScope.launch {
            sessionManager.closeSession(id)
            tabRepository.all().firstOrNull { it.id == id }?.let { startSession(it) }
        }
    }

    // ---- Groups ----

    fun createGroup(name: String, color: Int, withTabId: String?) {
        viewModelScope.launch {
            val groupId = tabRepository.createGroup(name, color)
            withTabId?.let { tabRepository.addToGroup(it, groupId) }
        }
    }

    fun setTabGroup(tabId: String, groupId: Long?) {
        viewModelScope.launch { tabRepository.addToGroup(tabId, groupId) }
    }

    fun toggleGroupCollapsed(groupId: Long, collapsed: Boolean) {
        viewModelScope.launch { tabRepository.setGroupCollapsed(groupId, collapsed) }
    }

    /** Removes the group only. Its tabs stay, which is the non-destructive choice. */
    fun deleteGroup(groupId: Long) {
        viewModelScope.launch { tabRepository.deleteGroup(groupId) }
    }

    // ---- Split view ----

    fun toggleSplit() {
        val current = _split.value
        if (current.enabled) {
            _split.value = current.copy(enabled = false)
            return
        }
        val others = tabs.value.map { it.tab.id }.filter { it != _activeTabId.value }
        val secondary = current.secondaryTabId?.takeIf { it in others } ?: others.firstOrNull()
        if (secondary == null) {
            postMessage("Open a second tab to use split view.")
            return
        }
        _split.value = current.copy(enabled = true, secondaryTabId = secondary)
        viewModelScope.launch { ensureSession(secondary) }
    }

    fun setSplitSecondary(id: String) {
        _split.update { it.copy(secondaryTabId = id, enabled = true) }
        viewModelScope.launch { ensureSession(id) }
    }

    fun setSplitOrientation(vertical: Boolean) {
        _split.update { it.copy(vertical = vertical) }
    }

    fun setSplitRatio(ratio: Float) {
        _split.update { it.copy(ratio = ratio.coerceIn(0.15f, 0.85f)) }
    }

    // ---- Session lifecycle ----

    private suspend fun ensureSession(tabId: String) {
        if (sessionManager.handle(tabId) != null) return
        if (tabId in _starting.value) return
        tabRepository.all().firstOrNull { it.id == tabId }?.let { startSession(it) }
    }

    /**
     * Starts the real session for [tab].
     *
     * The session id is forced to equal the tab id so the two never drift apart, and
     * a failure surfaces as a message rather than a silent empty pane.
     */
    private suspend fun startSession(tab: TabModel) {
        if (tab.id in _starting.value) return
        _starting.update { it + tab.id }
        try {
            val prefs = settingsRepository.settings.first()
            sessionManager.createSession(
                request = SessionRequest(
                    kind = tab.kind,
                    id = tab.id,
                    executable = tab.shell,
                    workingDirectory = tab.workingDirectory,
                    transcriptRows = prefs.scrollbackLines,
                    sshProfileId = tab.sshProfileId,
                    distroId = tab.distroId,
                ),
                // A PTY started by this process runs as this app's UID, whatever
                // privilege the device could otherwise offer. Reporting anything
                // else here would put a root badge on an unprivileged shell.
                privilege = PrivilegeLevel.UNPRIVILEGED,
            )
        } catch (e: TerminalStartException) {
            postMessage(e.message ?: "The session could not be started.", e.detail)
        } catch (e: Exception) {
            postMessage("The session could not be started.", e.message)
        } finally {
            _starting.update { it - tab.id }
        }
    }

    // ---- Input ----

    private fun inputFor(tabId: String): TerminalInput =
        TerminalInput(sessionManager.handle(tabId), sessionManager.emulator(tabId))

    fun sendText(tabId: String, text: String) {
        val mods = _modifiers.value
        if (mods.ctrl || mods.alt) {
            val input = inputFor(tabId)
            text.codePoints().toArray().forEach { input.sendCodePoint(it, mods.ctrl, mods.alt) }
            clearModifiers()
        } else {
            sessionManager.write(tabId, text)
        }
    }

    fun sendKeyEvent(tabId: String, event: android.view.KeyEvent): Boolean {
        val mods = _modifiers.value
        val consumed = inputFor(tabId).onKeyDown(event, mods.ctrl, mods.alt)
        if (consumed && (mods.ctrl || mods.alt || mods.shift)) clearModifiers()
        return consumed
    }

    /** Handles a toolbar key: modifiers latch, everything else sends bytes. */
    fun sendToolbarKey(tabId: String, key: ToolbarKey) {
        if (key.action.isModifier) {
            _modifiers.update { current ->
                when (key.action) {
                    ToolbarAction.CTRL -> current.copy(ctrl = !current.ctrl)
                    ToolbarAction.ALT -> current.copy(alt = !current.alt)
                    ToolbarAction.SHIFT -> current.copy(shift = !current.shift)
                    else -> current
                }
            }
            return
        }
        val mods = _modifiers.value
        inputFor(tabId).sendToolbarAction(key.action, key.literal, mods.ctrl, mods.alt, mods.shift)
        clearModifiers()
    }

    fun clearModifiers() {
        if (_modifiers.value != Modifiers()) _modifiers.value = Modifiers()
    }

    fun resize(tabId: String, columns: Int, rows: Int) = sessionManager.resize(tabId, columns, rows)

    fun paste(tabId: String) {
        clipboardText()?.let { inputFor(tabId).paste(it) }
    }

    fun copy(text: String) = copyToClipboard(text)

    /** Runs a quick command or snippet, optionally pressing Enter for the user. */
    fun runCommand(tabId: String, command: String, execute: Boolean) {
        sessionManager.write(tabId, if (execute) command + "\r" else command)
    }

    fun changeFontSize(delta: Float) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().fontSizeSp
            settingsRepository.setFontSize(current + delta)
        }
    }

    fun scaleFontSize(factor: Float) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().fontSizeSp
            settingsRepository.setFontSize(current * factor)
        }
    }

    // ---- Feedback ----

    fun emulator(tabId: String) = sessionManager.emulator(tabId)

    /** Rings the bell the way the user asked for it, and never more than that. */
    fun onBell() {
        viewModelScope.launch {
            val prefs = settingsRepository.settings.first()
            if (!prefs.bellEnabled || !prefs.bellVibrate) return@launch
            vibrator()?.let { vibrator ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(40)
                }
            }
        }
    }

    fun postMessage(text: String, detail: String? = null) {
        _messages.update { it + UserMessage(text, detail) }
    }

    fun dismissMessage(id: Long) {
        _messages.update { list -> list.filterNot { it.id == id } }
    }

    // ---- Helpers ----

    private fun defaultName(kind: SessionKind, distroId: String?): String = when (kind) {
        SessionKind.LOCAL -> "Terminal"
        SessionKind.PROOT -> distroId?.replaceFirstChar { it.uppercase() } ?: "Linux"
        SessionKind.SSH -> "SSH"
    }

    private fun defaultWorkingDirectory(kind: SessionKind): String = when (kind) {
        SessionKind.PROOT -> "/root"
        else -> context.filesDir.absolutePath
    }

    private fun copyToClipboard(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("NEXTERM", text))
    }

    private fun clipboardText(): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return manager?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun onCleared() {
        super.onCleared()
        sessionManager.clipboardSink = null
        sessionManager.pasteSource = null
    }
}

/** Latched modifier state from the keyboard toolbar. */
data class Modifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)
