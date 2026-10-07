package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.friday.ai.data.local.entity.ChatMessageEntity
import com.friday.ai.data.local.entity.ErrandEntity
import com.friday.ai.data.local.entity.InteractionEntity
import com.friday.ai.data.local.entity.MemoryEntity
import com.friday.ai.data.local.entity.SessionSummaryEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity

/**
 * The whole database at once, for settings export and import.
 *
 * Import replaces everything inside one transaction: a file that fails half
 * way leaves the phone exactly as it was, never half old and half new.
 * Notifications are not part of it — they are a short-lived cache of what
 * this phone showed, pruned anyway.
 */
@Dao
@Suppress("TooManyFunctions") // a read, a clear and an insert per table
abstract class BackupDao {

    @Query("SELECT * FROM user_preferences")
    abstract suspend fun preferences(): List<UserPreferenceEntity>

    @Query("SELECT * FROM memories")
    abstract suspend fun memories(): List<MemoryEntity>

    @Query("SELECT * FROM errands")
    abstract suspend fun errands(): List<ErrandEntity>

    @Query("SELECT * FROM chat_messages")
    abstract suspend fun chat(): List<ChatMessageEntity>

    @Query("SELECT * FROM interactions")
    abstract suspend fun interactions(): List<InteractionEntity>

    @Query("SELECT * FROM session_summaries")
    abstract suspend fun summaries(): List<SessionSummaryEntity>

    @Transaction
    @Suppress("LongParameterList") // one list per table, all replaced together
    open suspend fun replaceAll(
        preferences: List<UserPreferenceEntity>,
        memories: List<MemoryEntity>,
        errands: List<ErrandEntity>,
        chat: List<ChatMessageEntity>,
        interactions: List<InteractionEntity>,
        summaries: List<SessionSummaryEntity>
    ) {
        clearPreferences(); clearMemories(); clearErrands(); clearChat(); clearInteractions(); clearSummaries()
        insertPreferences(preferences)
        insertMemories(memories)
        insertErrands(errands)
        insertChat(chat)
        insertInteractions(interactions)
        insertSummaries(summaries)
    }

    @Query("DELETE FROM user_preferences") protected abstract suspend fun clearPreferences()
    @Query("DELETE FROM memories") protected abstract suspend fun clearMemories()
    @Query("DELETE FROM errands") protected abstract suspend fun clearErrands()
    @Query("DELETE FROM chat_messages") protected abstract suspend fun clearChat()
    @Query("DELETE FROM interactions") protected abstract suspend fun clearInteractions()
    @Query("DELETE FROM session_summaries") protected abstract suspend fun clearSummaries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPreferences(rows: List<UserPreferenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertMemories(rows: List<MemoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertErrands(rows: List<ErrandEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertChat(rows: List<ChatMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertInteractions(rows: List<InteractionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSummaries(rows: List<SessionSummaryEntity>)
}
