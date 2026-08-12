package com.nexterm.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Stores the 16-entry ANSI palette as a compact comma-separated list. */
class Converters {
    @TypeConverter
    fun longListToString(value: List<Long>?): String = value?.joinToString(",").orEmpty()

    @TypeConverter
    fun stringToLongList(value: String?): List<Long> =
        value?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty()
}

/**
 * NEXTERM's persistent state.
 *
 * Schema changes must ship a migration: the database holds user-authored content
 * (quick commands, snippets, themes, SSH profiles, bookmarks), so destructive
 * migration would silently discard the user's work. See [Migrations].
 */
@Database(
    entities = [
        TabEntity::class,
        TabGroupEntity::class,
        QuickCommandEntity::class,
        SnippetEntity::class,
        BookmarkEntity::class,
        ThemeEntity::class,
        SshProfileEntity::class,
        DistroEntity::class,
        ToolbarKeyEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class NextermDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun tabGroupDao(): TabGroupDao
    abstract fun quickCommandDao(): QuickCommandDao
    abstract fun snippetDao(): SnippetDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun themeDao(): ThemeDao
    abstract fun sshProfileDao(): SshProfileDao
    abstract fun distroDao(): DistroDao
    abstract fun toolbarKeyDao(): ToolbarKeyDao

    companion object {
        const val NAME = "nexterm.db"
    }
}

/**
 * Schema migrations.
 *
 * Empty at version 1. Every future version must append a Migration here rather than
 * enabling fallbackToDestructiveMigration, which would wipe user data.
 */
object Migrations {
    val ALL: Array<androidx.room.migration.Migration> = emptyArray()
}
