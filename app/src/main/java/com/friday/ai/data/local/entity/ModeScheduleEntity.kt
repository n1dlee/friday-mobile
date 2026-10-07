package com.friday.ai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * "Включай режим отдыха каждый день в 23:00": when a mode turns on (or,
 * with [exit], off) by itself.
 *
 * [days] is a bitmask, Monday = bit 0 … Sunday = bit 6. Field names are part
 * of the settings-export format.
 */
@Serializable
@Entity(tableName = "mode_schedules", indices = [Index("modeId")])
data class ModeScheduleEntity(
    @PrimaryKey val id: String,
    val modeId: String,
    val exit: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val lastFiredAt: Long = 0
)

/**
 * One time a mode was turned on, by the owner or by a schedule. Habits
 * ("you turn it on around 23:00 every evening") are read from these.
 */
@Entity(tableName = "mode_runs", indices = [Index("modeId")])
data class ModeRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modeId: String,
    val at: Long,
    val automatic: Boolean
)
