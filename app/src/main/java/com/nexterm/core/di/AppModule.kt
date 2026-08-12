package com.nexterm.core.di

import android.content.Context
import androidx.room.Room
import com.nexterm.core.common.ApplicationScope
import com.nexterm.core.common.IoDispatcher
import com.nexterm.core.security.CredentialStore
import com.nexterm.core.terminal.LocalPtyProvider
import com.nexterm.core.terminal.ProotLocator
import com.nexterm.core.terminal.ProotProvider
import com.nexterm.core.terminal.SshCredentials
import com.nexterm.core.terminal.SshProvider
import com.nexterm.data.database.BookmarkDao
import com.nexterm.data.database.DistroDao
import com.nexterm.data.database.Migrations
import com.nexterm.data.database.NextermDatabase
import com.nexterm.data.database.QuickCommandDao
import com.nexterm.data.database.SnippetDao
import com.nexterm.data.database.SshProfileDao
import com.nexterm.data.database.TabDao
import com.nexterm.data.database.TabGroupDao
import com.nexterm.data.database.ThemeDao
import com.nexterm.data.database.ToolbarKeyDao
import com.nexterm.feature.distros.DistroManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun context(@ApplicationContext context: Context): Context = context

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Outlives any screen, so session bookkeeping and downloads survive rotation and
     * navigation. SupervisorJob keeps one failed child from cancelling the rest.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NextermDatabase =
        Room.databaseBuilder(context, NextermDatabase::class.java, NextermDatabase.NAME)
            // No destructive fallback: the database holds user-authored content, so a
            // schema bump must ship a migration instead of wiping it (spec §44).
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides fun tabDao(db: NextermDatabase): TabDao = db.tabDao()
    @Provides fun tabGroupDao(db: NextermDatabase): TabGroupDao = db.tabGroupDao()
    @Provides fun quickCommandDao(db: NextermDatabase): QuickCommandDao = db.quickCommandDao()
    @Provides fun snippetDao(db: NextermDatabase): SnippetDao = db.snippetDao()
    @Provides fun bookmarkDao(db: NextermDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun themeDao(db: NextermDatabase): ThemeDao = db.themeDao()
    @Provides fun sshProfileDao(db: NextermDatabase): SshProfileDao = db.sshProfileDao()
    @Provides fun distroDao(db: NextermDatabase): DistroDao = db.distroDao()
    @Provides fun toolbarKeyDao(db: NextermDatabase): ToolbarKeyDao = db.toolbarKeyDao()

    @Provides
    @Singleton
    fun credentialStore(@ApplicationContext context: Context): CredentialStore =
        CredentialStore(context)

    @Provides
    @Singleton
    fun localPtyProvider(@ApplicationContext context: Context): LocalPtyProvider =
        LocalPtyProvider(context)

    @Provides
    @Singleton
    fun prootLocator(@ApplicationContext context: Context): ProotLocator =
        ProotLocator(context)

    @Provides
    @Singleton
    fun prootProvider(
        @ApplicationContext context: Context,
        locator: ProotLocator,
        local: LocalPtyProvider,
        distroManager: DistroManager,
    ): ProotProvider = ProotProvider(
        context = context,
        locator = locator,
        local = local,
        rootfsResolver = { distroId -> distroManager.rootfsDirectory(distroId) },
    )

    /**
     * The SSH provider receives a *resolver* rather than the credential store, so the
     * secrets are fetched at connect time and shredded straight afterwards instead of
     * being held live in the provider.
     */
    @Provides
    @Singleton
    fun sshProvider(
        profileDao: SshProfileDao,
        credentialStore: CredentialStore,
    ): SshProvider = SshProvider { profileId ->
        val profile = profileDao.get(profileId)
            ?: throw IllegalStateException("SSH profile $profileId no longer exists")
        SshCredentials(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            privateKey = credentialStore.privateKey(profileId),
            privateKeyPassphrase = credentialStore.passphrase(profileId),
            password = credentialStore.password(profileId),
            knownHosts = credentialStore.knownHosts().ifBlank { null },
            // Accepting a new host key is an explicit user action, recorded by
            // writing the key into known_hosts; it is never assumed here.
            trustUnknownHost = false,
        )
    }
}
