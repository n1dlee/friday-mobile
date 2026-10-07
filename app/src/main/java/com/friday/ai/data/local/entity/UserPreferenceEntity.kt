package com.friday.ai.data.local.entity

import androidx.room.Entity
import kotlinx.serialization.Serializable
import androidx.room.PrimaryKey

/**
 * Room entity for storing user preferences (API key, model, mode).
 * Key-value storage pattern for flexibility.
 */
@Serializable
@Entity(tableName = "user_preferences")
data class UserPreferenceEntity(
    @PrimaryKey
    val key: String,
    val value: String
)
