package com.friday.ai.data.remote

import com.friday.ai.data.remote.dto.ChatCompletionChunk
import com.friday.ai.data.remote.dto.ChatCompletionRequest
import com.friday.ai.data.remote.dto.ApiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqApiResponseParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parse streaming chunk with content token`() {
        val raw = """
            {
                "id": "chatcmpl-123",
                "choices": [{
                    "index": 0,
                    "delta": {"content": "Hello"},
                    "finish_reason": null
                }]
            }
        """.trimIndent()

        val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), raw)

        assertEquals("chatcmpl-123", chunk.id)
        assertEquals(1, chunk.choices.size)
        assertEquals("Hello", chunk.choices[0].delta.content)
        assertNull(chunk.choices[0].finishReason)
    }

    @Test
    fun `parse streaming chunk with finish reason stop`() {
        val raw = """
            {
                "id": "chatcmpl-456",
                "choices": [{
                    "index": 0,
                    "delta": {},
                    "finish_reason": "stop"
                }]
            }
        """.trimIndent()

        val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), raw)

        assertEquals("stop", chunk.choices[0].finishReason)
        assertNull(chunk.choices[0].delta.content)
    }

    @Test
    fun `parse streaming chunk with role delta`() {
        val raw = """
            {
                "id": "chatcmpl-789",
                "choices": [{
                    "index": 0,
                    "delta": {"role": "assistant"},
                    "finish_reason": null
                }]
            }
        """.trimIndent()

        val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), raw)

        assertEquals("assistant", chunk.choices[0].delta.role)
        assertNull(chunk.choices[0].delta.content)
    }

    @Test
    fun `serialize chat completion request correctly`() {
        val request = ChatCompletionRequest(
            model = "llama-3.3-70b-versatile",
            messages = listOf(
                ApiMessage(role = "system", content = "You are helpful."),
                ApiMessage(role = "user", content = "Hi!")
            ),
            temperature = 0.7,
            maxTokens = 4096,
            stream = true
        )

        val fullJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val serialized = fullJson.encodeToString(ChatCompletionRequest.serializer(), request)

        assertTrue("Expected model in: $serialized", serialized.contains("llama-3.3-70b-versatile"))
        assertTrue("Expected stream in: $serialized", serialized.contains("stream"))
        assertTrue("Expected max_tokens in: $serialized", serialized.contains("max_tokens"))
    }

    @Test
    fun `parse chunk with empty choices array`() {
        val raw = """{ "id": "x", "choices": [] }"""

        val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), raw)

        assertEquals(0, chunk.choices.size)
    }

    @Test
    fun `build vision request JSON correctly`() {
        val messagesJson = JsonArray(listOf(
            JsonObject(mapOf(
                "role" to JsonPrimitive("user"),
                "content" to JsonArray(listOf(
                    JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive("What is this?")
                    )),
                    JsonObject(mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf(
                            "url" to JsonPrimitive("data:image/png;base64,abc123")
                        ))
                    ))
                ))
            ))
        ))

        val bodyJson = JsonObject(mapOf(
            "model" to JsonPrimitive("llama-3.2-11b-vision-preview"),
            "messages" to messagesJson,
            "max_tokens" to JsonPrimitive(4096),
            "stream" to JsonPrimitive(true)
        ))

        val serialized = bodyJson.toString()

        assertTrue(serialized.contains("llama-3.2-11b-vision-preview"))
        assertTrue(serialized.contains("image_url"))
        assertTrue(serialized.contains("data:image/png;base64,abc123"))
        assertTrue(serialized.contains("What is this?"))

        val parsed = json.parseToJsonElement(serialized).jsonObject
        assertEquals("llama-3.2-11b-vision-preview", parsed["model"]?.jsonPrimitive?.content)

        val messages = parsed["messages"]?.jsonArray
        assertNotNull(messages)
        assertEquals(1, messages!!.size)

        val content = messages[0].jsonObject["content"]?.jsonArray
        assertNotNull(content)
        assertEquals(2, content!!.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image_url", content[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse vision streaming response chunk`() {
        val raw = """
            {
                "id": "chatcmpl-vision-1",
                "choices": [{
                    "index": 0,
                    "delta": {"content": "I see a mobile app"},
                    "finish_reason": null
                }]
            }
        """.trimIndent()

        val parsed = json.parseToJsonElement(raw).jsonObject
        val choices = parsed["choices"]?.jsonArray
        val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.content

        assertEquals("I see a mobile app", content)
    }
}
