package com.friday.ai.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Groq API chat completion request DTO.
 * Mirrors the OpenAI-compatible API format used by Groq.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 800,
    val stream: Boolean = true,
    /** Only for reasoning models; omitted from the JSON when null. */
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    /** What Friday can do, offered to the model; omitted when null. */
    val tools: List<ToolDefinition>? = null
)

/**
 * One message in a conversation with the model. Besides plain text it can
 * carry the model's request to use tools (`assistant` + [toolCalls]) and the
 * result of one (`tool` + [toolCallId]).
 */
@Serializable
data class ApiMessage(
    val role: String,
    val content: String,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSpec
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    /** JSON Schema of the arguments. */
    val parameters: JsonObject
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    /** The arguments as a JSON string, exactly as the model wrote them. */
    val arguments: String
)
