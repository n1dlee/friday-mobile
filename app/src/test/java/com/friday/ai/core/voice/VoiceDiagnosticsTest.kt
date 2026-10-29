package com.friday.ai.core.voice

import com.friday.ai.core.VoiceTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceDiagnosticsTest {
    @Test
    fun `queued and ready audio are not proof of playback and next synthesis cannot erase playback`() {
        var now = 0L
        val diagnostics = VoiceDiagnostics { now }
        diagnostics.begin(1)
        diagnostics.stage(1, VoiceStage.THINKING)
        diagnostics.playback(1, PlaybackEvent(PlaybackPhase.QUEUED, VoiceEngine.EDGE))
        assertEquals(VoiceStage.THINKING, diagnostics.state.value.stage)
        now = 120
        diagnostics.playback(1, PlaybackEvent(PlaybackPhase.PLAYING, VoiceEngine.EDGE))
        assertEquals(120L, diagnostics.state.value.durationsMs[VoiceStage.THINKING])
        diagnostics.playback(1, PlaybackEvent(PlaybackPhase.READY, VoiceEngine.EDGE))
        assertEquals(PlaybackPhase.PLAYING, diagnostics.state.value.playback?.phase)
    }

    @Test
    fun `replacement clears stale playback and rejects old turn events`() {
        val diagnostics = VoiceDiagnostics { 0 }
        diagnostics.begin(1)
        diagnostics.playback(1, PlaybackEvent(PlaybackPhase.PLAYING, VoiceEngine.EDGE))
        diagnostics.cancel()
        diagnostics.begin(2)
        diagnostics.playback(1, PlaybackEvent(PlaybackPhase.FINISHED, VoiceEngine.EDGE))
        diagnostics.heard(1, VoiceTurn.Outcome.Stranger)
        assertNull(diagnostics.state.value.playback)
        assertEquals(0, diagnostics.state.value.counts.rejected)
        assertEquals(1, diagnostics.state.value.counts.cancelled)
        assertEquals(0, diagnostics.state.value.counts.failures)
    }

    @Test
    fun `metadata counts fallback and owner rejection without retaining private text`() {
        var now = 0L
        val diagnostics = VoiceDiagnostics { now }
        diagnostics.begin(1)
        diagnostics.owner(1, OwnerCheck.CHECKING)
        now = 42
        diagnostics.owner(1, OwnerCheck.ACCEPTED)
        diagnostics.heard(1, VoiceTurn.Outcome.Heard(
            "private message", VoiceTurn.Origin.LOCAL, VoiceTurn.Failure.NETWORK
        ))
        val snapshot = diagnostics.state.value
        assertEquals(42L, snapshot.ownerCheckMs)
        assertEquals(1, snapshot.counts.fallbacks)
        assertFalse(VoiceDiagnosticReport.format(snapshot).contains("private message"))
        diagnostics.reset()
        assertEquals(VoiceDiagnosticSnapshot(), diagnostics.state.value)
    }
}
