package com.friday.ai.domain.usecase

import com.friday.ai.data.local.dao.ChatSessionSummary
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.repository.AssistantRepository
import kotlinx.coroutines.flow.Flow

/**
 * Reads conversation history.
 * Single Responsibility (SOLID-S): only reads and prunes stored messages.
 */
class GetChatHistoryUseCase(private val repository: AssistantRepository) {

    /** @return Reactive Flow of all stored messages, ordered by timestamp. */
    operator fun invoke(): Flow<List<Message>> {
        return repository.getChatHistory()
    }

    /** Messages belonging to one conversation. */
    fun session(sessionId: String): Flow<List<Message>> =
        repository.getSessionMessages(sessionId)

    /** Past conversations, most recently active first. */
    fun sessions(): Flow<List<ChatSessionSummary>> = repository.getSessions()

    /** Delete a single conversation. */
    suspend fun deleteSession(sessionId: String) {
        repository.deleteSession(sessionId)
    }

    /** Clear all stored messages. */
    suspend fun clearHistory() {
        repository.clearHistory()
    }
}
