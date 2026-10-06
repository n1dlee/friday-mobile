package com.friday.ai.domain.model

import java.util.UUID

/**
 * Represents a single chat message in the conversation.
 * Immutable data class following FP principles.
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val role: MessageRole,
    val sessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

/**
 * Role of the message sender.
 * Single Responsibility: only defines who sent the message.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
