package com.friday.ai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "interactions")
data class InteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userInput: String,
    val assistantResponse: String,
    val commandType: String?,
    val sessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
