package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.friday.ai.data.local.entity.ModeEventEntity
import com.friday.ai.data.local.entity.ModeRunEntity
import com.friday.ai.data.local.entity.ModeScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // schedules, events and run history: three small tables, one owner
interface ModeScheduleDao {

    @Query("SELECT * FROM mode_schedules")
    suspend fun all(): List<ModeScheduleEntity>

    @Query("SELECT * FROM mode_schedules")
    fun observeAll(): Flow<List<ModeScheduleEntity>>

    @Query("SELECT * FROM mode_schedules WHERE id = :id")
    suspend fun byId(id: String): ModeScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ModeScheduleEntity)

    @Query("DELETE FROM mode_schedules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mode_schedules WHERE modeId = :modeId")
    suspend fun deleteForMode(modeId: String)

    @Query("SELECT * FROM mode_events")
    suspend fun events(): List<ModeEventEntity>

    @Query("SELECT * FROM mode_events")
    fun observeEvents(): Flow<List<ModeEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: ModeEventEntity)

    @Query("DELETE FROM mode_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Query("DELETE FROM mode_events WHERE modeId = :modeId")
    suspend fun deleteEventsForMode(modeId: String)

    @Insert
    suspend fun logRun(run: ModeRunEntity)

    @Query("SELECT * FROM mode_runs WHERE modeId = :modeId AND at >= :since ORDER BY at")
    suspend fun runsSince(modeId: String, since: Long): List<ModeRunEntity>

    /** Old history isn't needed for habits; kept small. */
    @Query("DELETE FROM mode_runs WHERE at < :before")
    suspend fun pruneRuns(before: Long)
}
