package com.friday.ai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A notification Friday saw while it was on screen.
 *
 * Only what's needed to answer "what did I miss" is kept — app, title, a
 * short text and when. Nothing is uploaded anywhere, and rows are pruned so
 * this never becomes a long-term log of everything the user reads.
 */
@Entity(
    tableName = "notifications",
    indices = [Index("postedAt"), Index("packageName")]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val seenByUser: Boolean = false
)
