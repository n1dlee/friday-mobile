package com.friday.ai.service.voice

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTurnJobsTest {
    @Test
    fun `replacement waits for capture cleanup before starting another exchange`() = runTest {
        val events = mutableListOf<String>()
        val turns = VoiceTurnJobs(backgroundScope, { events += "interrupt" }, { error("Unexpected timeout") })
        var oldId = 0
        turns.replace { id ->
            oldId = id
            try {
                events += "capture"
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(100) }
                events += "released"
            }
        }
        runCurrent()
        turns.replace { events += "replacement" }
        runCurrent()
        assertFalse(turns.current(oldId))
        assertFalse("replacement" in events)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(events.indexOf("released") < events.indexOf("replacement"))
        turns.destroy()
    }

    @Test
    fun `close cancels generation and drops callbacks without cancelling controller`() = runTest {
        var cancelled = false
        var lateCallback = false
        var id = 0
        val turns = VoiceTurnJobs(backgroundScope, {}, { error("Unexpected timeout") })
        turns.replace {
            id = it
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent()
        turns.close {}
        runCurrent()
        turns.launch(id) { lateCallback = true }
        turns.replace { lateCallback = false }
        runCurrent()
        assertTrue(cancelled)
        assertFalse(lateCallback)
        turns.destroy()
    }

    @Test
    fun `stuck capture refuses replacement instead of opening two recorders`() = runTest {
        var blocked = 0
        var replacement = false
        val turns = VoiceTurnJobs(backgroundScope, {}, { blocked++ })
        turns.replace {
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(5_000) } }
        }
        runCurrent()
        turns.replace { replacement = true }
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, blocked)
        assertFalse(replacement)
        advanceTimeBy(3_000)
        runCurrent()
        turns.replace { replacement = true }
        runCurrent()
        assertTrue(replacement)
        turns.destroy()
        advanceUntilIdle()
    }
}
