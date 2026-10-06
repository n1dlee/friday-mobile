package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.friday.ai.data.local.entity.SessionSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionSummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SessionSummaryEntity)

    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun forSession(sessionId: String): SessionSummaryEntity?

    @Query("SELECT * FROM session_summaries")
    suspend fun all(): List<SessionSummaryEntity>

    @Query("SELECT * FROM session_summaries")
    fun observeAll(): Flow<List<SessionSummaryEntity>>

    @Query("DELETE FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}
