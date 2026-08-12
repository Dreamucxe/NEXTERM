package com.nexterm.data.repository

import com.nexterm.data.database.BookmarkDao
import com.nexterm.data.database.BookmarkEntity
import com.nexterm.data.database.DefaultContent
import com.nexterm.data.database.QuickCommandDao
import com.nexterm.data.database.QuickCommandEntity
import com.nexterm.data.database.SnippetDao
import com.nexterm.data.database.SnippetEntity
import com.nexterm.data.database.ThemeDao
import com.nexterm.data.database.ToolbarKeyDao
import com.nexterm.data.database.ToolbarKeyEntity
import com.nexterm.data.model.Bookmark
import com.nexterm.data.model.QuickCommand
import com.nexterm.data.model.Snippet
import com.nexterm.data.model.TerminalTheme
import com.nexterm.data.model.ToolbarKey
import com.nexterm.data.model.toEntity
import com.nexterm.data.model.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-authored content: quick commands, snippets, bookmarks, themes and the
 * keyboard toolbar layout.
 *
 * [seedDefaults] runs once on first launch and uses INSERT OR IGNORE, so a user who
 * deletes a shipped snippet does not find it back the next time the app starts.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val quickCommandDao: QuickCommandDao,
    private val snippetDao: SnippetDao,
    private val bookmarkDao: BookmarkDao,
    private val themeDao: ThemeDao,
    private val toolbarKeyDao: ToolbarKeyDao,
) {
    val quickCommands: Flow<List<QuickCommand>> =
        quickCommandDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val snippets: Flow<List<Snippet>> =
        snippetDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val bookmarks: Flow<List<Bookmark>> =
        bookmarkDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val themes: Flow<List<TerminalTheme>> =
        themeDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val toolbarKeys: Flow<List<ToolbarKey>> =
        toolbarKeyDao.observeAll().map { rows -> rows.map { it.toModel() } }

    /**
     * Inserts the shipped defaults, but only into tables that are still empty.
     * Checking emptiness rather than relying on IGNORE alone means a user who
     * cleared a whole table keeps it cleared.
     */
    suspend fun seedDefaults() {
        if (quickCommandDao.count() == 0) quickCommandDao.insertAll(DefaultContent.quickCommands())
        if (snippetDao.count() == 0) snippetDao.insertAll(DefaultContent.snippets())
        if (themeDao.count() == 0) themeDao.insertAll(DefaultContent.themes())
        if (toolbarKeyDao.count() == 0) toolbarKeyDao.insertAll(DefaultContent.toolbarKeys())
    }

    suspend fun theme(id: Long): TerminalTheme? = themeDao.get(id)?.toModel()

    // ---- Quick commands ----

    suspend fun saveQuickCommand(command: QuickCommand): Long = quickCommandDao.upsert(
        QuickCommandEntity(
            id = command.id,
            label = command.label,
            command = command.command,
            icon = command.icon,
            position = command.position,
            runImmediately = command.runImmediately,
        ),
    )

    suspend fun deleteQuickCommand(id: Long) = quickCommandDao.deleteById(id)

    suspend fun reorderQuickCommands(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> quickCommandDao.updatePosition(id, index) }
    }

    // ---- Snippets ----

    suspend fun saveSnippet(snippet: Snippet): Long = snippetDao.upsert(
        SnippetEntity(
            id = snippet.id,
            name = snippet.name,
            template = snippet.template,
            description = snippet.description,
            position = snippet.position,
        ),
    )

    suspend fun deleteSnippet(id: Long) = snippetDao.deleteById(id)

    // ---- Bookmarks ----

    suspend fun addBookmark(label: String, path: String): Long =
        bookmarkDao.insert(BookmarkEntity(label = label, path = path))

    suspend fun deleteBookmark(id: Long) = bookmarkDao.deleteById(id)

    suspend fun isBookmarked(path: String): Boolean = bookmarkDao.exists(path)

    // ---- Themes ----

    suspend fun saveTheme(theme: TerminalTheme): Long = themeDao.upsert(theme.toEntity())

    /**
     * Built-in themes are protected by the DAO's `isBuiltIn = 0` clause, so this
     * cannot remove one even if the UI asked it to.
     */
    suspend fun deleteTheme(id: Long) = themeDao.deleteCustom(id)

    /** Editing a built-in copies it first; the original stays intact. */
    suspend fun duplicateTheme(theme: TerminalTheme, name: String): Long =
        themeDao.upsert(theme.copy(id = 0, name = name, isBuiltIn = false).toEntity())

    // ---- Toolbar ----

    suspend fun saveToolbarKey(key: ToolbarKey): Long = toolbarKeyDao.upsert(
        ToolbarKeyEntity(
            id = key.id,
            label = key.label,
            action = key.action.name,
            literal = key.literal,
            position = key.position,
        ),
    )

    suspend fun deleteToolbarKey(id: Long) = toolbarKeyDao.deleteById(id)

    suspend fun reorderToolbarKeys(orderedIds: List<Long>) = toolbarKeyDao.reorder(orderedIds)

    /** Restores the shipped toolbar after the user has customised it into a corner. */
    suspend fun resetToolbar() {
        toolbarKeyDao.deleteAll()
        toolbarKeyDao.insertAll(DefaultContent.toolbarKeys())
    }
}
