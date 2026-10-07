package com.friday.ai.data.local.entity

import androidx.room.Entity
import kotlinx.serialization.Serializable
import androidx.room.PrimaryKey

/**
 * A written summary of one conversation.
 *
 * The dashboard used to show "N messages", which says nothing about what was
 * actually discussed. This holds the real thing, generated once per session
 * and refreshed as the conversation grows, so looking back at a day tells you
 * what happened rather than how much was typed.
 *
 * [messageCount] records what the summary was written from, so it can be
 * regenerated only when the conversation has meaningfully moved on.
 */
@Serializable
@Entity(tableName = "session_summaries")
data class SessionSummaryEntity(
    @PrimaryKey val sessionId: String,
    val summary: String,
    val messageCount: Int,
    val updatedAt: Long = System.currentTimeMillis()
)
