package com.friday.ai.core

import com.friday.ai.core.PlayerStart.Outcome
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.MediaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartTest {

    @Test
    fun `naming a closed player asks it to play, not just to open`() {
        // The phrase from the report: it must reach the playback path with the player named,
        // where a closed player is now started *and* told to play.
        val r = CommandRouter().route("Включи музыку в Spotify")
        assertEquals(MediaAction.PLAY, (r as CommandResult.MediaControl).action)
        assertTrue(MusicApps.match(r.appHint!!)?.label == "Spotify")
    }

    @Test
    fun `only music actually playing is reported as playing`() {
        assertEquals("Играет в Spotify", PlayerStart.reply(Outcome.PLAYING, "Spotify", russian = true))
        listOf(Outcome.OPENED_NOT_PLAYING, Outcome.OPENED_UNVERIFIED, Outcome.NOT_INSTALLED).forEach {
            assertFalse(it.name, PlayerStart.reply(it, "Spotify", russian = true).startsWith("Играет"))
        }
    }

    @Test
    fun `a player that stayed paused is told plainly, with what to do`() {
        assertTrue(PlayerStart.reply(Outcome.OPENED_NOT_PLAYING, "Spotify", russian = true).contains("нажмите"))
        assertEquals("Playing on Spotify", PlayerStart.reply(Outcome.PLAYING, "Spotify", russian = false))
    }
}
