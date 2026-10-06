package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.MediaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRoutingTest {

    private val router = CommandRouter()

    private fun media(phrase: String): CommandResult.MediaControl {
        val r = router.route(phrase)
        assertTrue("'$phrase' -> $r", r is CommandResult.MediaControl)
        return r as CommandResult.MediaControl
    }

    @Test
    fun `starting and stopping music`() {
        assertEquals(MediaAction.PLAY, media("включи музыку").action)
        assertEquals(MediaAction.PLAY, media("поставь музыку").action)
        assertEquals(MediaAction.STOP, media("выключи музыку").action)
        assertEquals(MediaAction.STOP, media("останови музыку").action)
        assertEquals(MediaAction.PAUSE, media("поставь музыку на паузу").action)
    }

    @Test
    fun `skipping tracks`() {
        assertEquals(MediaAction.NEXT, media("следующий трек").action)
        assertEquals(MediaAction.NEXT, media("переключи песню").action)
        assertEquals(MediaAction.PREVIOUS, media("предыдущий трек").action)
    }

    @Test
    fun `a named player is enough on its own`() {
        // "включи спотифай" never says the word music.
        val r = media("включи спотифай")
        assertEquals(MediaAction.PLAY, r.action)
        assertNotNull("the player must be carried through", r.appHint)
        assertEquals(
            "com.spotify.music",
            MusicApps.match(r.appHint!!)?.packageName
        )
    }

    @Test
    fun `samsung music is recognised`() {
        assertEquals(
            "com.sec.android.app.music",
            MusicApps.match(media("включи samsung music").appHint!!)?.packageName
        )
        assertEquals(
            "com.sec.android.app.music",
            MusicApps.match("самсунг мьюзик")?.packageName
        )
    }

    @Test
    fun `a longer player name beats the shorter one inside it`() {
        assertEquals("ru.yandex.music", MusicApps.match("включи яндекс музыку")?.packageName)
        assertEquals(
            "com.google.android.apps.youtube.music",
            MusicApps.match("включи ютуб мьюзик")?.packageName
        )
    }

    @Test
    fun `no player named means whatever is already loaded`() {
        assertNull(media("включи музыку").appHint)
    }

    // ---------- things that must not become media commands ----------

    @Test
    fun `a bare verb is not a media command`() {
        // "включи" alone must not reach for the media session.
        listOf("включи фонарик", "включи звук", "включи вайфай").forEach {
            val r = router.route(it)
            assertTrue("'$it' -> $r", r !is CommandResult.MediaControl)
        }
    }

    @Test
    fun `ending the conversation is not a media stop`() {
        assertTrue(router.route("хватит") !is CommandResult.MediaControl)
        assertTrue(router.route("стоп") !is CommandResult.MediaControl)
    }

    @Test
    fun `an unrelated question is untouched`() {
        assertTrue(router.route("какая погода") !is CommandResult.MediaControl)
        assertTrue(router.route("позвони папе") !is CommandResult.MediaControl)
    }

    @Test
    fun `starting playback is distinguished from stopping it`() {
        // Launching a closed app makes sense for "включи Spotify" and is
        // nonsense for "останови Spotify".
        assertTrue(MediaAction.PLAY.startsPlayback)
        assertTrue(MediaAction.TOGGLE.startsPlayback)
        listOf(MediaAction.PAUSE, MediaAction.STOP, MediaAction.NEXT, MediaAction.PREVIOUS)
            .forEach { assertTrue("$it", !it.startsPlayback) }
    }

    @Test
    fun `player names do not fire on unrelated words`() {
        assertNull(MusicApps.match("посмотри расписание"))
        assertNull(MusicApps.match(""))
    }
}
