package com.nexterm.data.repository

import com.nexterm.core.terminal.SessionKind
import com.nexterm.data.database.TabDao
import com.nexterm.data.database.TabGroupDao
import com.nexterm.data.database.TabGroupEntity
import com.nexterm.data.model.TabGroup
import com.nexterm.data.model.TabModel
import com.nexterm.data.model.toEntity
import com.nexterm.data.model.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable half of the tab system.
 *
 * A tab and its session are deliberately separate: this repository owns the row that
 * survives process death, while [com.nexterm.core.terminal.TerminalSessionManager]
 * owns the live PTY that does not. Reattaching the two after a restart is the job of
 * the ViewModel, which is why nothing here ever touches a session.
 */
@Singleton
class TabRepository @Inject constructor(
    private val tabDao: TabDao,
    private val groupDao: TabGroupDao,
) {
    val tabs: Flow<List<TabModel>> = tabDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val groups: Flow<List<TabGroup>> = groupDao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<TabModel> = tabDao.getAll().map { it.toModel() }

    /**
     * Creates a tab row. The id is generated here rather than by the database so the
     * caller can start a session against it immediately, without a round trip.
     */
    suspend fun create(
        name: String,
        kind: SessionKind,
        shell: String,
        workingDirectory: String,
        distroId: String? = null,
        sshProfileId: Long? = null,
        groupId: Long? = null,
    ): TabModel {
        val now = System.currentTimeMillis()
        val tab = TabModel(
            id = UUID.randomUUID().toString(),
            name = name,
            isNameUserSet = false,
            kind = kind,
            shell = shell,
            workingDirectory = workingDirectory,
            distroId = distroId,
            sshProfileId = sshProfileId,
            groupId = groupId,
            themeId = null,
            position = tabDao.nextPosition(),
            splitPosition = null,
            createdAt = now,
            lastActiveAt = now,
        )
        tabDao.upsert(tab.toEntity())
        return tab
    }

    suspend fun update(tab: TabModel) = tabDao.upsert(tab.toEntity())

    suspend fun delete(id: String) = tabDao.delete(id)

    /** A user rename sticks: auto-naming will not overwrite it afterwards. */
    suspend fun rename(id: String, name: String) = tabDao.rename(id, name)

    /**
     * Renames from the shell's reported title or working directory. Skipped for a
     * tab the user has named, which is what `isNameUserSet` in the query enforces.
     */
    suspend fun autoRename(id: String, name: String) = tabDao.autoRename(id, name)

    suspend fun touch(id: String) = tabDao.touch(id, System.currentTimeMillis())

    suspend fun updateWorkingDirectory(id: String, cwd: String) =
        tabDao.updateWorkingDirectory(id, cwd)

    suspend fun reorder(orderedIds: List<String>) = tabDao.reorder(orderedIds)

    suspend fun setSplitPosition(id: String, pane: Int?) = tabDao.setSplitPosition(id, pane)

    // ---- Groups ----

    suspend fun createGroup(name: String, color: Int): Long =
        groupDao.upsert(TabGroupEntity(name = name, color = color.toLong() and 0xFFFFFFFFL))

    suspend fun updateGroup(group: TabGroup) = groupDao.upsert(
        TabGroupEntity(
            id = group.id,
            name = group.name,
            color = group.color.toLong() and 0xFFFFFFFFL,
            isCollapsed = group.isCollapsed,
            position = group.position,
        ),
    )

    suspend fun addToGroup(tabId: String, groupId: Long?) = tabDao.setGroup(tabId, groupId)

    suspend fun setGroupCollapsed(groupId: Long, collapsed: Boolean) =
        groupDao.setCollapsed(groupId, collapsed)

    /**
     * Deleting a group must never delete the tabs inside it — that would be silent
     * data loss. Membership is cleared first, then the group row goes.
     */
    suspend fun deleteGroup(groupId: Long) {
        groupDao.ungroupTabs(groupId)
        groupDao.delete(groupId)
    }
}
