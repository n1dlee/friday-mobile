package com.friday.ai.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema upgrades that preserve what the user has accumulated.
 *
 * Until now the database was rebuilt from scratch on every version bump. That
 * was fine while the app was being roughed out, but the memory graph, chat
 * history and learned facts are the most valuable thing in it — losing them
 * to a routine schema change is not acceptable. Every future version needs a
 * migration here; there is deliberately no destructive fallback to hide a
 * missing one.
 *
 * Each statement mirrors exactly what Room generates for the corresponding
 * entity. If they drift, Room fails loudly at open time rather than silently
 * corrupting anything — and the exported schemas under `app/schemas` are what
 * it compares against.
 */
object Migrations {

    /** v2 added the memory and interaction log. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memories` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `category` TEXT NOT NULL,
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `interactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `userInput` TEXT NOT NULL,
                    `assistantResponse` TEXT NOT NULL,
                    `commandType` TEXT,
                    `timestamp` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /** v3 grouped interactions into conversation sessions. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `interactions` ADD COLUMN `sessionId` TEXT")
        }
    }

    /** v4 gave chat messages the same session grouping, so voice and typed
     *  turns land in one thread. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `sessionId` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
        }
    }

    /** v5 started recording notifications for "what did I miss". */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notifications` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appName` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `postedAt` INTEGER NOT NULL,
                    `seenByUser` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_postedAt` ON `notifications` (`postedAt`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notifications_packageName` " +
                    "ON `notifications` (`packageName`)"
            )
        }
    }

    /** v6 stores a written summary per conversation. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Note the trailing PRIMARY KEY clause rather than an inline one:
            // that is what Room generates for a non-generated primary key, and
            // anything else makes it reject the migration at open time.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_summaries` (" +
                    "`sessionId` TEXT NOT NULL, " +
                    "`summary` TEXT NOT NULL, " +
                    "`messageCount` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionId`))"
            )
        }
    }

    /** v7 added location-based errands. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `errands` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`what` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`done` INTEGER NOT NULL, " +
                    "`snoozedUntil` INTEGER NOT NULL, " +
                    "`lastNotifiedAt` INTEGER NOT NULL)"
            )
        }
    }

    /** Every migration, in order, for the database builder. */
    /** v8 added modes the owner creates by voice. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `modes` (" +
                    "`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`aliases` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`steps` TEXT NOT NULL, " +
                    "`undo` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`lastRunAt` INTEGER NOT NULL, " +
                    "`runCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
        }
    }

    /** v9: modes on a schedule, and the history habits are read from. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `mode_schedules` (" +
                    "`id` TEXT NOT NULL, " +
                    "`modeId` TEXT NOT NULL, " +
                    "`exit` INTEGER NOT NULL, " +
                    "`hour` INTEGER NOT NULL, " +
                    "`minute` INTEGER NOT NULL, " +
                    "`days` INTEGER NOT NULL, " +
                    "`lastFiredAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_mode_schedules_modeId` ON `mode_schedules` (`modeId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `mode_runs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`modeId` TEXT NOT NULL, " +
                    "`at` INTEGER NOT NULL, " +
                    "`automatic` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_mode_runs_modeId` ON `mode_runs` (`modeId`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9
    )
}
