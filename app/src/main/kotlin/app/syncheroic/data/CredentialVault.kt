package app.syncheroic.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Credentials(val email: String, val password: String, val sessionToken: String? = null)

class CredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val json = Json

    fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.encodeToString(Credentials.serializer(), credentials).encodeToByteArray())
        preferences.edit {
            putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    fun load(): Credentials? = runCatching {
        val encrypted = preferences.getString(CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        json.decodeFromString(Credentials.serializer(), cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString())
    }.getOrNull()

    fun clear() {
        preferences.edit(commit = true) { clear() }
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "syncheroic.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FILE_NAME = "encrypted_credentials"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
    }
}
