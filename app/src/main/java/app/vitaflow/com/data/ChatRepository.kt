package app.vitaflow.com.data

import app.vitaflow.com.ai.AttachmentJson
import app.vitaflow.com.data.db.ChatMessageEntity
import app.vitaflow.com.data.db.ConversationEntity
import app.vitaflow.com.data.db.VitaDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Chats (conversations) and their messages. Every message belongs to one chat. */
class ChatRepository(private val db: VitaDatabase) {
    private val chats = db.chatDao()
    private val conversations = db.conversationDao()

    fun conversations(accountId: Long): Flow<List<ConversationEntity>> = conversations.observe(accountId)
    fun messages(conversationId: Long): Flow<List<ChatMessageEntity>> = chats.observe(conversationId)
    suspend fun conversation(id: Long): ConversationEntity? = conversations.get(id)

    suspend fun newConversation(accountId: Long): Long = conversations.insert(ConversationEntity(accountId = accountId))

    suspend fun add(
        accountId: Long,
        conversationId: Long,
        role: String,
        text: String,
        changeIds: List<Long> = emptyList(),
        sourcesJson: String? = null,
        attachmentsJson: String = ""
    ): Long {
        val id = chats.insert(
            ChatMessageEntity(
                accountId = accountId, conversationId = conversationId, role = role, text = text,
                changeIds = changeIds.joinToString(","), sourcesJson = sourcesJson, attachments = attachmentsJson
            )
        )
        conversations.get(conversationId)?.let { c ->
            val title = if (c.title.isBlank() && role == "USER") {
                text.trim().ifBlank { "ছবি / ফাইল" }.take(40)
            } else c.title
            conversations.update(c.copy(title = title, updatedAt = System.currentTimeMillis()))
        }
        return id
    }

    private fun deleteFiles(messages: List<ChatMessageEntity>) {
        messages.forEach { m -> AttachmentJson.decode(m.attachments).forEach { runCatching { File(it.path).delete() } } }
    }

    suspend fun deleteConversation(conversationId: Long) {
        deleteFiles(chats.allOnce(conversationId))
        chats.deleteConversation(conversationId)
        conversations.delete(conversationId)
    }

    /** Removes every chat (and the photos sent in them) of this account. Facts Max learned stay until deleted in the memory screen. */
    suspend fun clearAll(accountId: Long) {
        deleteFiles(chats.withAttachments(accountId))
        chats.clear(accountId)
        conversations.clear(accountId)
    }
}
