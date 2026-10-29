package com.friday.ai.service

import com.friday.ai.core.VoiceTurn
import com.friday.ai.data.remote.GroqApiException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrRecoveryTest {
    @Test
    fun `transport and service failures reuse locally decoded words from the same capture`() = runTest {
        val failures = listOf(IOException(), SocketTimeoutException(), GroqApiException(503, "unavailable"))
        failures.forEach { failure ->
            var requests = 0
            val result = AsrRecovery.transcribe("включи фонарик", null, true, { true }) {
                requests++
                throw failure
            }
            assertEquals(1, requests)
            assertTrue(result is VoiceTurn.Outcome.Heard)
            result as VoiceTurn.Outcome.Heard
            assertEquals(VoiceTurn.Origin.LOCAL, result.origin)
            assertTrue(result.degradedBy != null)
        }
    }

    @Test
    fun `owner rejection overrides cloud success and every local recovery`() = runTest {
        assertEquals(VoiceTurn.Outcome.Stranger,
            AsrRecovery.transcribe("включи фонарик", null, true, { false }) { throw IOException() })
        assertEquals(VoiceTurn.Outcome.Stranger,
            AsrRecovery.transcribe("включи фонарик", null, false, { false }) { error("No cloud request expected") })
        assertEquals(VoiceTurn.Outcome.Stranger,
            AsrRecovery.transcribe(null, null, true, { false }) { "включи фонарик" })
    }

    @Test
    fun `authentication and configuration errors remain visible even with local words`() = runTest {
        listOf(401, 403, 400, 404).forEach { code ->
            val result = AsrRecovery.transcribe("фонарик", null, true, { true }) {
                throw GroqApiException(code, "refused")
            }
            assertTrue(result is VoiceTurn.Outcome.Unavailable)
        }
    }

    @Test
    fun `no key allows a local command but no decoded words remains an explicit failure`() = runTest {
        val local = AsrRecovery.transcribe("фонарик", null, false, null) { error("Must not upload") }
        assertEquals(VoiceTurn.Outcome.Heard("фонарик", VoiceTurn.Origin.LOCAL, VoiceTurn.Failure.NO_KEY), local)
        assertEquals(VoiceTurn.Outcome.Unavailable(VoiceTurn.Failure.NO_KEY),
            AsrRecovery.transcribe(null, null, false, null) { error("Must not upload") })
    }

    @Test
    fun `empty cloud result retains the existing short-word fallback without degraded execution`() = runTest {
        assertEquals(VoiceTurn.Outcome.Heard("да", VoiceTurn.Origin.LOCAL),
            AsrRecovery.transcribe("да", null, true, null) { "" })
        assertEquals(VoiceTurn.Outcome.Nothing, AsrRecovery.transcribe("", null, true, null) { "" })
    }

    @Test
    fun `cancelled upload never returns fallback words`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            AsrRecovery.transcribe("фонарик", null, true, { true }) {
                entered.complete(Unit)
                awaitCancellation()
            }
            returned = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(returned)
    }

    @Test
    fun `failed upload waits for owner verdict before returning local words`() = runTest {
        val checking = CompletableDeferred<Unit>()
        val verdict = CompletableDeferred<Boolean>()
        val result = async {
            AsrRecovery.transcribe("фонарик", null, true, {
                checking.complete(Unit)
                verdict.await()
            }) { throw IOException() }
        }
        checking.await()
        assertFalse(result.isCompleted)
        verdict.complete(false)
        assertEquals(VoiceTurn.Outcome.Stranger, result.await())
    }

    @Test
    fun `cancellation during owner verification discards local fallback`() = runTest {
        val checking = CompletableDeferred<Unit>()
        var returned = false
        val result = launch {
            AsrRecovery.transcribe("фонарик", null, true, {
                checking.complete(Unit)
                awaitCancellation()
            }) { throw IOException() }
            returned = true
        }
        checking.await()
        result.cancelAndJoin()
        assertFalse(returned)
    }
}
