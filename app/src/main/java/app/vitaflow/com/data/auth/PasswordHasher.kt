package app.vitaflow.com.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 with a random salt. Passwords and security answers are never stored as plain text. */
object PasswordHasher {
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hash(secret: CharArray, saltB64: String): String {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val spec = PBEKeySpec(secret, salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun verify(secret: CharArray, saltB64: String, expectedHashB64: String): Boolean {
        val actual = hash(secret, saltB64)
        return MessageDigest.isEqual(actual.toByteArray(), expectedHashB64.toByteArray())
    }
}
