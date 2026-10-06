package com.friday.ai.core

import com.friday.ai.core.GroqModels.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqModelsTest {

    /** What this account offered when the llama models disappeared. */
    private val oct2026 = setOf(
        "allam-2-7b", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b",
        "whisper-large-v3", "whisper-large-v3-turbo"
    )

    @Test
    fun `a retired choice is replaced by the best model that still exists`() {
        // The actual failure: the saved choice was llama-3.3-70b-versatile.
        assertEquals("openai/gpt-oss-120b", GroqModels.pick(Role.CHAT, oct2026, chosen = "llama-3.3-70b-versatile"))
    }

    @Test
    fun `a choice that still exists is respected`() {
        assertEquals("qwen/qwen3.8-27b", GroqModels.pick(Role.CHAT, oct2026, chosen = "qwen/qwen3.8-27b"))
    }

    @Test
    fun `each job gets its own model`() {
        assertEquals("openai/gpt-oss-120b", GroqModels.pick(Role.CHAT, oct2026))
        assertEquals("openai/gpt-oss-20b", GroqModels.pick(Role.FAST, oct2026))
        assertEquals("qwen/qwen3.8-27b", GroqModels.pick(Role.VISION, oct2026))
    }

    @Test
    fun `a text-only model is never used for images, even if chosen`() {
        // gpt-oss rejects image content outright ("content must be a string").
        assertEquals("qwen/qwen3.8-27b", GroqModels.pick(Role.VISION, oct2026, chosen = "openai/gpt-oss-120b"))
    }

    @Test
    fun `when the next favourite goes too, the list moves on`() {
        val later = oct2026 - "openai/gpt-oss-120b"
        assertEquals("qwen/qwen3.8-27b", GroqModels.pick(Role.CHAT, later))
    }

    @Test
    fun `nothing suitable is reported, not guessed`() {
        assertNull(GroqModels.pick(Role.VISION, setOf("openai/gpt-oss-20b")))
    }

    @Test
    fun `before the list is known, the first preference is used`() {
        assertEquals("openai/gpt-oss-120b", GroqModels.pick(Role.CHAT, emptySet()))
        assertEquals("my/model", GroqModels.pick(Role.CHAT, emptySet(), chosen = "my/model"))
    }

    @Test
    fun `reasoning models get low effort and room to think`() {
        // With 150 tokens gpt-oss spent 139–148 of them reasoning and was cut off.
        assertEquals("low", GroqModels.reasoningEffort("openai/gpt-oss-120b"))
        assertNull(GroqModels.reasoningEffort("qwen/qwen3.8-27b"))
        assertTrue(GroqModels.tokenBudget("openai/gpt-oss-20b", 150) > 150 + 200)
        assertEquals(150, GroqModels.tokenBudget("qwen/qwen3.8-27b", 150))
    }

    @Test
    fun `settings only offer models that exist`() {
        assertEquals(
            listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b"),
            GroqModels.choicesForChat(oct2026)
        )
        assertEquals(listOf("qwen/qwen3.8-27b"), GroqModels.choicesForChat(setOf("qwen/qwen3.8-27b", "allam-2-7b")))
    }
}
