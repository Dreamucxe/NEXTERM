package com.nexterm.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexterm_settings")

/** How long a session should keep the CPU awake (spec §32). */
enum class WakeLockMode { NEVER, WHILE_ACTIVE, ALWAYS }

/** Where the tab strip sits. */
enum class TabPosition { TOP, BOTTOM }

/** File browser layout. */
enum class FileViewMode { LIST, GRID, DETAILS }

enum class FileSortOrder { NAME, SIZE, MODIFIED, TYPE }

/**
 * Every user-facing setting, as one immutable snapshot.
 *
 * Held in DataStore rather than a Room table: these are scalar preferences read on
 * nearly every frame (font size, theme id, blur), and DataStore gives a typed Flow
 * with atomic writes and no query overhead. User-authored *content* — themes, quick
 * commands, snippets — lives in Room, where it can be listed and related.
 */
data class Settings(
    // Terminal
    val shell: String = "",
    val fontSizeSp: Float = 13f,
    val fontFamily: String = "MONOSPACE",
    val lineSpacing: Float = 1.0f,
    val cursorStyle: Int = 0,
    val cursorBlink: Boolean = true,
    val scrollbackLines: Int = 10_000,
    val bellEnabled: Boolean = true,
    val bellVibrate: Boolean = true,
    // Tabs
    val tabPosition: TabPosition = TabPosition.TOP,
    val tabPreviews: Boolean = true,
    val tabAnimations: Boolean = true,
    val tabAutoNaming: Boolean = true,
    val confirmTabClose: Boolean = true,
    // Appearance
    val themeId: Long = 1L,
    val accentOverride: Long? = null,
    val transparency: Float = 0f,
    val blurIntensity: Float = 0.6f,
    val cornerRadiusDp: Float = 14f,
    val useNavigationRail: Boolean = true,
    val reducedMotion: Boolean = false,
    // Keyboard
    val showKeyboardToolbar: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val swipeToSwitchTabs: Boolean = true,
    val pinchToZoom: Boolean = true,
    val edgeSwipeSidebar: Boolean = true,
    // Sessions
    val persistSessions: Boolean = true,
    val foregroundService: Boolean = true,
    val wakeLockMode: WakeLockMode = WakeLockMode.WHILE_ACTIVE,
    // Security
    val warnOnRootCommands: Boolean = true,
    // File browser
    val showHiddenFiles: Boolean = false,
    val fileViewMode: FileViewMode = FileViewMode.LIST,
    val fileSortOrder: FileSortOrder = FileSortOrder.NAME,
    val foldersFirst: Boolean = true,
    val calculateFolderSizes: Boolean = false,
    // Onboarding
    val onboardingShown: Boolean = false,
) {
    /** Scrollback choices offered in settings (spec §4). */
    companion object {
        val SCROLLBACK_CHOICES = listOf(1_000, 5_000, 10_000, 50_000, 100_000)
    }
}

