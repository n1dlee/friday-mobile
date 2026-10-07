package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.friday.ai.data.local.entity.ModeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModeDao {

    @Query("SELECT * FROM modes ORDER BY createdAt")
    suspend fun all(): List<ModeEntity>

    @Query("SELECT * FROM modes ORDER BY createdAt")
    fun observeAll(): Flow<List<ModeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mode: ModeEntity)

    @Query("DELETE FROM modes WHERE id = :id")
    suspend fun delete(id: String)
}
