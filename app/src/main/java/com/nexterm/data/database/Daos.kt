package com.nexterm.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position ASC")
    fun observeAll(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs ORDER BY position ASC")
    suspend fun getAll(): List<TabEntity>

    @Upsert
    suspend fun upsert(tab: TabEntity)

    @Upsert
    suspend fun upsertAll(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE tabs SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: String, position: Int)

    @Query("UPDATE tabs SET name = :name, isNameUserSet = 1 WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE tabs SET name = :name WHERE id = :id AND isNameUserSet = 0")
    suspend fun autoRename(id: String, name: String)

    @Query("UPDATE tabs SET groupId = :groupId WHERE id = :id")
    suspend fun setGroup(id: String, groupId: Long?)

    @Query("UPDATE tabs SET lastActiveAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE tabs SET workingDirectory = :cwd WHERE id = :id")
    suspend fun updateWorkingDirectory(id: String, cwd: String)

    @Query("UPDATE tabs SET splitPosition = :pane WHERE id = :id")
    suspend fun setSplitPosition(id: String, pane: Int?)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tabs")
    suspend fun nextPosition(): Int

    /** Rewrites the whole ordering in one transaction after a drag-reorder. */
    @Transaction
    suspend fun reorder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> updatePosition(id, index) }
    }
}

@Dao
interface TabGroupDao {
    @Query("SELECT * FROM tab_groups ORDER BY position ASC")
    fun observeAll(): Flow<List<TabGroupEntity>>

    @Upsert
    suspend fun upsert(group: TabGroupEntity): Long

    @Query("DELETE FROM tab_groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tab_groups SET isCollapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    /**
     * Deleting a group must not delete its tabs. Room cannot express a foreign-key
     * SET NULL here without coupling the tables, so the repository clears the
     * membership first and then calls [delete]; both run in one transaction there.
     */
    @Query("UPDATE tabs SET groupId = NULL WHERE groupId = :id")
    suspend fun ungroupTabs(id: Long)
}

@Dao
interface QuickCommandDao {
    @Query("SELECT * FROM quick_commands ORDER BY position ASC")
    fun observeAll(): Flow<List<QuickCommandEntity>>

    @Upsert
    suspend fun upsert(command: QuickCommandEntity): Long

    @Delete
    suspend fun delete(command: QuickCommandEntity)

    @Query("DELETE FROM quick_commands WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE quick_commands SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("SELECT COUNT(*) FROM quick_commands")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(commands: List<QuickCommandEntity>)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY position ASC")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Upsert
    suspend fun upsert(snippet: SnippetEntity): Long

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM snippets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(snippets: List<SnippetEntity>)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY position ASC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path)")
    suspend fun exists(path: String): Boolean
}

@Dao
interface ThemeDao {
    @Query("SELECT * FROM themes ORDER BY isBuiltIn DESC, name ASC")
    fun observeAll(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun get(id: Long): ThemeEntity?

    @Upsert
    suspend fun upsert(theme: ThemeEntity): Long

    @Query("DELETE FROM themes WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: Long)

    @Query("SELECT COUNT(*) FROM themes")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(themes: List<ThemeEntity>)
}

@Dao
interface SshProfileDao {
    @Query("SELECT * FROM ssh_profiles ORDER BY label ASC")
    fun observeAll(): Flow<List<SshProfileEntity>>

    @Query("SELECT * FROM ssh_profiles WHERE id = :id")
    suspend fun get(id: Long): SshProfileEntity?

    @Upsert
    suspend fun upsert(profile: SshProfileEntity): Long

    @Query("DELETE FROM ssh_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE ssh_profiles SET lastConnectedAt = :at WHERE id = :id")
    suspend fun markConnected(id: Long, at: Long)
}

@Dao
interface DistroDao {
    @Query("SELECT * FROM distros ORDER BY displayName ASC")
    fun observeAll(): Flow<List<DistroEntity>>

    @Query("SELECT * FROM distros WHERE id = :id")
    suspend fun get(id: String): DistroEntity?

    @Upsert
    suspend fun upsert(distro: DistroEntity)

    @Query("UPDATE distros SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(distros: List<DistroEntity>)
}

@Dao
interface ToolbarKeyDao {
    @Query("SELECT * FROM toolbar_keys ORDER BY position ASC")
    fun observeAll(): Flow<List<ToolbarKeyEntity>>

    @Upsert
    suspend fun upsert(key: ToolbarKeyEntity): Long

    @Query("DELETE FROM toolbar_keys WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Only used by "reset toolbar to defaults", which the UI confirms first. */
    @Query("DELETE FROM toolbar_keys")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM toolbar_keys")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(keys: List<ToolbarKeyEntity>)

    @Transaction
    suspend fun reorder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updatePosition(id, index) }
    }

    @Query("UPDATE toolbar_keys SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)
}
