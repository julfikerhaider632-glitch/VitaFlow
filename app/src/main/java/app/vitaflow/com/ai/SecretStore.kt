package app.vitaflow.com.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.vitaflow.com.data.vitaPrefs
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the Tavily API key encrypted with a key that never leaves the phone's Keystore. */
class SecretStore(private val context: Context) {
    private val alias = "vitaflow_secret_key"
    private val prefKey = stringPreferencesKey("tavily_key_enc")

    val hasTavilyKey: Flow<Boolean> = context.vitaPrefs.data.map { !it[prefKey].isNullOrEmpty() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    suspend fun saveTavilyKey(value: String) {
        val v = value.trim()
        context.vitaPrefs.edit { p ->
            if (v.isEmpty()) {
                p.remove(prefKey)
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val out = cipher.iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))
                p[prefKey] = Base64.encodeToString(out, Base64.NO_WRAP)
            }
        }
    }

    suspend fun tavilyKey(): String? {
        val enc = context.vitaPrefs.data.first()[prefKey] ?: return null
        return runCatching {
            val bytes = Base64.decode(enc, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }
}
