package com.nexterm.data.model

import com.nexterm.data.database.BookmarkEntity
import com.nexterm.data.database.DistroEntity
import com.nexterm.data.database.QuickCommandEntity
import com.nexterm.data.database.SnippetEntity
import com.nexterm.data.database.SshProfileEntity
import com.nexterm.data.database.TabEntity
import com.nexterm.data.database.TabGroupEntity
import com.nexterm.data.database.ThemeEntity
import com.nexterm.data.database.ToolbarKeyEntity
import com.nexterm.core.terminal.SessionKind

/**
 * Domain models.
 *
 * The database entities are storage shapes (nullable columns, enums as strings);
 * these are what the rest of the app reasons about. Keeping them apart means a
 * column rename never ripples into the UI, and the UI never sees a string where an
 * enum belongs.
 */

/** A terminal colour scheme with colours already decoded to ARGB ints. */
data class TerminalTheme(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val accent: Int,
    /** The 16 base ANSI colours in index order. */
    val ansi: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TerminalTheme && other.id == id && other.name == name &&
            other.background == background && other.foreground == foreground &&
            other.cursor == cursor && other.selection == selection &&
            other.accent == accent && other.ansi.contentEquals(ansi)

    override fun hashCode(): Int =
        (((id.hashCode() * 31 + name.hashCode()) * 31 + background) * 31 + foreground) * 31 +
            ansi.contentHashCode()

    companion object {
        /** Used before the database has loaded, so the first frame is never white. */
        val FALLBACK = TerminalTheme(
            id = 0,
            name = "NEXTERM Neon",
            isBuiltIn = true,
            background = 0xFF07090F.toInt(),
            foreground = 0xFFD8E0F0.toInt(),
            cursor = 0xFF32E6C8.toInt(),
            selection = 0x5532E6C8,
            accent = 0xFF32E6C8.toInt(),
            ansi = intArrayOf(
                0xFF12161F.toInt(), 0xFFFF5C7A.toInt(), 0xFF3DDC97.toInt(), 0xFFFFC857.toInt(),
                0xFF4D9DE0.toInt(), 0xFF9D7CFF.toInt(), 0xFF32E6C8.toInt(), 0xFFB6C2D9.toInt(),
                0xFF3A4256.toInt(), 0xFFFF8FA3.toInt(), 0xFF7BE8B8.toInt(), 0xFFFFD98A.toInt(),
                0xFF7FBEEA.toInt(), 0xFFBFA5FF.toInt(), 0xFF7FF0DC.toInt(), 0xFFEAF0FA.toInt(),
            ),
        )
    }
}

fun ThemeEntity.toModel(): TerminalTheme = TerminalTheme(
    id = id,
    name = name,
    isBuiltIn = isBuiltIn,
    background = background.toInt(),
    foreground = foreground.toInt(),
    cursor = cursor.toInt(),
    selection = selection.toInt(),
    accent = accent.toInt(),
    // A malformed palette must not crash rendering; pad from the fallback instead.
    ansi = IntArray(16) { index ->
        ansi.getOrNull(index)?.toInt() ?: TerminalTheme.FALLBACK.ansi[index]
    },
)

fun TerminalTheme.toEntity(): ThemeEntity = ThemeEntity(
    id = id,
    name = name,
    isBuiltIn = isBuiltIn,
    background = background.toUInt().toLong(),
    foreground = foreground.toUInt().toLong(),
    cursor = cursor.toUInt().toLong(),
    selection = selection.toUInt().toLong(),
    accent = accent.toUInt().toLong(),
    ansi = ansi.map { it.toUInt().toLong() },
)

/** A tab as the UI knows it. [sessionId] is null until its session is started. */
data class TabModel(
    val id: String,
    val name: String,
    val isNameUserSet: Boolean,
    val kind: SessionKind,
    val shell: String,
    val workingDirectory: String,
    val distroId: String? = null,
    val sshProfileId: Long? = null,
    val groupId: Long? = null,
    val themeId: Long? = null,
    val position: Int,
    val splitPosition: Int? = null,
    val createdAt: Long,
    val lastActiveAt: Long,
)

