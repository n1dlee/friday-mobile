package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.MediaAction
import com.friday.ai.domain.model.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandOffTest {

    @Test
    fun `an effect seen after the first send needs nothing more`() = runTest {
        var sends = 0
        val r = HandOff.run(send = { sends++ }, happened = { sends >= 1 }, waitMs = 1_000)
        assertEquals(HandOff.Result.DONE, r)
        assertEquals(1, sends)
    }

    @Test
    fun `a request swallowed by a cold start is sent once more`() = runTest {
        // What "say it twice" used to be: the app obeys only once it is already up.
        var sends = 0
        val r = HandOff.run(send = { sends++ }, happened = { sends >= 2 }, waitMs = 1_000)
        assertEquals(HandOff.Result.DONE_ON_RETRY, r)
        assertEquals(2, sends)
    }

    @Test
    fun `never more than one retry, and nothing claimed when nothing happened`() = runTest {
        var sends = 0
        val r = HandOff.run(send = { sends++ }, happened = { false }, waitMs = 1_000)
        assertEquals(HandOff.Result.NOT_SEEN, r)
        assertEquals(2, sends)
    }

    @Test
    fun `actions that must not happen twice are never retried`() = runTest {
        // A second ACTION_CALL would be a second call.
        var sends = 0
        assertEquals(HandOff.Result.NOT_SEEN, HandOff.run({ sends++ }, { false }, waitMs = 1_000, retry = false))
        assertEquals(1, sends)
    }

    @Test
    fun `waiting stops as soon as the effect shows`() = runTest {
        var checks = 0
        assertEquals(true, HandOff.poll(10_000) { ++checks >= 3 })
        assertEquals(3, checks)
    }
}

class PlayerResolutionTest {

    private val spotify = DeviceContext.App("com.spotify.music", "Spotify")
    private val vk = DeviceContext.App("com.uma.musicvk", "VK Музыка")
    private val tidal = DeviceContext.App("com.aspiro.tidal", "TIDAL")
    private val installed = listOf(spotify, vk, tidal)

    @Test
    fun `any installed player is found by its label, not only well-known ones`() {
        assertEquals(vk, MediaSearch.Chooser.named("включи музыку в vk", installed))
        assertEquals(vk, MediaSearch.Chooser.named("включи музыку в вк", installed))
        assertEquals(tidal, MediaSearch.Chooser.named("включи музыку в tidal", installed))
        assertEquals(spotify, MediaSearch.Chooser.named("включи музыку в спотифае", installed))
    }

    @Test
    fun `a place is not a player`() {
        assertNull(MediaSearch.Chooser.named("включи музыку в машине", installed))
        assertNull(MediaSearch.Chooser.named("включи музыку", installed))
    }

    @Test
    fun `naming a player the patterns don't know still reaches it`() {
        val r = CommandRouter().route("включи музыку в VK") as CommandResult.MediaControl
        assertEquals(MediaAction.PLAY, r.action)
        assertEquals(vk, MediaSearch.Chooser.named(r.appHint!!, installed))
        // No player mentioned: nothing to look up.
        assertNull((CommandRouter().route("останови музыку") as CommandResult.MediaControl).appHint)
    }

    @Test
    fun `with no name the usual player wins, then the best installed`() {
        assertEquals(vk, MediaSearch.Chooser.choose(MediaKind.MUSIC, installed, null, usual = vk.packageName))
        assertEquals(spotify, MediaSearch.Chooser.choose(MediaKind.MUSIC, installed, null, usual = null))
    }

    @Test
    fun `a search that played says what plays`() {
        assertEquals(
            "Играет «Believer» в Spotify",
            PlayerStart.reply(PlayerStart.Outcome.PLAYING, "Spotify", russian = true, query = "Believer")
        )
    }
}
