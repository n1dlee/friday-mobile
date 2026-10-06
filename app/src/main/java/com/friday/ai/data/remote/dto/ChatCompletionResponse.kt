package com.friday.ai.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Groq API streaming response chunk DTO.
 * Each SSE line contains one of these objects.
 */
@Serializable
data class ChatCompletionChunk(
    val id: String = "",
    val choices: List<ChunkChoice> = emptyList()
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    /** Pieces of tool calls; one call's name and arguments arrive spread over several chunks. */
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallDelta>? = null
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionDelta? = null
)

@Serializable
data class FunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)
