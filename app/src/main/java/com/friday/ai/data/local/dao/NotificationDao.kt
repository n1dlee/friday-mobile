package com.friday.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.friday.ai.data.local.entity.NotificationEntity

@Dao
interface NotificationDao {

    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    /** Unseen notifications, newest first — the pool "what did I miss" reads. */
    @Query(
        "SELECT * FROM notifications WHERE seenByUser = 0 AND postedAt >= :since " +
            "ORDER BY postedAt DESC LIMIT :limit"
    )
    suspend fun unseenSince(since: Long, limit: Int = 50): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE postedAt >= :since ORDER BY postedAt DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int = 50): List<NotificationEntity>

    @Query("UPDATE notifications SET seenByUser = 1 WHERE postedAt <= :upTo")
    suspend fun markSeen(upTo: Long)

    /** Keeps the table from becoming a permanent record of everything read. */
    @Query("DELETE FROM notifications WHERE postedAt < :olderThan")
    suspend fun prune(olderThan: Long)

    @Query("SELECT COUNT(*) FROM notifications WHERE seenByUser = 0")
    suspend fun unseenCount(): Int
}
