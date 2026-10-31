package app.vitaflow.com.max

import app.vitaflow.com.ai.LlmEngine
import app.vitaflow.com.data.ProfileRepository
import app.vitaflow.com.data.SettingsStore
import app.vitaflow.com.data.db.MemoryFactEntity
import app.vitaflow.com.data.db.VitaDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray

/**
 * Max's memory.
 *  - Inside a chat: the whole chat is saved. Older parts are kept as a rolling summary, the latest messages are kept
 *    word for word, and when you mention something from long ago the matching old messages are looked up.
 *  - Outside a chat: every chat has a one-line digest, and Max keeps a list of lasting facts about you.
 * All of it stays on the phone, and facts can be read and deleted.
 */
class MemoryManager(
    private val db: VitaDatabase,
    private val llm: LlmEngine,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore
) {
    private companion object {
        const val RECENT = 6          // latest messages kept word for word
        const val FOLD_AFTER = 12     // fold older messages into the summary after this many
        const val MAX_FACTS = 100
        val stop = setOf("করো", "করুন", "আমার", "আমি", "তুমি", "তোমার", "এটা", "ওটা", "সেটা", "কি", "কী", "the", "and", "for", "you", "that", "this", "with", "have", "what")
    }

    suspend fun enabled(): Boolean = settings.memoryEnabled.first()

    fun facts(accountId: Long): Flow<List<MemoryFactEntity>> = db.memoryDao().observe(accountId)
    suspend fun deleteFact(id: Long) = db.memoryDao().delete(id)
    suspend fun clearFacts(accountId: Long) = db.memoryDao().clear(accountId)
    suspend fun addFact(accountId: Long, text: String) { addFacts(accountId, 0, listOf(text)) }

    private fun ago(ms: Long): String {
        val days = ((System.currentTimeMillis() - ms) / (24L * 60 * 60 * 1000)).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            else -> "$days days ago"
        }
    }

    /**
     * Everything Max should know at the start of a chat session: the user, lasting facts, what other chats were about,
     * and this chat so far. [skipLastUser] leaves out the message that is being answered right now.
     */
    suspend fun contextFor(accountId: Long, conversationId: Long, skipLastUser: Boolean): String {
        val sb = StringBuilder()
        profiles.summaryLine(accountId).takeIf { it.isNotBlank() }?.let { sb.append("About the user: $it\n") }
        if (enabled()) {
            val facts = db.memoryDao().allOnce(accountId).take(12)
            if (facts.isNotEmpty()) sb.append("Things you remember about the user:\n").append(facts.joinToString("\n") { "- ${it.text}" }).append("\n")
            val others = db.conversationDao().allOnce(accountId).filter { it.id != conversationId && it.digest.isNotBlank() }.take(8)
            if (others.isNotEmpty()) {
                sb.append("Other chats you had (one line each):\n")
                    .append(others.joinToString("\n") { "- ${ago(it.updatedAt)}: ${it.digest}" }).append("\n")
            }
        }
        val conv = db.conversationDao().get(conversationId)
        if (conv != null && conv.summary.isNotBlank()) sb.append("Earlier in this chat (summary): ${conv.summary}\n")
        var recent = db.chatDao().recent(conversationId, RECENT + 1).reversed()
        if (skipLastUser && recent.lastOrNull()?.role == "USER") recent = recent.dropLast(1)
        recent = recent.takeLast(RECENT)
        if (recent.isNotEmpty()) {
            sb.append("Latest messages in this chat:\n")
                .append(recent.joinToString("\n") { "${if (it.role == "USER") "User" else "Max"}: ${it.text.take(240)}" }).append("\n")
        }
        return sb.toString().take(3200)
    }

    /** Looks for older messages of this chat that share words with the new message, so "that thing from last week" can be found. */
    suspend fun retrieve(conversationId: Long, query: String): String {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.distinct()
        if (words.isEmpty()) return ""
        val all = db.chatDao().allOnce(conversationId)
        if (all.size <= RECENT + 2) return ""
        val older = all.dropLast(RECENT + 1)
        val hits = older.map { m -> m to words.count { w -> m.text.lowercase().contains(w) } }
            .filter { it.second >= 2 || (words.size == 1 && it.second >= 1) }
            .sortedByDescending { it.second }.take(2)
        if (hits.isEmpty()) return ""
        return "[Earlier in this chat: " + hits.joinToString(" | ") { (m, _) ->
            "${if (m.role == "USER") "User" else "Max"}: ${m.text.take(200)}"
        } + "]"
    }

    /**
     * Folds older messages into the chat's summary, writes the one-line digest and learns new facts.
     * [force] is used when you leave a chat, so the digest is always up to date outside it.
     */
    suspend fun foldIfNeeded(accountId: Long, conversationId: Long, force: Boolean = false) {
        if (!enabled() || !llm.isReady()) return
        val conv = db.conversationDao().get(conversationId) ?: return
        val all = db.chatDao().allOnce(conversationId)
        if (all.size < 2) return
        val unsummarized = all.filter { it.id > conv.summarizedUpTo }
        val windowStart = maxOf(0, all.size - RECENT)
        val older = unsummarized.filter { all.indexOf(it) < windowStart }
        val needFold = older.size >= FOLD_AFTER
        val needDigest = force && (conv.digest.isBlank() || unsummarized.isNotEmpty())
        if (!needFold && !needDigest) return
        val toFold = if (force) unsummarized else older
        if (toFold.isEmpty()) return

        val system = """
You keep a short memory for a fitness assistant. Reply with JSON only: {"summary":"...","digest":"...","facts":["..."]}
summary: the updated summary of the whole chat (max 120 words, same language as the chat).
digest: one line about what this chat was about (max 15 words).
facts: new lasting facts about the user worth remembering (preferences, habits, health limits, goals, important people), at most 5, each under 15 words. Empty list if there is nothing new.
""".trimIndent()
        val user = "Previous summary: ${conv.summary.ifBlank { "none" }}\nNew messages:\n" +
            toFold.joinToString("\n") { "${if (it.role == "USER") "User" else "Max"}: ${it.text.take(400)}" }
        val raw = runCatching { llm.complete(system, user, 0.2) }.getOrNull() ?: return
        val o = IntentValidator.extractJson(raw) ?: return
        val summary = o.optString("summary").trim().take(900)
        if (summary.isBlank()) return
        db.conversationDao().update(
            conv.copy(
                summary = summary,
                digest = o.optString("digest").trim().take(120).ifBlank { conv.digest },
                summarizedUpTo = toFold.last().id
            )
        )
        val arr: JSONArray? = o.optJSONArray("facts")
        if (arr != null) addFacts(accountId, conversationId, (0 until arr.length()).map { arr.optString(it) })
    }

    private suspend fun addFacts(accountId: Long, conversationId: Long, texts: List<String>) {
        val existing = db.memoryDao().allOnce(accountId)
        val known = existing.map { it.text.lowercase().trim() }.toMutableSet()
        for (raw in texts) {
            val t = raw.trim().take(120)
            if (t.length < 4 || t.lowercase() in known) continue
            known += t.lowercase()
            db.memoryDao().insert(MemoryFactEntity(accountId = accountId, text = t, conversationId = conversationId))
        }
        // keep the list small: the oldest facts go first
        val all = db.memoryDao().allOnce(accountId)
        if (all.size > MAX_FACTS) all.drop(MAX_FACTS).forEach { db.memoryDao().delete(it.id) }
    }
}
