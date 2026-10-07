package com.friday.ai.data.remote

import com.friday.ai.core.GroqModels
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.ChatCompletionChunk
import com.friday.ai.data.remote.dto.ChatCompletionRequest
import com.friday.ai.data.remote.dto.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

class GroqApiService private constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODELS_URL = "https://api.groq.com/openai/v1/models"
        private const val WHISPER_MODEL = "whisper-large-v3-turbo"

        /** Text chat: room for a real answer, but not an essay. */
        const val DEFAULT_MAX_TOKENS = 800

        /** Searching takes reasoning tokens of its own before the short answer. */
        private const val BROWSE_MAX_TOKENS = 1500

        /** Spoken replies are one or two sentences — anything more is a bug, not a feature. */
        const val VOICE_MAX_TOKENS = 150

        fun create(): GroqApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            return GroqApiService(client, groqJson)
        }
    }

    fun streamCompletion(
        apiKey: String,
        messages: List<ApiMessage>,
        model: String = GroqModels.fallback(GroqModels.Role.CHAT),
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): Flow<String> = streamChat(apiKey, messages, model, maxTokens)
        .filterIsInstance<ChatEvent.Text>()
        .map { it.token }

    /**
     * A streamed reply that may also ask to use [tools]. Text is emitted as
     * it arrives; tool calls, which come in pieces, are emitted once whole.
     */
    fun streamChat(
        apiKey: String,
        messages: List<ApiMessage>,
        model: String = GroqModels.fallback(GroqModels.Role.CHAT),
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        tools: List<ToolDefinition>? = null
    ): Flow<ChatEvent> = flow {
        val offered = tools?.takeIf { it.isNotEmpty() }
        val thorough = offered != null
        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            maxTokens = GroqModels.tokenBudget(model, maxTokens, thorough),
            stream = true,
            reasoningEffort = GroqModels.reasoningEffort(model, thorough),
            tools = offered
        )

        val requestBody = json.encodeToString(
            ChatCompletionRequest.serializer(),
            request
        ).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw GroqApiException(
                code = response.code,
                message = parseErrorMessage(response.code, errorBody),
                retryAfterMs = GroqApiException.retryAfterMs(errorBody)
            )
        }

        val reader: BufferedReader = response.body?.byteStream()?.bufferedReader()
            ?: throw GroqApiException(code = 0, message = "Empty response body")

        val toolCalls = ToolCallAssembler()
        reader.use { buffered ->
            buffered.lineSequence().forEach { line ->
                if (!line.startsWith("data: ")) return@forEach
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") return@forEach

                val delta = json.decodeFromString(ChatCompletionChunk.serializer(), data)
                    .choices.firstOrNull()?.delta ?: return@forEach
                delta.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.Text(it)) }
                delta.toolCalls?.let { toolCalls.add(it) }
            }
        }
        toolCalls.calls().takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.ToolCalls(it)) }
    }.flowOn(Dispatchers.IO)

    fun analyzeImage(
        apiKey: String,
        imageBase64: String,
        prompt: String = "Describe what you see on this screen in detail. " +
            "Answer in the same language as the user's question.",
        model: String = GroqModels.fallback(GroqModels.Role.VISION)
    ): Flow<String> = flow {
        val messagesJson = JsonArray(listOf(
            JsonObject(mapOf(
                "role" to JsonPrimitive("user"),
                "content" to JsonArray(listOf(
                    JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(prompt)
                    )),
                    JsonObject(mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf(
                            "url" to JsonPrimitive("data:image/png;base64,$imageBase64")
                        ))
                    ))
                ))
            ))
        ))

        val bodyJson = JsonObject(mapOf(
            "model" to JsonPrimitive(model),
            "messages" to messagesJson,
            "max_tokens" to JsonPrimitive(4096),
            "stream" to JsonPrimitive(true)
        ))

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw GroqApiException(
                code = response.code,
                message = parseErrorMessage(response.code, errorBody)
            )
        }

        val reader = response.body?.byteStream()?.bufferedReader()
            ?: throw GroqApiException(code = 0, message = "Empty response body")

        reader.use { buffered ->
            buffered.lineSequence().forEach { line ->
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") return@forEach

                    val parsed = json.parseToJsonElement(data).jsonObject
                    val choices = parsed["choices"]?.jsonArray ?: return@forEach
                    val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                    val content = delta?.get("content")?.jsonPrimitive?.content
                    if (content != null) emit(content)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun analyzeText(
        apiKey: String,
        text: String,
        prompt: String = "Analyze the following content and provide insights:",
        model: String = GroqModels.fallback(GroqModels.Role.CHAT)
    ): Flow<String> {
        val messages = listOf(
            ApiMessage(role = "user", content = "$prompt\n\n$text")
        )
        return streamCompletion(apiKey, messages, model)
    }

    /**
     * Asks [model] a question it answers after searching the web with Groq's
     * built-in `browser_search` tool. Not streamed: the searching happens on
     * Groq's side and only the finished answer is worth having.
     */
    fun browse(apiKey: String, model: String, system: String, question: String): String {
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "reasoning_effort" to JsonPrimitive("low"),
                "max_tokens" to JsonPrimitive(BROWSE_MAX_TOKENS),
                "tools" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("browser_search"))))),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(system))),
                        JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(question)))
                    )
                )
            )
        )
        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GroqApiException(
                    response.code, parseErrorMessage(response.code, text), GroqApiException.retryAfterMs(text)
                )
            }
            return json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                .ifBlank { throw GroqApiException(0, "The search returned no answer") }
        }
    }

    /** Ids of the models this key can use right now. */
    fun listModels(apiKey: String): Set<String> {
        val request = Request.Builder().url(MODELS_URL).addHeader("Authorization", "Bearer $apiKey").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GroqApiException(response.code, parseErrorMessage(response.code, body))
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptySet()
            return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()
        }
    }

    /** @param prompt words to expect — the owner's contacts — so names come back spelled right */
    fun transcribeAudio(apiKey: String, audioFile: File, language: String? = null, prompt: String? = null): String {
        val fileBody = audioFile.asRequestBody("audio/wav".toMediaType())

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, fileBody)
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart("response_format", "json")

        if (language != null) {
            multipartBuilder.addFormDataPart("language", language)
        }
        if (prompt != null) {
            multipartBuilder.addFormDataPart("prompt", prompt)
        }

        val httpRequest = Request.Builder()
            .url(WHISPER_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBuilder.build())
            .build()

        val response = client.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw GroqApiException(
                code = response.code,
                message = parseErrorMessage(response.code, errorBody)
            )
        }

        val body = response.body?.string() ?: throw GroqApiException(0, "Empty response")
        val parsed = json.parseToJsonElement(body).jsonObject
        return parsed["text"]?.jsonPrimitive?.content ?: ""
    }

    private fun parseErrorMessage(code: Int, body: String): String = when (code) {
        401 -> "Invalid API key. Check your Groq API key in Settings."
        429 -> "Rate limit exceeded. Please wait a moment and try again."
        503 -> "Groq service is temporarily unavailable. Try again later."
        else -> "API error ($code): $body"
    }
}

