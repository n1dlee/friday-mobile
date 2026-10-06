package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.friday.ai.data.local.entity.InteractionEntity

@Dao
interface InteractionDao {

    @Insert
    suspend fun insert(interaction: InteractionEntity): Long

    @Query("SELECT * FROM interactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<InteractionEntity>

    @Query("SELECT * FROM interactions WHERE commandType = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 50): List<InteractionEntity>

    @Query("SELECT * FROM interactions WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 20): List<InteractionEntity>

    @Query(
        "SELECT commandType, COUNT(*) as cnt FROM interactions " +
            "WHERE commandType IS NOT NULL GROUP BY commandType ORDER BY cnt DESC"
    )
    suspend fun getCommandStats(): List<CommandStat>

    @Query("SELECT COUNT(*) FROM interactions")
    suspend fun count(): Int
}

data class CommandStat(
    val commandType: String,
    val cnt: Int
)
