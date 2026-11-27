package enterprises.legitimate.rabbitears

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import enterprises.legitimate.rabbitears.api.models.LibraryKind
import enterprises.legitimate.rabbitears.kit.CredentialStore
import enterprises.legitimate.rabbitears.kit.ProfileMemory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device token sealed with an AES key that never leaves the Android Keystore, the server
 * address in plain preferences. Backup is off; a restored copy could not be opened anyway, since
 * the key stays on this device.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override var server: String?
        get() = prefs.getString("server", null)
        set(value) = prefs.edit { putString("server", value) }

    override var serverName: String?
        get() = prefs.getString("serverName", null)
        set(value) = prefs.edit { putString("serverName", value) }

    override var token: String?
        get() = prefs.getString("token", null)?.let { runCatching { open(it) }.getOrNull() }
        // A Keystore that cannot seal keeps no token, which a sign-in reads back to refuse.
        set(value) = prefs.edit {
            putString("token", value?.let { runCatching { seal(it) }.getOrNull() })
        }

    override fun profileMemory(key: String) =
        ProfileMemory(
            prefs.getBoolean("remember:$key", true),
            prefs.getBoolean("skipPin:$key", true),
        )

    override fun setProfileMemory(key: String, memory: ProfileMemory) = prefs.edit {
        putBoolean("remember:$key", memory.remember)
        putBoolean("skipPin:$key", memory.skipPin)
    }

    override fun libraryKinds(server: String) =
        prefs.getStringSet("kinds:$server", null)?.mapNotNull { LibraryKind.decode(it) }?.toSet()

    override fun setLibraryKinds(server: String, kinds: Set<LibraryKind>) = prefs.edit {
        putStringSet("kinds:$server", kinds.map { it.value }.toSet())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val spec =
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** IV then ciphertext, Base64. */
    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(plain.toByteArray()),
            Base64.NO_WRAP,
        )
    }

    private fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV))
            }
        return String(cipher.doFinal(bytes, IV, bytes.size - IV))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "device-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
