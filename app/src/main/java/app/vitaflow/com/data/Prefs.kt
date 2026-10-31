package app.vitaflow.com.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

val Context.vitaPrefs: DataStore<Preferences> by preferencesDataStore(name = "vitaflow_prefs")

/** Remembers which account is logged in. */
class SessionStore(private val context: Context) {
    private val key = longPreferencesKey("session_account_id")

    val accountId: Flow<Long?> = context.vitaPrefs.data.map { it[key] }

    suspend fun set(id: Long) {
        context.vitaPrefs.edit { it[key] = id }
    }

    suspend fun clear() {
        context.vitaPrefs.edit { it.remove(key) }
    }
}

enum class ThemeMode { SYSTEM, DARK, LIGHT }

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode> = context.vitaPrefs.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.vitaPrefs.edit { it[themeKey] = mode.name }
    }

    private val webKey = booleanPreferencesKey("web_search_enabled")
    private val speakKey = booleanPreferencesKey("speak_replies")
    private val langKey = stringPreferencesKey("voice_lang")
    private val wakeKey = booleanPreferencesKey("wake_standby")
    private val exportKey = longPreferencesKey("last_export_at")
    private val reduceKey = booleanPreferencesKey("reduce_effects")
    private val styleKey = stringPreferencesKey("reply_style")
    private val understandKey = stringPreferencesKey("understand_mode")
    private val memoryKey = booleanPreferencesKey("memory_enabled")
    private val convKey = longPreferencesKey("last_conversation_id")

    /** Whether Max learns lasting facts and keeps summaries of your chats. */
    val memoryEnabled: Flow<Boolean> = context.vitaPrefs.data.map { it[memoryKey] ?: true }
    suspend fun setMemoryEnabled(v: Boolean) { context.vitaPrefs.edit { it[memoryKey] = v } }

    val lastConversationId: Flow<Long> = context.vitaPrefs.data.map { it[convKey] ?: 0L }
    suspend fun setLastConversationId(v: Long) { context.vitaPrefs.edit { it[convKey] = v } }

    /** "SMART": the AI reads everything you say. "QUICK": short simple commands are read by fixed rules first (faster). */
    val understandMode: Flow<String> = context.vitaPrefs.data.map { it[understandKey] ?: "SMART" }
    suspend fun setUnderstandMode(v: String) { context.vitaPrefs.edit { it[understandKey] = v } }

    /** Time of the last data export, 0 if never. */
    val lastExportAt: Flow<Long> = context.vitaPrefs.data.map { it[exportKey] ?: 0L }
    suspend fun markExported() { context.vitaPrefs.edit { it[exportKey] = System.currentTimeMillis() } }

    /** Turns off blur and glow, for slower phones. */
    val reduceEffects: Flow<Boolean> = context.vitaPrefs.data.map { it[reduceKey] ?: false }
    suspend fun setReduceEffects(v: Boolean) { context.vitaPrefs.edit { it[reduceKey] = v } }

    /** "SHORT" or "DETAILED": how long Max's chat answers are. */
    val replyStyle: Flow<String> = context.vitaPrefs.data.map { it[styleKey] ?: "SHORT" }
    suspend fun setReplyStyle(v: String) { context.vitaPrefs.edit { it[styleKey] = v } }
    private val dayKey = stringPreferencesKey("search_day")
    private val countKey = intPreferencesKey("search_count")

    val webSearchEnabled: Flow<Boolean> = context.vitaPrefs.data.map { it[webKey] ?: true }
    val speakReplies: Flow<Boolean> = context.vitaPrefs.data.map { it[speakKey] ?: false }

    /** "bn" or "en": which speech model listens when the mic is pressed. */
    val voiceLang: Flow<String> = context.vitaPrefs.data.map { it[langKey] ?: "bn" }

    /** When on, Live mode first waits for "Hey Max" instead of listening to everything. */
    val wakeStandby: Flow<Boolean> = context.vitaPrefs.data.map { it[wakeKey] ?: false }
    suspend fun setWakeStandby(v: Boolean) { context.vitaPrefs.edit { it[wakeKey] = v } }

    suspend fun setWebSearchEnabled(v: Boolean) { context.vitaPrefs.edit { it[webKey] = v } }
    suspend fun setSpeakReplies(v: Boolean) { context.vitaPrefs.edit { it[speakKey] = v } }
    suspend fun setVoiceLang(v: String) { context.vitaPrefs.edit { it[langKey] = v } }

    /** Counts today's web searches so the free monthly quota is not used up by accident. */
    suspend fun takeSearchSlot(max: Int): Boolean {
        var allowed = false
        context.vitaPrefs.edit { p ->
            val today = LocalDate.now().toString()
            val count = if (p[dayKey] == today) (p[countKey] ?: 0) else 0
            if (count < max) {
                p[dayKey] = today
                p[countKey] = count + 1
                allowed = true
            }
        }
        return allowed
    }
}
