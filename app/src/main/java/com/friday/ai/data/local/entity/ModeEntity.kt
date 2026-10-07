package com.friday.ai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A mode the owner created by voice: "создай режим грусти — Spotify с
 * грустными песнями".
 *
 * [steps] and [undo] are JSON arrays of action envelopes, the same
 * language learned phrases and the model's tool calls use. [undo] is set
 * while the mode is on: the actions that put back what its steps changed,
 * newest first. It is kept here, not in memory, so "выключи режим отдыха"
 * still works after the phone restarts.
 *
 * Field names are part of the settings-export format.
 */
@Serializable
@Entity(tableName = "modes")
data class ModeEntity(
    @PrimaryKey val id: String,
    /** As the owner said it after "режим": "грусти", "отдыха". */
    val name: String,
    /** Other ways the owner has called it, learned over time (JSON array of strings). */
    val aliases: String = "[]",
    /** The owner's own words describing it, kept to show and to recompile from. */
    val description: String,
    val steps: String,
    val undo: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0,
    val runCount: Int = 0
)
