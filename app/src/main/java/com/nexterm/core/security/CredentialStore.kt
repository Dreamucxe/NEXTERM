package com.nexterm.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Stores SSH secrets encrypted at rest.
 *
 * The encryption key lives in the Android Keystore (hardware-backed where the device
 * supports it) and never leaves it, so the ciphertext here is useless without the
 * device. Per spec §34 no secret is ever written to a log, included in an error
 * message, or persisted in the Room database alongside its profile.
 *
 * If the encrypted store cannot be opened — which happens if the Keystore entry is
 * invalidated, e.g. after the user removes their lock screen — the store degrades to
 * "no secrets available" rather than crashing, and the user is asked to re-enter the
 * credential. It never silently falls back to plaintext.
 */
class CredentialStore(context: Context) {

    private val preferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // A corrupt or invalidated keystore entry: drop the file so the next launch
        // can start clean, and continue without stored secrets.
        runCatching {
            File(context.filesDir.parentFile, "shared_prefs/$FILE_NAME.xml").delete()
        }
        null
    }

    val isAvailable: Boolean get() = preferences != null

    fun storePassword(profileId: Long, password: CharArray) {
        preferences?.edit()?.putString(passwordKey(profileId), String(password))?.apply()
        password.fill(' ')
    }

    fun storePrivateKey(profileId: Long, key: ByteArray, passphrase: CharArray?) {
        preferences?.edit()?.apply {
            putString(keyKey(profileId), android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
            passphrase?.let { putString(passphraseKey(profileId), String(it)) }
        }?.apply()
        key.fill(0)
        passphrase?.fill(' ')
    }

    fun password(profileId: Long): CharArray? =
        preferences?.getString(passwordKey(profileId), null)?.toCharArray()

    fun privateKey(profileId: Long): ByteArray? =
        preferences?.getString(keyKey(profileId), null)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    fun passphrase(profileId: Long): CharArray? =
        preferences?.getString(passphraseKey(profileId), null)?.toCharArray()

    fun hasSecret(profileId: Long): Boolean =
        preferences?.let {
            it.contains(passwordKey(profileId)) || it.contains(keyKey(profileId))
        } ?: false

    fun clear(profileId: Long) {
        preferences?.edit()
            ?.remove(passwordKey(profileId))
            ?.remove(keyKey(profileId))
            ?.remove(passphraseKey(profileId))
            ?.apply()
    }

    /** known_hosts is not secret, but it belongs with the SSH state. */
    fun knownHosts(): String = preferences?.getString(KNOWN_HOSTS, null).orEmpty()

    fun appendKnownHost(entry: String) {
        val current = knownHosts()
        if (current.contains(entry)) return
        preferences?.edit()
            ?.putString(KNOWN_HOSTS, if (current.isBlank()) entry else "$current\n$entry")
            ?.apply()
    }

    private fun passwordKey(id: Long) = "ssh_password_$id"
    private fun keyKey(id: Long) = "ssh_key_$id"
    private fun passphraseKey(id: Long) = "ssh_passphrase_$id"

    private companion object {
        const val FILE_NAME = "nexterm_credentials"
        const val KNOWN_HOSTS = "known_hosts"
    }
}
