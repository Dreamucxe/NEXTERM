package com.nexterm.core.terminal

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of the available terminal engines.
 *
 * [TerminalSessionManager] routes a [SessionRequest] here by its [SessionKind], which
 * is what keeps the UI free of any knowledge of PTYs, proot command lines or SSH.
 */
@Singleton
class TerminalProviders @Inject constructor(
    private val local: LocalPtyProvider,
    private val proot: ProotProvider,
    private val ssh: SshProvider,
) {
    fun forKind(kind: SessionKind): TerminalProvider? = when (kind) {
        SessionKind.LOCAL -> local
        SessionKind.PROOT -> proot
        SessionKind.SSH -> ssh
    }

    /** Every engine with its current availability, for the new-session UI. */
    fun all(): List<Pair<TerminalProvider, ProviderAvailability>> =
        listOf(local, proot, ssh).map { it to it.availability() }
}
