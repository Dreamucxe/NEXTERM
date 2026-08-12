package com.nexterm.feature.distros

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexterm.core.permissions.PrivilegeManager
import com.nexterm.core.permissions.PrivilegeState
import com.nexterm.core.terminal.ProotLocator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the environments screen needs to say about proot itself. */
data class ProotStatus(
    val available: Boolean,
    val path: String?,
    val source: ProotLocator.Source?,
    val checkedPaths: List<String>,
    /**
     * The helper ELF proot runs in place of each guest program, if one was found.
     *
     * Worth surfacing rather than hiding: without it proot has to write that helper
     * out and execute it, which an app sandbox refuses, so a session can fail even
     * though the proot binary itself is present and executable.
     */
    val loaderPath: String? = null,
)

data class EnvironmentsUiState(
    val proot: ProotStatus? = null,
    val architecture: DistroCatalog.Architecture? = null,
    val message: String? = null,
    val messageDetail: String? = null,
    val pendingUninstall: String? = null,
)

/**
 * Drives the Linux-environment screen.
 *
 * Installing is a real download, a real SHA-256 verification and a real tar
 * extraction through [DistroManager]. Running one needs an executable proot, which
 * this app cannot produce on its own — Android blocks executing anything from
 * app-writable storage since API 29 — so [ProotStatus] reports plainly whether one
 * was found and where it looked. A distro can still be installed and inspected
 * without proot; it simply cannot be entered, and the UI says exactly that.
 */
@HiltViewModel
class EnvironmentsViewModel @Inject constructor(
    private val distroManager: DistroManager,
    private val privilegeManager: PrivilegeManager,
    private val prootLocator: ProotLocator,
) : ViewModel() {

    private val _ui = MutableStateFlow(EnvironmentsUiState())
    val ui: StateFlow<EnvironmentsUiState> = _ui

    val distros: StateFlow<List<InstallableDistro>> = distroManager.observeDistros()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val privilege: StateFlow<PrivilegeState> = privilegeManager.state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val located = prootLocator.locate()
            _ui.update {
                it.copy(
                    proot = when (located) {
                        is ProotLocator.Result.Found -> ProotStatus(
                            available = true,
                            path = located.path,
                            source = located.source,
                            checkedPaths = emptyList(),
                            loaderPath = located.loaderPath,
                        )

                        is ProotLocator.Result.NotFound -> ProotStatus(
                            available = false,
                            path = null,
                            source = null,
                            checkedPaths = located.checked,
                        )
                    },
                    architecture = distroManager.architecture,
                )
            }
            privilegeManager.refresh()
        }
    }

    fun install(distroId: String) {
        viewModelScope.launch {
            try {
                distroManager.install(distroId)
            } catch (e: DistroInstallException) {
                post(e.reason, e.detail)
            } catch (e: Exception) {
                post("The installation did not finish.", e.message)
            }
        }
    }

    fun cancel(distroId: String) = distroManager.cancelInstall(distroId)

    fun requestUninstall(distroId: String) = _ui.update { it.copy(pendingUninstall = distroId) }

    fun cancelUninstall() = _ui.update { it.copy(pendingUninstall = null) }

    /** Only ever reached from the confirmation dialog: this deletes a whole rootfs. */
    fun confirmUninstall() {
        val id = _ui.value.pendingUninstall ?: return
        _ui.update { it.copy(pendingUninstall = null) }
        viewModelScope.launch {
            try {
                distroManager.uninstall(id)
            } catch (e: Exception) {
                post("The environment could not be removed.", e.message)
            }
        }
    }

    fun requestShizuku() = privilegeManager.requestShizukuPermission()

    fun dismissMessage() = _ui.update { it.copy(message = null, messageDetail = null) }

    private fun post(message: String, detail: String?) =
        _ui.update { it.copy(message = message, messageDetail = detail) }
}
