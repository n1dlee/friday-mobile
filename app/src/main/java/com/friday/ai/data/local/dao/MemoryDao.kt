package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.friday.ai.data.local.entity.MemoryEntity

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY updatedAt DESC")
    suspend fun getByCategory(category: String): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE `key` = :key AND category = :category LIMIT 1")
    suspend fun findByKey(key: String, category: String): MemoryEntity?

    @Query(
        "SELECT * FROM memories WHERE value LIKE '%' || :query || '%' " +
            "OR `key` LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int = 20): List<MemoryEntity>

    @Query("UPDATE memories SET value = :value, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateValue(id: Long, value: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}