/**
 * @param retryAfterMs how long Groq said to wait, when it said; only rate
 *   limits carry it.
 */
class GroqApiException(
    val code: Int,
    override val message: String,
    val retryAfterMs: Long? = null
) : Exception(message) {
    /** This model's per-minute allowance is used up; another model has its own. */
    val rateLimited: Boolean
        get() = code == HTTP_TOO_MANY_REQUESTS

    /** The model's output could not be read back (it happens with browser search); a retry usually works. */
    val outputParseFailed: Boolean
        get() = message.contains("output_parse_failed")

    /** The model wrote a tool call Groq could not parse; another try usually works. */
    val toolUseFailed: Boolean
        get() = message.contains("tool_use_failed")

    /** The requested model is gone or not available to this key; worth re-picking. */
    val modelMissing: Boolean
        get() = code == HTTP_NOT_FOUND && message.contains("model_not_found") ||
            message.contains("does not exist or you do not have access")

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MS_PER_SECOND = 1000

        private val tryAgainIn = Regex("""try again in ([\d.]+)(ms|s)""")

        /** Reads "Please try again in 2.3025s" (or "855ms") from a rate-limit reply. */
        fun retryAfterMs(body: String): Long? {
            val (amount, unit) = tryAgainIn.find(body)?.destructured ?: return null
            val value = amount.toDoubleOrNull() ?: return null
            return (if (unit == "s") value * MS_PER_SECOND else value).toLong()
        }
    }
}

/** How requests to Groq are written and replies read. Shared so tests use the same rules. */
internal val groqJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    // Optional request fields such as reasoning_effort are left out rather
    // than sent as null, which models without that setting may reject.
    explicitNulls = false
}
