package com.friday.ai.data.local.entity

import androidx.room.Entity
import kotlinx.serialization.Serializable
import androidx.room.PrimaryKey

/**
 * Something to do next time you're near the right kind of place.
 *
 * "Напомни купить молоко" has no useful time attached — it's about *where*
 * you are, not when. These sit dormant until you happen to be near a shop.
 *
 * [snoozedUntil] exists so "потом" actually means later rather than never,
 * and [done] so a dismissed errand stops nagging entirely.
 */
@Serializable
@Entity(tableName = "errands")
data class ErrandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What to get, in the user's own words. */
    val what: String,
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    /** Epoch millis before which this errand stays quiet. */
    val snoozedUntil: Long = 0,
    /** When the user was last told about this, to avoid repeat pings. */
    val lastNotifiedAt: Long = 0
)
