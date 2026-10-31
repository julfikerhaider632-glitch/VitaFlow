package app.vitaflow.com.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.vitaflow.com.data.SessionStore
import app.vitaflow.com.data.db.VitaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Saves everything (accounts, tasks, workouts, meals, water, chat) into one file on the phone and loads it back.
 * The file can be protected with a passphrase. Nothing is uploaded anywhere.
 */
class BackupManager(private val db: VitaDatabase, private val session: SessionStore) {
    private val tables = listOf(
        "accounts", "tasks", "workout_plans", "workout_exercises",
        "meals", "water_logs", "change_records", "chat_messages",
        "profiles", "weight_logs", "workout_sessions", "set_logs",
        "conversations", "memory_facts"
    )
    private val magic = "VFENC1".toByteArray(Charsets.US_ASCII)

    suspend fun export(passphrase: String?): ByteArray = withContext(Dispatchers.IO) {
        val sdb = db.openHelper.readableDatabase
        val root = JSONObject().put("app", "vitaflow").put("format", 1).put("dbVersion", sdb.version)
        val data = JSONObject()
        for (table in tables) {
            val rows = JSONArray()
            sdb.query("SELECT * FROM $table").use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val col = c.getColumnName(i)
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL)
                            Cursor.FIELD_TYPE_INTEGER -> row.put(col, c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> row.put(col, c.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> row.put(col, c.getString(i))
                            else -> Unit
                        }
                    }
                    rows.put(row)
                }
            }
            data.put(table, rows)
        }
        root.put("tables", data)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (passphrase.isNullOrEmpty()) bytes else encrypt(bytes, passphrase)
    }

    /** Returns null on success, or a readable error message. The old data is replaced in one transaction. */
    suspend fun import(bytes: ByteArray, passphrase: String?): String? = withContext(Dispatchers.IO) {
        try {
            val plain = if (bytes.size > magic.size && bytes.copyOf(magic.size).contentEquals(magic)) {
                if (passphrase.isNullOrEmpty()) return@withContext "ফাইলটা পাসওয়ার্ড দিয়ে সুরক্ষিত। পাসওয়ার্ড লিখুন।"
                decrypt(bytes, passphrase) ?: return@withContext "পাসওয়ার্ড ভুল অথবা ফাইল নষ্ট।"
            } else {
                bytes
            }
            val root = JSONObject(String(plain, Charsets.UTF_8))
            if (root.optString("app") != "vitaflow") return@withContext "এটা Vita Flow-এর backup ফাইল নয়।"
            val sdb = db.openHelper.writableDatabase
            if (root.optInt("dbVersion", 1) > sdb.version) return@withContext "এই backup নতুন সংস্করণের app-এর। app আপডেট করুন।"
            val data = root.getJSONObject("tables")

            sdb.beginTransaction()
            try {
                for (table in tables.reversed()) sdb.execSQL("DELETE FROM $table")
                for (table in tables) {
                    val cols = columnsOf(sdb, table)
                    val rows = data.optJSONArray(table) ?: continue
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val cv = ContentValues()
                        for (key in row.keys()) {
                            if (key !in cols) continue
                            when (val v = row.get(key)) {
                                JSONObject.NULL -> cv.putNull(key)
                                is Int -> cv.put(key, v.toLong())
                                is Long -> cv.put(key, v)
                                is Double -> cv.put(key, v)
                                is Boolean -> cv.put(key, if (v) 1L else 0L)
                                else -> cv.put(key, v.toString())
                            }
                        }
                        sdb.insert(table, SQLiteDatabase.CONFLICT_REPLACE, cv)
                    }
                }
                sdb.setTransactionSuccessful()
            } finally {
                sdb.endTransaction()
            }
            db.invalidationTracker.refreshVersionsAsync()
            session.clear() // the restored accounts have their own ids, so the user logs in again
            null
        } catch (e: org.json.JSONException) {
            "ফাইলটা পড়া যায়নি।"
        } catch (e: Exception) {
            "restore হয়নি: ${e.message ?: "অজানা সমস্যা"}"
        }
    }

    private fun columnsOf(sdb: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> {
        val out = mutableSetOf<String>()
        sdb.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) out += c.getString(1)
        }
        return out
    }

    private fun keyFrom(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 150_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(128, iv))
        return magic + salt + iv + cipher.doFinal(plain)
    }

    private fun decrypt(file: ByteArray, passphrase: String): ByteArray? = runCatching {
        val salt = file.copyOfRange(magic.size, magic.size + 16)
        val iv = file.copyOfRange(magic.size + 16, magic.size + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.doFinal(file, magic.size + 28, file.size - magic.size - 28)
    }.getOrNull()
}
