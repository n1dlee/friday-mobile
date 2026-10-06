package com.friday.ai.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LazuriDeviceRegisterRequest(
    val name: String,
    val platform: String
)

@Serializable
data class LazuriDeviceResponse(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeen: String? = null,
    val createdAt: String? = null
)

@Serializable
data class LazuriNoteCreateRequest(
    val title: String,
    val content: String
)

@Serializable
data class LazuriMemoryCreateRequest(
    val sessionId: String? = null,
    val role: String,
    val content: String,
    val sourceDeviceId: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class LazuriMemoryResponse(
    val id: String,
    val sessionId: String? = null,
    val role: String,
    val content: String,
    val sourceDeviceId: String? = null,
    val metadata: Map<String, String>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
