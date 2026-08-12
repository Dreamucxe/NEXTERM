package com.nexterm.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted tab. This is the durable half of a terminal session: the live PTY
 * lives in [com.nexterm.core.terminal.TerminalSessionManager] and dies with the
 * process, while this row lets NEXTERM rebuild the same tab afterwards.
 *
 * Covers everything spec §31 requires to be persisted.
 */
@Entity(tableName = "tabs", indices = [Index("groupId"), Index("position")])
data class TabEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Set once the user renames a tab, so auto-naming stops fighting them. */
    val isNameUserSet: Boolean = false,
    /** LOCAL / PROOT / SSH, stored as the enum name. */
    val kind: String,
    val shell: String,
    val workingDirectory: String,
    val distroId: String? = null,
    val sshProfileId: Long? = null,
    val groupId: Long? = null,
    val themeId: Long? = null,
    val position: Int,
    /** Index of the pane this tab occupies in a split, or null when not split. */
    val splitPosition: Int? = null,
    val createdAt: Long,
    val lastActiveAt: Long,
)

/** A Chrome-style tab group: a coloured, collapsible band over a run of tabs. */
@Entity(tableName = "tab_groups")
data class TabGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB. Stored as Long because Room has no unsigned types. */
    val color: Long,
    val isCollapsed: Boolean = false,
    val position: Int = 0,
)

/** A saved command the user can run with one tap. */
@Entity(tableName = "quick_commands")
data class QuickCommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val command: String,
    /** Material icon name, resolved by the UI; null falls back to a default. */
    val icon: String? = null,
    val position: Int = 0,
    /** Run immediately (append newline) or just insert into the prompt. */
    val runImmediately: Boolean = true,
)

/**
 * A reusable command template. Placeholders are written `$NAME` and are filled in
 * by the user before the snippet runs.
 */
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val template: String,
    val description: String? = null,
    val position: Int = 0,
)

/** A bookmarked directory in the file browser. */
@Entity(tableName = "bookmarks", indices = [Index(value = ["path"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val path: String,
    val position: Int = 0,
)

/**
 * A terminal colour scheme. The 16 ANSI colours plus the interface accents; the
 * 240 remaining xterm-256 entries are generated arithmetically at render time.
 */
@Entity(tableName = "themes")
data class ThemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Built-in presets cannot be deleted, only duplicated. */
    val isBuiltIn: Boolean = false,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val selection: Long,
    val accent: Long,
    /** The 16 base ANSI colours, ARGB, in index order 0..15. */
    val ansi: List<Long>,
)

/**
 * A saved SSH host. Secrets are deliberately absent: the private key and password
 * live in [com.nexterm.core.security.CredentialStore], encrypted under a
 * Keystore-backed key, and are referenced by this row's id.
 */
@Entity(tableName = "ssh_profiles")
data class SshProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** PASSWORD or KEY. */
    val authMethod: String,
    /** Whether a key/password has been stored for this profile. */
    val hasStoredSecret: Boolean = false,
    val lastConnectedAt: Long? = null,
)

/** An installed (or installable) Linux environment. */
@Entity(tableName = "distros")
data class DistroEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val version: String,
    /** NOT_INSTALLED / INSTALLING / INSTALLED / BROKEN / UPDATING. */
    val status: String,
    val rootfsPath: String? = null,
    val installedAt: Long? = null,
    val sizeBytes: Long? = null,
)

/** A key on the customisable keyboard toolbar. */
@Entity(tableName = "toolbar_keys")
data class ToolbarKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Semantic action name (ESC, CTRL, ARROW_UP, ...) or LITERAL. */
    val action: String,
    /** Text sent when [action] is LITERAL. */
    val literal: String? = null,
    val position: Int = 0,
)
