package com.nexterm.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexterm.data.model.TerminalTheme
import com.nexterm.data.preferences.FileSortOrder
import com.nexterm.data.preferences.Settings
import com.nexterm.data.preferences.SettingsRepository
import com.nexterm.data.preferences.TabPosition
import com.nexterm.data.preferences.WakeLockMode
import com.nexterm.data.repository.ContentRepository
import com.nexterm.data.repository.LicenseDocument
import com.nexterm.data.repository.LicenseRepository
import com.nexterm.data.repository.LicenseText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings, written straight through to DataStore.
 *
 * Every control here changes a real preference that some other part of the app
 * already reads; nothing is stored and then ignored.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val contentRepository: ContentRepository,
    private val licenseRepository: LicenseRepository,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val themes: StateFlow<List<TerminalTheme>> = contentRepository.themes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The licence the user is reading, or null. Loaded on demand — these are large. */
    private val _license = MutableStateFlow<LicenseText?>(null)
    val license: StateFlow<LicenseText?> = _license

    fun openLicense(document: LicenseDocument) {
        viewModelScope.launch { _license.value = licenseRepository.read(document) }
    }

    fun closeLicense() {
        _license.value = null
    }

    private fun edit(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settingsRepository.block() }
    }

    fun setFontSize(value: Float) = edit { setFontSize(value) }
    fun setLineSpacing(value: Float) = edit { setLineSpacing(value) }
    fun setCursorStyle(value: Int) = edit { setCursorStyle(value) }
    fun setCursorBlink(value: Boolean) = edit { setCursorBlink(value) }
    fun setScrollback(value: Int) = edit { setScrollback(value) }
    fun setBell(value: Boolean) = edit { setBell(value) }
    fun setBellVibrate(value: Boolean) = edit { setBellVibrate(value) }
    fun setShell(value: String) = edit { setShell(value) }

    fun setTabPosition(value: TabPosition) = edit { setTabPosition(value) }
    fun setConfirmTabClose(value: Boolean) = edit { setConfirmTabClose(value) }
    fun setTabAutoNaming(value: Boolean) = edit { setTabAutoNaming(value) }

    fun setThemeId(value: Long) = edit { setThemeId(value) }
    fun setTransparency(value: Float) = edit { setTransparency(value) }
    fun setCornerRadius(value: Float) = edit { setCornerRadius(value) }
    fun setReducedMotion(value: Boolean) = edit { setReducedMotion(value) }

    fun setKeyboardToolbar(value: Boolean) = edit { setKeyboardToolbar(value) }
    fun setGestures(value: Boolean) = edit { setGestures(value) }
    fun setPinchZoom(value: Boolean) = edit { setPinchZoom(value) }
    fun setSwipeTabs(value: Boolean) = edit { setSwipeTabs(value) }

    fun setPersistSessions(value: Boolean) = edit { setPersistSessions(value) }
    fun setForegroundService(value: Boolean) = edit { setForegroundService(value) }
    fun setWakeLockMode(value: WakeLockMode) = edit { setWakeLockMode(value) }
    fun setRootWarnings(value: Boolean) = edit { setRootWarnings(value) }

    fun setShowHiddenFiles(value: Boolean) = edit { setShowHiddenFiles(value) }
    fun setFoldersFirst(value: Boolean) = edit { setFoldersFirst(value) }
    fun setFileSortOrder(value: FileSortOrder) = edit { setFileSortOrder(value) }
    fun setCalculateFolderSizes(value: Boolean) = edit { setCalculateFolderSizes(value) }

    fun resetToolbar() {
        viewModelScope.launch { contentRepository.resetToolbar() }
    }
}
