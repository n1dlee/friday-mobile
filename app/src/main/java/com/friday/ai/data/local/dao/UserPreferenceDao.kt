package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.friday.ai.data.local.entity.UserPreferenceEntity

/**
 * Data Access Object for user preferences.
 * Interface Segregation (SOLID-I): only preference operations.
 */
@Dao
interface UserPreferenceDao {

    @Query("SELECT value FROM user_preferences WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(preference: UserPreferenceEntity)

    /** Every preference whose key starts with [prefix] — a group such as learned commands. */
    @Query("SELECT * FROM user_preferences WHERE `key` LIKE :prefix || '%'")
    suspend fun withPrefix(prefix: String): List<UserPreferenceEntity>

    @Query("DELETE FROM user_preferences WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM user_preferences WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteWithPrefix(prefix: String)
}
