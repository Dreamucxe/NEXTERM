package com.nexterm.data.repository

import com.nexterm.core.security.CredentialStore
import com.nexterm.data.database.SshProfileDao
import com.nexterm.data.model.SshProfile
import com.nexterm.data.model.toEntity
import com.nexterm.data.model.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH hosts and their secrets.
 *
 * Secrets never live in the Room row: passwords and private keys go into
 * [CredentialStore] (EncryptedSharedPreferences under a Keystore-backed key), and
 * the profile only records *whether* one exists. That is what lets the edit screen
 * offer "replace key" without ever reading the key back.
 */
@Singleton
class SshRepository @Inject constructor(
    private val profileDao: SshProfileDao,
    private val credentials: CredentialStore,
) {
    val profiles: Flow<List<SshProfile>> =
        profileDao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** False when the Keystore is unusable; the UI warns instead of storing plaintext. */
    val canStoreSecrets: Boolean get() = credentials.isAvailable

    suspend fun get(id: Long): SshProfile? = profileDao.get(id)?.toModel()

    /**
     * Saves a profile and, when supplied, its secret.
     *
     * The row is written first because Room assigns the id for a new profile, and
     * the secret has to be filed under that same id. Passing null for both secrets
     * leaves whatever was already stored untouched, so editing a hostname does not
     * discard the key.
     *
     * The caller's arrays are wiped by [CredentialStore] once stored.
     */
    suspend fun save(
        profile: SshProfile,
        password: CharArray? = null,
        privateKey: ByteArray? = null,
        passphrase: CharArray? = null,
    ): Long {
        val supplyingSecret = password != null || privateKey != null
        val id = profileDao.upsert(
            profile.copy(
                hasStoredSecret = supplyingSecret || credentials.hasSecret(profile.id),
            ).toEntity(),
        )

        password?.let { credentials.storePassword(id, it) }
        privateKey?.let { credentials.storePrivateKey(id, it, passphrase) }
        return id
    }

    /** Removes the profile and its secret together; neither may outlive the other. */
    suspend fun delete(id: Long) {
        credentials.clear(id)
        profileDao.deleteById(id)
    }

    /** Forgets a stored password/key but keeps the host definition. */
    suspend fun clearSecret(id: Long) {
        credentials.clear(id)
        profileDao.get(id)?.let { profileDao.upsert(it.copy(hasStoredSecret = false)) }
    }

    suspend fun markConnected(id: Long) = profileDao.markConnected(id, System.currentTimeMillis())

    /** Records a host key the user explicitly accepted, so it is trusted next time. */
    fun trustHostKey(entry: String) = credentials.appendKnownHost(entry)
}
