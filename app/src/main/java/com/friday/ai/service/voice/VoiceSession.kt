package com.friday.ai.service.voice

import android.util.Log
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.ChatMessageEntity
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.ModelCatalog
import java.util.UUID

/**
 * What a spoken conversation needs to know and leaves behind: settings,
 * the current session, the interaction log, and a copy in the chat history.
 */
class VoiceSession(
    private val prefDao: UserPreferenceDao,
    private val memory: FridayMemory,
    private val chatDao: ChatMessageDao,
    private val catalog: ModelCatalog
) {

    private companion object {
        const val TAG = "VoiceSession"
    }

    suspend fun apiKey(): String = prefDao.get("groq_api_key").orEmpty()

    suspend fun model(): String = catalog.model(com.friday.ai.core.GroqModels.Role.CHAT)

    /** Answers with this when [model] has used up its per-minute allowance. */
    suspend fun backupModel(): String = catalog.model(com.friday.ai.core.GroqModels.Role.FAST)

    /** Groq said the model is gone: re-read what exists so the next turn works. */
    suspend fun repairModel() {
        catalog.refresh()
    }

    /** Whether to answer in Russian. */
    suspend fun russian(): Boolean = (prefDao.get("prefer_russian") ?: "true") == "true"

    suspend fun id(): String = memory.getOrCreateSessionId()

    /** Records the exchange for memory and the dashboard. */
    suspend fun log(user: String, reply: String, kind: String?) {
        memory.logInteraction(user, reply, kind, id())
    }

    /**
     * Copies a spoken exchange into the chat under the same session, so it
     * shows up in the app and can be continued by typing.
     */
    suspend fun mirror(user: String, reply: String) {
        try {
            val session = id()
            val now = System.currentTimeMillis()
            chatDao.insert(entity(user, MessageRole.USER, session, now))
            // One millisecond later, so the reply always sorts after the question.
            chatDao.insert(entity(reply, MessageRole.ASSISTANT, session, now + 1))
        } catch (e: Exception) {
            Log.w(TAG, "Could not mirror voice turn into chat: ${e.message}")
        }
    }

    private fun entity(content: String, role: MessageRole, session: String, at: Long) = ChatMessageEntity(
        id = UUID.randomUUID().toString(),
        content = content,
        role = role.name,
        sessionId = session,
        timestamp = at
    )
}
