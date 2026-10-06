package com.friday.ai.service

import com.friday.ai.core.Acknowledgements
import com.friday.ai.core.GroqModels
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.ChatCompletionRequest
import com.friday.ai.data.remote.groqJson
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.service.voice.VoiceErrors
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model retirement that silenced Friday, and everything built so it cannot again. */
class GroqFailuresTest {

    private val retired = GroqApiException(
        404,
        "API error (404): {\"error\":{\"message\":\"The model `llama-3.3-70b-versatile` does not exist or you " +
            "do not have access to it.\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"
    )

    @Test
    fun `a retired model is recognised from Groq's reply`() {
        assertTrue(retired.modelMissing)
        assertFalse(GroqApiException(404, "Not found").modelMissing)
        assertFalse(GroqApiException(401, "Invalid API key").modelMissing)
    }

    @Test
    fun `each failure says something the user can act on`() {
        // Before, every one of these was "Something went wrong".
        assertTrue(VoiceErrors.spoken(retired, true).contains("сменил модель"))
        assertTrue(VoiceErrors.spoken(GroqApiException(401, "x"), true).contains("Ключ Groq"))
        assertTrue(VoiceErrors.spoken(GroqApiException(429, "x"), true).contains("лимит"))
        assertTrue(VoiceErrors.spoken(IOException("timeout"), true).contains("интернет"))
        assertTrue(VoiceErrors.spoken(GroqApiException(500, "boom"), false).contains("boom"))
    }

    @Test
    fun `reasoning effort is sent only when set`() {
        // "reasoning_effort": null risks a rejected request from a model
        // without that setting; the field must simply be absent.
        val plain = groqJson.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(model = "qwen/qwen3.8-27b", messages = listOf(ApiMessage("user", "hi")))
        )
        val reasoning = groqJson.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = "openai/gpt-oss-20b", messages = listOf(ApiMessage("user", "hi")), reasoningEffort = "low"
            )
        )
        assertFalse(plain, plain.contains("reasoning_effort"))
        assertTrue(reasoning, reasoning.contains("\"reasoning_effort\":\"low\""))
    }

    @Test
    fun `refresh replaces a retired chosen model and remembers the list`() = runTest {
        val prefs = mutableMapOf("groq_api_key" to "k", ModelCatalog.PREF_CHOSEN to "llama-3.3-70b-versatile")
        val dao = mockk<UserPreferenceDao>()
        coEvery { dao.get(any()) } answers { prefs[firstArg()] }
        val saved = slot<UserPreferenceEntity>()
        coEvery { dao.set(capture(saved)) } answers { prefs[saved.captured.key] = saved.captured.value }
        val groq = mockk<GroqApiService>()
        every { groq.listModels("k") } returns setOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")
        val catalog = ModelCatalog(groq, dao, StandardTestDispatcher(testScheduler))

        assertTrue(catalog.refresh())
        assertEquals("openai/gpt-oss-120b", prefs[ModelCatalog.PREF_CHOSEN])
        assertEquals("openai/gpt-oss-20b", catalog.model(GroqModels.Role.FAST))
        assertEquals("qwen/qwen3.8-27b", catalog.model(GroqModels.Role.VISION))
    }

    @Test
    fun `no key or no network leaves things as they were`() = runTest {
        val dao = mockk<UserPreferenceDao>(relaxed = true)
        coEvery { dao.get(any()) } returns null
        val catalog = ModelCatalog(mockk(), dao, StandardTestDispatcher(testScheduler))
        assertFalse(catalog.refresh())
        assertEquals(GroqModels.fallback(GroqModels.Role.CHAT), catalog.model(GroqModels.Role.CHAT))
    }
}

class QuickWinsTest {

    @Test
    fun `the persona speaks of herself in the feminine and follows the user's language`() {
        val prompt = SystemPromptBuilder().build(AssistantMode.DEFAULT)
        assertTrue(prompt.contains("feminine"))
        assertTrue(prompt.contains("сэр"))
        assertTrue(prompt.contains("latest message"))
        assertTrue(prompt.contains("Never invent"))
    }

    @Test
    fun `the voice is female in both languages`() {
        // Every fixed phrase is feminine ("не расслышала"); a male voice clashed.
        assertEquals("ru-RU-SvetlanaNeural", EdgeTtsProtocol.VOICE_RU)
        assertEquals("en-IE-EmilyNeural", EdgeTtsProtocol.VOICE_EN)
    }

    @Test
    fun `the cache key changes with the voice and is a safe file name`() {
        val a = EdgeTtsProtocol.cacheKey("ru-RU-SvetlanaNeural", "Да, сэр?")
        val b = EdgeTtsProtocol.cacheKey("ru-RU-DmitryNeural", "Да, сэр?")
        assertNotEquals("a new voice must not replay the old one's audio", a, b)
        assertEquals(a, EdgeTtsProtocol.cacheKey("ru-RU-SvetlanaNeural", "Да, сэр?"))
        assertTrue(a, a.matches(Regex("[0-9a-f]{40}\\.mp3")))
    }

    @Test
    fun `every greeting is prefetched`() {
        val seen = (1..200).map { Acknowledgements().next(null) }.toSet() +
            (1..200).map { Acknowledgements().next(com.friday.ai.core.WakePhrases.Language.ENGLISH) }.toSet()
        assertTrue(
            "greetings not prefetched: ${seen - Acknowledgements.all.toSet()}",
            Acknowledgements.all.containsAll(seen)
        )
    }
}
