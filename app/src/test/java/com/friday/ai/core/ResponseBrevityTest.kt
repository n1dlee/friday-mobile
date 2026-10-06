package com.friday.ai.core

import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.ChatCompletionRequest
import com.friday.ai.domain.model.AssistantMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for verbose replies. The text chat previously had no
 * brevity instruction at all — only the voice path did — and every request
 * allowed 4096 tokens, so the model was free to write essays.
 */
class ResponseBrevityTest {

    private val builder = SystemPromptBuilder()

    @Test
    fun `every system prompt carries a brevity rule`() {
        AssistantMode.entries.forEach { mode ->
            val prompt = builder.build(mode)
            assertTrue(
                "mode $mode is missing the brevity rule",
                prompt.contains(SystemPromptBuilder.BREVITY_RULE)
            )
        }
    }

    @Test
    fun `brevity rule survives a large memory context`() {
        val memory = buildString {
            appendLine("--- User Memory ---")
            repeat(40) { appendLine("- fact_$it: value $it") }
            appendLine("--- End of Memory ---")
        }
        val prompt = builder.build(AssistantMode.DEFAULT, memory)
        assertTrue(prompt.contains(SystemPromptBuilder.BREVITY_RULE))
        assertTrue(prompt.contains("fact_39"))
    }

    @Test
    fun `voice replies are capped far tighter than chat replies`() {
        assertTrue(
            "voice cap must be tighter than the chat cap",
            GroqApiService.VOICE_MAX_TOKENS < GroqApiService.DEFAULT_MAX_TOKENS
        )
        assertTrue(
            "a spoken reply of more than ~2 sentences is a bug",
            GroqApiService.VOICE_MAX_TOKENS <= 200
        )
    }

    @Test
    fun `chat token cap is not the old runaway 4096`() {
        assertTrue(GroqApiService.DEFAULT_MAX_TOKENS <= 1000)
    }

    @Test
    fun `max tokens is actually serialized into the request body`() {
        val json = Json { encodeDefaults = true }
        val body = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = "llama-3.3-70b-versatile",
                messages = listOf(ApiMessage("user", "hi")),
                maxTokens = GroqApiService.VOICE_MAX_TOKENS
            )
        )
        assertTrue(body.contains("\"max_tokens\":${GroqApiService.VOICE_MAX_TOKENS}"))
        assertTrue("streaming must stay on", body.contains("\"stream\":true"))
    }

    @Test
    fun `request defaults to the chat cap when unspecified`() {
        val request = ChatCompletionRequest(
            model = "m",
            messages = listOf(ApiMessage("user", "hi"))
        )
        assertEquals(GroqApiService.DEFAULT_MAX_TOKENS, request.maxTokens)
    }
}
