package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.friday.ai.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/** One row per conversation, for the session list. */
data class ChatSessionSummary(
    val sessionId: String,
    val title: String?,
    val startedAt: Long,
    val lastActivityAt: Long,
    val messageCount: Int
)

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeSession(sessionId: String): Flow<List<ChatMessageEntity>>

    /**
     * Conversations, most recently active first. The title is the first thing
     * the user said in that conversation — the same convention chat apps use,
     * and it avoids spending an LLM call just to name a thread.
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               (SELECT c.content FROM chat_messages c
                 WHERE c.sessionId = m.sessionId AND c.role = 'USER'
                 ORDER BY c.timestamp ASC LIMIT 1) AS title,
               MIN(m.timestamp) AS startedAt,
               MAX(m.timestamp) AS lastActivityAt,
               COUNT(*) AS messageCount
          FROM chat_messages m
         WHERE m.sessionId IS NOT NULL
         GROUP BY m.sessionId
         ORDER BY lastActivityAt DESC
        """
    )
    fun observeSessions(): Flow<List<ChatSessionSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int
}
