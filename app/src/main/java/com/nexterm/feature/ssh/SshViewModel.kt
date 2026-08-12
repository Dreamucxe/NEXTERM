package com.nexterm.feature.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexterm.data.model.SshAuthMethod
import com.nexterm.data.model.SshProfile
import com.nexterm.data.repository.SshRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A profile the user asked to delete, held until they confirm. */
data class PendingProfileDelete(val profile: SshProfile)

@HiltViewModel
class SshViewModel @Inject constructor(
    private val repository: SshRepository,
) : ViewModel() {

    val profiles: StateFlow<List<SshProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** False when the Android Keystore is unusable on this device. */
    val canStoreSecrets: Boolean get() = repository.canStoreSecrets

    private val _pendingDelete = MutableStateFlow<PendingProfileDelete?>(null)
    val pendingDelete: StateFlow<PendingProfileDelete?> = _pendingDelete

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * Saves a host.
     *
     * The secret arrives as a [CharArray]/[ByteArray] and is handed straight to the
     * repository, which wipes it once the Keystore has it. It is never turned into a
     * String, because a String would sit in the heap until GC decided otherwise.
     */
    fun save(
        profile: SshProfile,
        password: CharArray? = null,
        privateKey: ByteArray? = null,
        passphrase: CharArray? = null,
    ) {
        viewModelScope.launch {
            runCatching { repository.save(profile, password, privateKey, passphrase) }
                .onFailure { _message.value = it.message ?: "The host could not be saved." }
        }
    }

    fun requestDelete(profile: SshProfile) {
        _pendingDelete.value = PendingProfileDelete(profile)
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val target = _pendingDelete.value?.profile ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            repository.delete(target.id)
            _message.value = "${target.label} was removed, along with its stored secret."
        }
    }

    fun forgetSecret(profile: SshProfile) {
        viewModelScope.launch {
            repository.clearSecret(profile.id)
            _message.value = "The stored secret for ${profile.label} was erased."
        }
    }

    fun dismissMessage() {
        _message.update { null }
    }

    fun blank() = SshProfile(
        id = 0L,
        label = "",
        host = "",
        port = 22,
        username = "",
        authMethod = SshAuthMethod.PASSWORD,
        hasStoredSecret = false,
        lastConnectedAt = null,
    )
}
