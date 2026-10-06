package com.friday.ai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting chat messages.
 *
 * [sessionId] groups messages into a conversation. Voice turns and typed
 * turns share the same session ids, so a conversation started by talking
 * shows up in the chat history and can be continued by typing.
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index("sessionId"), Index("timestamp")]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val role: String,
    val sessionId: String? = null,
    val timestamp: Long
)
