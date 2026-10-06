package com.friday.ai.service

import com.friday.ai.data.local.dao.InteractionDao
import com.friday.ai.data.local.dao.MemoryDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.LazuriApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the conversation-session rules that make "what I said five minutes
 * ago" work: turns close together stay in one thread, and a long silence
 * starts a new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FridayMemorySessionTest {

    private companion object {
        const val PREF_ID = "voice_session_id"
        const val PREF_LAST = "voice_session_last_activity"
        const val ONE_HOUR = 60 * 60 * 1000L
    }

    private lateinit var memoryDao: MemoryDao
    private lateinit var interactionDao: InteractionDao
    private lateinit var groqApi: GroqApiService
    private lateinit var prefDao: UserPreferenceDao
    private lateinit var lazuriApi: LazuriApiService
    private lateinit var memory: FridayMemory

    /** In-memory stand-in for the preferences table. */
    private val stored = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        memoryDao = mockk(relaxed = true)
        interactionDao = mockk(relaxed = true)
        groqApi = mockk(relaxed = true)
        lazuriApi = mockk(relaxed = true)
        prefDao = mockk(relaxed = true)

        coEvery { prefDao.get(any()) } answers { stored[firstArg()] }
        val saved = slot<UserPreferenceEntity>()
        coEvery { prefDao.set(capture(saved)) } answers {
            stored[saved.captured.key] = saved.captured.value
        }

        memory = FridayMemory(memoryDao, interactionDao, groqApi, prefDao, lazuriApi, io.mockk.mockk(relaxed = true))
    }

    @Test
    fun `first ever turn creates a session`() = runTest {
        val id = memory.getOrCreateSessionId()

        assertTrue(id.isNotBlank())
        assertEquals(id, stored[PREF_ID])
        assertTrue("last activity should be recorded", stored.containsKey(PREF_LAST))
    }

    @Test
    fun `consecutive turns stay in the same session`() = runTest {
        val first = memory.getOrCreateSessionId()
        val second = memory.getOrCreateSessionId()

        assertEquals(first, second)
    }

    @Test
    fun `a turn shortly after the last one continues the conversation`() = runTest {
        val first = memory.getOrCreateSessionId()
        // Five minutes later — the case the user explicitly asked to work.
        stored[PREF_LAST] = (System.currentTimeMillis() - 5 * 60 * 1000L).toString()

        assertEquals(first, memory.getOrCreateSessionId())
    }

    @Test
    fun `a turn just inside the window still continues`() = runTest {
        val first = memory.getOrCreateSessionId()
        stored[PREF_LAST] = (System.currentTimeMillis() - (ONE_HOUR - 30_000L)).toString()

        assertEquals(first, memory.getOrCreateSessionId())
    }

    @Test
    fun `a turn after a long silence starts a new session`() = runTest {
        val first = memory.getOrCreateSessionId()
        stored[PREF_LAST] = (System.currentTimeMillis() - (ONE_HOUR + 60_000L)).toString()

        assertNotEquals(first, memory.getOrCreateSessionId())
    }

    @Test
    fun `new chat forces a fresh session even mid conversation`() = runTest {
        val first = memory.getOrCreateSessionId()
        val fresh = memory.startNewSession()

        assertNotEquals(first, fresh)
        assertEquals(fresh, stored[PREF_ID])
        // And the next turn continues the new one, not the old one.
        assertEquals(fresh, memory.getOrCreateSessionId())
    }

    @Test
    fun `resuming a past session makes it active for later turns`() = runTest {
        memory.getOrCreateSessionId()
        memory.resumeSession("older-session")

        assertEquals("older-session", stored[PREF_ID])
        assertEquals("older-session", memory.getOrCreateSessionId())
    }

    @Test
    fun `corrupt last-activity value does not crash and starts fresh`() = runTest {
        memory.getOrCreateSessionId()
        stored[PREF_LAST] = "not-a-number"

        val id = memory.getOrCreateSessionId()
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `interactions are recorded against the session`() = runTest {
        val session = memory.getOrCreateSessionId()
        memory.logInteraction("привет", "здравствуй", "voice_chat", session)

        coVerify {
            interactionDao.insert(match { it.sessionId == session && it.userInput == "привет" })
        }
    }

    @Test
    fun `nothing is pushed to Lazuri when it is not configured`() = runTest {
        memory.logInteraction("привет", "здравствуй", null, "s1")

        coVerify(exactly = 0) {
            lazuriApi.createMemory(any(), any(), any(), any(), any(), any(), any())
        }
    }
}