@Singleton
class SettingsRepository @Inject constructor(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        shell = this[Keys.SHELL] ?: "",
        fontSizeSp = this[Keys.FONT_SIZE] ?: 13f,
        fontFamily = this[Keys.FONT_FAMILY] ?: "MONOSPACE",
        lineSpacing = this[Keys.LINE_SPACING] ?: 1.0f,
        cursorStyle = this[Keys.CURSOR_STYLE] ?: 0,
        cursorBlink = this[Keys.CURSOR_BLINK] ?: true,
        scrollbackLines = this[Keys.SCROLLBACK] ?: 10_000,
        bellEnabled = this[Keys.BELL] ?: true,
        bellVibrate = this[Keys.BELL_VIBRATE] ?: true,
        tabPosition = this[Keys.TAB_POSITION]?.let { runCatching { TabPosition.valueOf(it) }.getOrNull() } ?: TabPosition.TOP,
        tabPreviews = this[Keys.TAB_PREVIEWS] ?: true,
        tabAnimations = this[Keys.TAB_ANIMATIONS] ?: true,
        tabAutoNaming = this[Keys.TAB_AUTO_NAMING] ?: true,
        confirmTabClose = this[Keys.CONFIRM_TAB_CLOSE] ?: true,
        themeId = this[Keys.THEME_ID] ?: 1L,
        accentOverride = this[Keys.ACCENT_OVERRIDE],
        transparency = this[Keys.TRANSPARENCY] ?: 0f,
        blurIntensity = this[Keys.BLUR] ?: 0.6f,
        cornerRadiusDp = this[Keys.CORNER_RADIUS] ?: 14f,
        useNavigationRail = this[Keys.NAV_RAIL] ?: true,
        reducedMotion = this[Keys.REDUCED_MOTION] ?: false,
        showKeyboardToolbar = this[Keys.KEYBOARD_TOOLBAR] ?: true,
        gesturesEnabled = this[Keys.GESTURES] ?: true,
        swipeToSwitchTabs = this[Keys.SWIPE_TABS] ?: true,
        pinchToZoom = this[Keys.PINCH_ZOOM] ?: true,
        edgeSwipeSidebar = this[Keys.EDGE_SWIPE] ?: true,
        persistSessions = this[Keys.PERSIST_SESSIONS] ?: true,
        foregroundService = this[Keys.FOREGROUND_SERVICE] ?: true,
        wakeLockMode = this[Keys.WAKE_LOCK]?.let { runCatching { WakeLockMode.valueOf(it) }.getOrNull() } ?: WakeLockMode.WHILE_ACTIVE,
        warnOnRootCommands = this[Keys.ROOT_WARNINGS] ?: true,
        showHiddenFiles = this[Keys.HIDDEN_FILES] ?: false,
        fileViewMode = this[Keys.FILE_VIEW_MODE]?.let { runCatching { FileViewMode.valueOf(it) }.getOrNull() } ?: FileViewMode.LIST,
        fileSortOrder = this[Keys.FILE_SORT]?.let { runCatching { FileSortOrder.valueOf(it) }.getOrNull() } ?: FileSortOrder.NAME,
        foldersFirst = this[Keys.FOLDERS_FIRST] ?: true,
        calculateFolderSizes = this[Keys.FOLDER_SIZES] ?: false,
        onboardingShown = this[Keys.ONBOARDING_SHOWN] ?: false,
    )

    suspend fun setShell(value: String) = put(Keys.SHELL, value)
    suspend fun setFontSize(value: Float) = put(Keys.FONT_SIZE, value.coerceIn(6f, 32f))
    suspend fun setFontFamily(value: String) = put(Keys.FONT_FAMILY, value)
    suspend fun setLineSpacing(value: Float) = put(Keys.LINE_SPACING, value.coerceIn(0.8f, 2f))
    suspend fun setCursorStyle(value: Int) = put(Keys.CURSOR_STYLE, value)
    suspend fun setCursorBlink(value: Boolean) = put(Keys.CURSOR_BLINK, value)
    suspend fun setScrollback(value: Int) = put(Keys.SCROLLBACK, value)
    suspend fun setBell(value: Boolean) = put(Keys.BELL, value)
    suspend fun setBellVibrate(value: Boolean) = put(Keys.BELL_VIBRATE, value)
    suspend fun setTabPosition(value: TabPosition) = put(Keys.TAB_POSITION, value.name)
    suspend fun setTabPreviews(value: Boolean) = put(Keys.TAB_PREVIEWS, value)
    suspend fun setTabAnimations(value: Boolean) = put(Keys.TAB_ANIMATIONS, value)
    suspend fun setTabAutoNaming(value: Boolean) = put(Keys.TAB_AUTO_NAMING, value)
    suspend fun setConfirmTabClose(value: Boolean) = put(Keys.CONFIRM_TAB_CLOSE, value)
    suspend fun setThemeId(value: Long) = put(Keys.THEME_ID, value)
    suspend fun setAccentOverride(value: Long?) =
        if (value == null) remove(Keys.ACCENT_OVERRIDE) else put(Keys.ACCENT_OVERRIDE, value)
    suspend fun setTransparency(value: Float) = put(Keys.TRANSPARENCY, value.coerceIn(0f, 0.8f))
    suspend fun setBlur(value: Float) = put(Keys.BLUR, value.coerceIn(0f, 1f))
    suspend fun setCornerRadius(value: Float) = put(Keys.CORNER_RADIUS, value.coerceIn(0f, 28f))
    suspend fun setNavigationRail(value: Boolean) = put(Keys.NAV_RAIL, value)
    suspend fun setReducedMotion(value: Boolean) = put(Keys.REDUCED_MOTION, value)
    suspend fun setKeyboardToolbar(value: Boolean) = put(Keys.KEYBOARD_TOOLBAR, value)
    suspend fun setGestures(value: Boolean) = put(Keys.GESTURES, value)
    suspend fun setSwipeTabs(value: Boolean) = put(Keys.SWIPE_TABS, value)
    suspend fun setPinchZoom(value: Boolean) = put(Keys.PINCH_ZOOM, value)
    suspend fun setEdgeSwipe(value: Boolean) = put(Keys.EDGE_SWIPE, value)
    suspend fun setPersistSessions(value: Boolean) = put(Keys.PERSIST_SESSIONS, value)
    suspend fun setForegroundService(value: Boolean) = put(Keys.FOREGROUND_SERVICE, value)
    suspend fun setWakeLockMode(value: WakeLockMode) = put(Keys.WAKE_LOCK, value.name)
    suspend fun setRootWarnings(value: Boolean) = put(Keys.ROOT_WARNINGS, value)
    suspend fun setShowHiddenFiles(value: Boolean) = put(Keys.HIDDEN_FILES, value)
    suspend fun setFileViewMode(value: FileViewMode) = put(Keys.FILE_VIEW_MODE, value.name)
    suspend fun setFileSortOrder(value: FileSortOrder) = put(Keys.FILE_SORT, value.name)
    suspend fun setFoldersFirst(value: Boolean) = put(Keys.FOLDERS_FIRST, value)
    suspend fun setCalculateFolderSizes(value: Boolean) = put(Keys.FOLDER_SIZES, value)
    suspend fun setOnboardingShown(value: Boolean) = put(Keys.ONBOARDING_SHOWN, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun <T> remove(key: Preferences.Key<T>) {
        context.dataStore.edit { it.remove(key) }
    }

    private object Keys {
        val SHELL = stringPreferencesKey("shell")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val CURSOR_STYLE = intPreferencesKey("cursor_style")
        val CURSOR_BLINK = booleanPreferencesKey("cursor_blink")
        val SCROLLBACK = intPreferencesKey("scrollback")
        val BELL = booleanPreferencesKey("bell")
        val BELL_VIBRATE = booleanPreferencesKey("bell_vibrate")
        val TAB_POSITION = stringPreferencesKey("tab_position")
        val TAB_PREVIEWS = booleanPreferencesKey("tab_previews")
        val TAB_ANIMATIONS = booleanPreferencesKey("tab_animations")
        val TAB_AUTO_NAMING = booleanPreferencesKey("tab_auto_naming")
        val CONFIRM_TAB_CLOSE = booleanPreferencesKey("confirm_tab_close")
        val THEME_ID = longPreferencesKey("theme_id")
        val ACCENT_OVERRIDE = longPreferencesKey("accent_override")
        val TRANSPARENCY = floatPreferencesKey("transparency")
        val BLUR = floatPreferencesKey("blur")
        val CORNER_RADIUS = floatPreferencesKey("corner_radius")
        val NAV_RAIL = booleanPreferencesKey("nav_rail")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val KEYBOARD_TOOLBAR = booleanPreferencesKey("keyboard_toolbar")
        val GESTURES = booleanPreferencesKey("gestures")
        val SWIPE_TABS = booleanPreferencesKey("swipe_tabs")
        val PINCH_ZOOM = booleanPreferencesKey("pinch_zoom")
        val EDGE_SWIPE = booleanPreferencesKey("edge_swipe")
        val PERSIST_SESSIONS = booleanPreferencesKey("persist_sessions")
        val FOREGROUND_SERVICE = booleanPreferencesKey("foreground_service")
        val WAKE_LOCK = stringPreferencesKey("wake_lock")
        val ROOT_WARNINGS = booleanPreferencesKey("root_warnings")
        val HIDDEN_FILES = booleanPreferencesKey("hidden_files")
        val FILE_VIEW_MODE = stringPreferencesKey("file_view_mode")
        val FILE_SORT = stringPreferencesKey("file_sort")
        val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val FOLDER_SIZES = booleanPreferencesKey("folder_sizes")
        val ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
    }
}
