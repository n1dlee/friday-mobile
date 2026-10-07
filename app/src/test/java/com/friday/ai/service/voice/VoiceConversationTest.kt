package com.friday.ai.service.voice

import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.OfflineCommands
import com.friday.ai.core.VoiceTurn
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.EdgeTtsSpeaker
import com.friday.ai.service.FridayOverlayManager
import com.friday.ai.service.OfflineCommandRecognizer
import com.friday.ai.service.WhisperTranscriber
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The spoken conversation, run without a phone. Before the service was split
 * none of this could be tested at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationTest {

    private lateinit var overlay: FridayOverlayManager
    private lateinit var speaker: EdgeTtsSpeaker
    private lateinit var transcriber: WhisperTranscriber
    private lateinit var offline: OfflineCommandRecognizer
    private lateinit var wake: WakeListener
    private lateinit var gate: SpeakerGate
    private lateinit var commands: CommandExecutor
    private lateinit var answers: SpokenAnswer
    private lateinit var session: VoiceSession

    /** Everything Friday was asked to say, with the callback for when she finishes. */
    private val said = mutableListOf<Pair<String, (() -> Unit)?>>()

    @Before
    fun setUp() {
        overlay = mockk(relaxed = true)
        speaker = mockk(relaxed = true)
        transcriber = mockk(relaxed = true)
        offline = mockk(relaxed = true)
        wake = mockk(relaxed = true)
        gate = mockk(relaxed = true)
        commands = mockk(relaxed = true)
        answers = mockk(relaxed = true)
        session = mockk(relaxed = true)

        every { offline.isOffline() } returns false
        every { gate.commandCheck() } returns null
        coEvery { session.apiKey() } returns "key"
        coEvery { session.russian() } returns true
        coEvery { commands.answerPending(any(), any()) } returns null
        every { speaker.speak(any(), any()) } answers {
            said += firstArg<String>() to secondArg<(() -> Unit)?>()
        }
    }

    /**
     * On the test scheduler but not in `backgroundScope`: `advanceUntilIdle`
     * stops once only background work is left, so the conversation would
     * never run at all.
     */
    private fun TestScope.conversation(inCall: () -> Boolean = { false }) = VoiceConversation(
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        VoiceIO(overlay, speaker, transcriber, offline, wake, mockk(relaxed = true), inCall = inCall),
        gate, commands, answers, session
    )

    /** Lets Friday finish the nth thing she was asked to say. */
    private fun TestScope.finishSpeaking(index: Int) {
        said[index].second!!.invoke()
        advanceUntilIdle()
    }

    private fun hears(vararg outcomes: VoiceTurn.Outcome) {
        coEvery { transcriber.recordAndTranscribe(any(), any()) } returnsMany outcomes.toList()
    }

    @Test
    fun `waking greets first and only then opens the microphone`() = runTest {
        // One recording that ends the turn, so the count below is exact.
        hears(VoiceTurn.Outcome.Heard("пока"))
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        assertEquals(1, said.size)
        coVerify(exactly = 0) { transcriber.recordAndTranscribe(any(), any()) }

        finishSpeaking(0)
        coVerify(exactly = 1) { transcriber.recordAndTranscribe(any(), any()) }
    }

    @Test
    fun `once a call starts Friday neither talks over it nor opens the microphone`() = runTest {
        var callActive = false
        hears(VoiceTurn.Outcome.Heard("позвони маме"), VoiceTurn.Outcome.Nothing)
        every { commands.route("позвони маме") } returns CommandResult.PhoneCall("маме")
        coEvery { commands.execute(CommandResult.PhoneCall("маме"), true) } answers {
            callActive = true
            CommandExecutor.Outcome.Reply("Звоню «Мама» в WhatsApp")
        }
        val c = conversation(inCall = { callActive })

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        assertEquals("only the greeting is spoken", 1, said.size)
        verify { overlay.showResult("Звоню «Мама» в WhatsApp", any()) }
        coVerify(exactly = 1) { transcriber.recordAndTranscribe(any(), any()) }
    }

    @Test
    fun `a command is carried out, spoken, recorded, and the conversation stays open`() = runTest {
        hears(VoiceTurn.Outcome.Heard("включи фонарик"), VoiceTurn.Outcome.Nothing)
        every { commands.route("включи фонарик") } returns CommandResult.ToggleFlashlight
        coEvery { commands.execute(CommandResult.ToggleFlashlight, true) } returns
            CommandExecutor.Outcome.Reply("Фонарик включён")
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        assertEquals("Фонарик включён", said[1].first)
        coVerify { session.log("включи фонарик", "Фонарик включён", "ToggleFlashlight") }
        coVerify { session.mirror("включи фонарик", "Фонарик включён") }
        // The wake word stays live while she talks, so it can interrupt.
        verify { wake.listen() }

        finishSpeaking(1)
        // Follow-up turn, then silence: the conversation closes quietly.
        coVerify(exactly = 2) { transcriber.recordAndTranscribe(any(), any()) }
        verify { overlay.dismiss() }
    }

    @Test
    fun `interrupting the greeting does not open a second recording`() = runTest {
        // The bug this split uncovered: stopping speech still fires its
        // "finished" callback, and that callback used to start listening
        // alongside the turn the interruption had already started.
        val held = CompletableDeferred<VoiceTurn.Outcome>()
        coEvery { transcriber.recordAndTranscribe(any(), any()) } coAnswers { held.await() }
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        assertTrue(c.speaking)

        c.onWake("пятница") // barge-in over the greeting
        advanceUntilIdle()
        verify { speaker.stop() }
        coVerify(exactly = 1) { transcriber.recordAndTranscribe(any(), any()) }

        finishSpeaking(0) // the stopped greeting reports itself finished
        coVerify(exactly = 1) { transcriber.recordAndTranscribe(any(), any()) }

        held.complete(VoiceTurn.Outcome.Nothing)
        advanceUntilIdle()
    }

    @Test
    fun `a recording from an interrupted turn is never carried out`() = runTest {
        val first = CompletableDeferred<VoiceTurn.Outcome>()
        coEvery { transcriber.recordAndTranscribe(any(), any()) } coAnswers { first.await() }
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0) // turn 1 is now recording

        c.onWake("пятница") // turn 2 begins while turn 1 still records
        advanceUntilIdle()

        first.complete(VoiceTurn.Outcome.Heard("позвони маме"))
        advanceUntilIdle()
        coVerify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `a goodbye ends the conversation without running anything`() = runTest {
        hears(VoiceTurn.Outcome.Heard("пока"))
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        verify { speaker.stop() }
        verify { overlay.dismiss() }
        coVerify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `an answer to a pending send is handled before anything else`() = runTest {
        // "нет" is also a goodbye word; here it must cancel the draft instead.
        hears(VoiceTurn.Outcome.Heard("нет"))
        coEvery { commands.answerPending("нет", true) } returns "Отменила."
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        assertEquals("Отменила.", said[1].first)
        verify(exactly = 0) { commands.route(any()) }
        finishSpeaking(1)
        // No follow-up after a confirmation: shown briefly, then gone.
        verify { overlay.showResult("Отменила.", any()) }
        coVerify(exactly = 1) { transcriber.recordAndTranscribe(any(), any()) }
    }

    @Test
    fun `plain conversation goes to the model and ends when Friday says goodbye`() = runTest {
        hears(VoiceTurn.Outcome.Heard("расскажи анекдот"))
        every { commands.route(any()) } returns CommandResult.ChatMessage("расскажи анекдот")
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Conversation("расскажи анекдот")
        coEvery { answers.answer("расскажи анекдот") } returns SpokenAnswer.Result.Finished("Вот анекдот. Пока!", false)
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        verify { overlay.showResult("Вот анекдот. Пока!", any()) }
        coVerify(exactly = 1) { transcriber.recordAndTranscribe(any(), any()) }
    }

    @Test
    fun `offline, a request that needs the network is refused plainly`() = runTest {
        every { offline.isOffline() } returns true
        every { wake.modelPath } returns "/model"
        coEvery { offline.listenForCommand("/model") } returns "какая погода"
        every { commands.route("какая погода") } returns CommandResult.Weather(null)
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        assertEquals(OfflineCommands.offlineRefusal(true), said[1].first)
        coVerify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `no API key is said on screen instead of failing silently`() = runTest {
        coEvery { session.apiKey() } returns ""
        val c = conversation()

        c.onWake("пятница")
        advanceUntilIdle()
        finishSpeaking(0)

        verify { overlay.showResult("API key not set", any()) }
        coVerify(exactly = 0) { transcriber.recordAndTranscribe(any(), any()) }
    }
}
