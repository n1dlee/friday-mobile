package com.friday.ai.core

import com.friday.ai.core.VoiceTurn.Next
import com.friday.ai.core.VoiceTurn.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTurnTest {

    private fun next(o: Outcome, followUp: Boolean = false, attempt: Int = 1) =
        VoiceTurn.next(o, followUp, attempt, maxAttempts = 2)

    @Test
    fun `heard speech is acted on, follow-up or not`() {
        assertEquals(Next.PROCESS, next(Outcome.Heard("какая погода")))
        assertEquals(Next.PROCESS, next(Outcome.Heard("а завтра?"), followUp = true))
    }

    @Test
    fun `blank transcription counts as nothing heard`() {
        assertEquals(Next.LISTEN_AGAIN, next(Outcome.Heard("   ")))
    }

    @Test
    fun `a stranger during a follow-up just ends the conversation`() {
        // Nobody addressed Friday in a follow-up. Answering another voice in
        // the room would be her butting in; saying "not you" would announce her.
        assertEquals(Next.END_QUIETLY, next(Outcome.Stranger, followUp = true))
        assertEquals(Next.END_QUIETLY, next(Outcome.Nothing, followUp = true))
    }

    @Test
    fun `right after waking, a stranger gets the owner one more try`() {
        // The owner is standing there; someone probably talked over them.
        assertEquals(Next.LISTEN_AGAIN, next(Outcome.Stranger, attempt = 1))
        assertEquals(Next.LISTEN_AGAIN, next(Outcome.Nothing, attempt = 1))
    }

    @Test
    fun `the last attempt says why it stopped`() {
        assertEquals(Next.SAY_NOT_RECOGNISED, next(Outcome.Stranger, attempt = 2))
        assertEquals(Next.SAY_NOT_HEARD, next(Outcome.Nothing, attempt = 2))
    }

    @Test
    fun `retrying never loops forever`() {
        listOf(Outcome.Nothing, Outcome.Stranger).forEach { o ->
            (2..5).forEach { attempt ->
                assertEquals("$o at $attempt", false, next(o, attempt = attempt) == Next.LISTEN_AGAIN)
            }
        }
    }
}
