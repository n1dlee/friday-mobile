package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.friday.ai.data.local.entity.ErrandEntity

@Dao
interface ErrandDao {

    @Insert
    suspend fun insert(errand: ErrandEntity): Long

    /** Errands that are still wanted and not snoozed right now. */
    @Query(
        "SELECT * FROM errands WHERE done = 0 AND snoozedUntil <= :now " +
            "ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun active(now: Long = System.currentTimeMillis(), limit: Int = 10): List<ErrandEntity>

    @Query("SELECT * FROM errands WHERE done = 0 ORDER BY createdAt DESC")
    suspend fun allOpen(): List<ErrandEntity>

    @Query("UPDATE errands SET done = 1 WHERE id = :id")
    suspend fun markDone(id: Long)

    @Query("UPDATE errands SET snoozedUntil = :until WHERE id = :id")
    suspend fun snooze(id: Long, until: Long)

    @Query("UPDATE errands SET lastNotifiedAt = :at WHERE id = :id")
    suspend fun markNotified(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM errands WHERE done = 0")
    suspend fun openCount(): Int
}
