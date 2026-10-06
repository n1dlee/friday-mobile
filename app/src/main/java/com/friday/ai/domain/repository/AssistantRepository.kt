package com.friday.ai.domain.repository

import com.friday.ai.data.local.dao.ChatSessionSummary
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract following Dependency Inversion Principle (SOLID-D).
 * Domain layer depends on this abstraction, not on concrete implementations.
 */
interface AssistantRepository {

    /** Stream tokens from Groq API for the given user prompt. */
    suspend fun sendMessage(prompt: String, history: List<Message>, mode: AssistantMode): Flow<String>

    /** Observe all chat messages from local DB as a reactive Flow. */
    fun getChatHistory(): Flow<List<Message>>

    /** Observe the messages of one conversation. */
    fun getSessionMessages(sessionId: String): Flow<List<Message>>

    /** Observe the list of past conversations, most recent first. */
    fun getSessions(): Flow<List<ChatSessionSummary>>

    /** Persist a message to local storage. */
    suspend fun saveMessage(message: Message)

    /** Delete one conversation. */
    suspend fun deleteSession(sessionId: String)

    /** Delete all messages from local storage. */
    suspend fun clearHistory()

    /** Analyze an image via Groq Vision API. */
    suspend fun analyzeImage(imageBase64: String, prompt: String): Flow<String>

    /** Analyze text content via Groq API. */
    suspend fun analyzeText(text: String, prompt: String): Flow<String>
}
