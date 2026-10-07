package com.friday.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.friday.ai.data.local.dao.BackupDao
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.dao.InteractionDao
import com.friday.ai.data.local.dao.ErrandDao
import com.friday.ai.data.local.dao.MemoryDao
import com.friday.ai.data.local.dao.ModeDao
import com.friday.ai.data.local.dao.NotificationDao
import com.friday.ai.data.local.dao.SessionSummaryDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.ChatMessageEntity
import com.friday.ai.data.local.entity.InteractionEntity
import com.friday.ai.data.local.entity.ErrandEntity
import com.friday.ai.data.local.entity.ModeEntity
import com.friday.ai.data.local.entity.MemoryEntity
import com.friday.ai.data.local.entity.NotificationEntity
import com.friday.ai.data.local.entity.SessionSummaryEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity

@Database(
    entities = [
        ChatMessageEntity::class,
        UserPreferenceEntity::class,
        MemoryEntity::class,
        InteractionEntity::class,
        NotificationEntity::class,
        SessionSummaryEntity::class,
        ErrandEntity::class,
        ModeEntity::class
    ],
    version = 8,
    // Exported so Room can verify the hand-written migrations.
    exportSchema = true
)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun memoryDao(): MemoryDao
    abstract fun interactionDao(): InteractionDao
    abstract fun notificationDao(): NotificationDao
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun errandDao(): ErrandDao
    abstract fun backupDao(): BackupDao
    abstract fun modeDao(): ModeDao
}
