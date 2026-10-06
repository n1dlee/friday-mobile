package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTextTest {

    @Test
    fun `case, yo and punctuation are folded`() {
        assertEquals("режим полета", SpokenText.normalise("Режим Полёта!"))
    }

    @Test
    fun `hyphenated and spaced spellings meet`() {
        // The recogniser writes it both ways; the two copies of this function
        // that existed before disagreed on exactly this.
        assertEquals(SpokenText.normalise("вай фай"), SpokenText.normalise("вай-фай"))
        assertEquals("wi fi", SpokenText.normalise("Wi-Fi"))
    }

    @Test
    fun `digits are kept`() {
        assertEquals("канал 2", SpokenText.normalise("канал #2"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals("", SpokenText.normalise("  ,.  "))
    }

    @Test
    fun `both matchers still find hyphenated input`() {
        assertEquals(SettingsScreens.Screen.WIFI, SettingsScreens.match("открой вай-фай"))
        assertEquals(SettingsScreens.Screen.WIFI, SettingsScreens.match("открой wi-fi"))
        assertEquals("ru.yandex.music", MusicApps.match("включи яндекс-музыку")?.packageName)
    }
}