fun TabEntity.toModel(): TabModel = TabModel(
    id = id,
    name = name,
    isNameUserSet = isNameUserSet,
    kind = runCatching { SessionKind.valueOf(kind) }.getOrDefault(SessionKind.LOCAL),
    shell = shell,
    workingDirectory = workingDirectory,
    distroId = distroId,
    sshProfileId = sshProfileId,
    groupId = groupId,
    themeId = themeId,
    position = position,
    splitPosition = splitPosition,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
)

fun TabModel.toEntity(): TabEntity = TabEntity(
    id = id,
    name = name,
    isNameUserSet = isNameUserSet,
    kind = kind.name,
    shell = shell,
    workingDirectory = workingDirectory,
    distroId = distroId,
    sshProfileId = sshProfileId,
    groupId = groupId,
    themeId = themeId,
    position = position,
    splitPosition = splitPosition,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
)

data class TabGroup(
    val id: Long,
    val name: String,
    val color: Int,
    val isCollapsed: Boolean,
    val position: Int,
)

fun TabGroupEntity.toModel() = TabGroup(id, name, color.toInt(), isCollapsed, position)

data class QuickCommand(
    val id: Long,
    val label: String,
    val command: String,
    val icon: String?,
    val position: Int,
    val runImmediately: Boolean,
)

fun QuickCommandEntity.toModel() = QuickCommand(id, label, command, icon, position, runImmediately)

data class Snippet(
    val id: Long,
    val name: String,
    val template: String,
    val description: String?,
    val position: Int,
) {
    /** `$NAME` placeholders, in first-appearance order and without duplicates. */
    val placeholders: List<String> get() = PLACEHOLDER.findAll(template)
        .map { it.groupValues[1] }
        .distinct()
        .toList()

    fun render(values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1]] ?: match.value
        }

    private companion object {
        val PLACEHOLDER = Regex("""\$([A-Z][A-Z0-9_]*)""")
    }
}

fun SnippetEntity.toModel() = Snippet(id, name, template, description, position)

data class Bookmark(val id: Long, val label: String, val path: String, val position: Int)

fun BookmarkEntity.toModel() = Bookmark(id, label, path, position)

enum class SshAuthMethod { PASSWORD, KEY }

data class SshProfile(
    val id: Long,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: SshAuthMethod,
    val hasStoredSecret: Boolean,
    val lastConnectedAt: Long?,
)

fun SshProfileEntity.toModel() = SshProfile(
    id = id,
    label = label,
    host = host,
    port = port,
    username = username,
    authMethod = runCatching { SshAuthMethod.valueOf(authMethod) }
        .getOrDefault(SshAuthMethod.PASSWORD),
    hasStoredSecret = hasStoredSecret,
    lastConnectedAt = lastConnectedAt,
)

fun SshProfile.toEntity() = SshProfileEntity(
    id = id,
    label = label,
    host = host,
    port = port,
    username = username,
    authMethod = authMethod.name,
    hasStoredSecret = hasStoredSecret,
    lastConnectedAt = lastConnectedAt,
)

enum class DistroStatus { NOT_INSTALLED, DOWNLOADING, EXTRACTING, INSTALLED, BROKEN }

data class Distro(
    val id: String,
    val displayName: String,
    val version: String,
    val status: DistroStatus,
    val rootfsPath: String?,
    val installedAt: Long?,
    val sizeBytes: Long?,
)

fun DistroEntity.toModel() = Distro(
    id = id,
    displayName = displayName,
    version = version,
    status = runCatching { DistroStatus.valueOf(status) }.getOrDefault(DistroStatus.NOT_INSTALLED),
    rootfsPath = rootfsPath,
    installedAt = installedAt,
    sizeBytes = sizeBytes,
)

/** What a toolbar key does when tapped. */
enum class ToolbarAction {
    ESC, TAB, CTRL, ALT, SHIFT,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE, BACKSPACE, ENTER,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    LITERAL,
    ;

    /** True for keys that latch until the next keypress rather than sending bytes. */
    val isModifier: Boolean get() = this == CTRL || this == ALT || this == SHIFT
}

data class ToolbarKey(
    val id: Long,
    val label: String,
    val action: ToolbarAction,
    val literal: String?,
    val position: Int,
)

fun ToolbarKeyEntity.toModel() = ToolbarKey(
    id = id,
    label = label,
    action = runCatching { ToolbarAction.valueOf(action) }.getOrDefault(ToolbarAction.LITERAL),
    literal = literal,
    position = position,
)
