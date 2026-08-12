package com.nexterm.feature.files

import com.nexterm.core.terminal.PrivilegeLevel
import com.nexterm.feature.distros.DistroCatalog
import com.nexterm.feature.distros.DistroManager
import javax.inject.Inject
import javax.inject.Singleton

/** A provider paired with what it can do right now, ready for the location chooser. */
data class ProviderOption(
    val provider: FileSystemProvider,
    val capabilities: ProviderCapabilities,
) {
    val isUsable: Boolean get() = capabilities.canList
}

/**
 * Every filesystem NEXTERM can show, and which of them are usable at this moment.
 *
 * The list is rebuilt on request rather than cached, because all four of the
 * conditions that decide it can change while the app is running: root can be granted,
 * Shizuku can be started or revoked, the user can grant or drop a SAF folder, and a
 * distro can finish installing. Anything cached here would eventually be a lie.
 */
@Singleton
class FileSystemRegistry @Inject constructor(
    private val local: LocalFileSystemProvider,
    private val saf: SafFileSystemProvider,
    private val elevatedProviders: ElevatedProviderFactory,
    private val distroManager: DistroManager,
) {
    /**
     * Providers in the order the chooser should show them: the one that always works
     * first, then the ones that depend on a grant, then elevated access, then distros.
     */
    suspend fun options(): List<ProviderOption> = buildList {
        add(ProviderOption(local, local.capabilities()))
        add(ProviderOption(saf, saf.capabilities()))

        for (level in listOf(PrivilegeLevel.ROOT, PrivilegeLevel.SHIZUKU)) {
            val provider = elevatedProviders.create(level)
            // Unavailable levels are still listed, carrying the reason they are not
            // usable; hiding them would leave the user guessing why root is missing.
            add(ProviderOption(provider, provider.capabilities()))
        }

        for (entry in DistroCatalog.entries) {
            if (distroManager.rootfsDirectory(entry.id) == null) continue
            val provider = ProotFileSystemProvider(entry.id, entry.displayName, distroManager, local)
            add(ProviderOption(provider, provider.capabilities()))
        }
    }

    /** Resolves a provider by the id stored with a tab or a bookmark. */
    suspend fun byId(id: String): FileSystemProvider? = when {
        id == local.id -> local
        id == saf.id -> saf
        id == "root" -> elevatedProviders.create(PrivilegeLevel.ROOT)
        id == "shizuku" -> elevatedProviders.create(PrivilegeLevel.SHIZUKU)
        id.startsWith("proot:") -> id.removePrefix("proot:").let { distroId ->
            DistroCatalog.byId(distroId)?.let { entry ->
                ProotFileSystemProvider(entry.id, entry.displayName, distroManager, local)
            }
        }

        else -> null
    }

    /** The provider a fresh browser tab should open with. */
    fun default(): FileSystemProvider = local
}
