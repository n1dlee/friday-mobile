package com.friday.ai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * "Когда подключаюсь к машине — включай режим вождения": a mode started
 * (or, with [exit], ended) by something happening to the phone.
 *
 * [kind] is "bluetooth" (a device by [value], its name as the phone shows
 * it), "wifi" (a network by [value], its SSID) or "charger". [onConnect]
 * false means on disconnecting. Field names are part of the settings-export
 * format.
 */
@Serializable
@Entity(tableName = "mode_events", indices = [Index("modeId")])
data class ModeEventEntity(
    @PrimaryKey val id: String,
    val modeId: String,
    val kind: String,
    val value: String,
    val onConnect: Boolean,
    val exit: Boolean
)
