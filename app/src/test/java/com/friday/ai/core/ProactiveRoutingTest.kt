package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveRoutingTest {

    private val router = CommandRouter()

    @Test
    fun `what did i miss is recognised in both languages`() {
        listOf("что я пропустил", "что нового", "what did I miss", "what's new").forEach {
            assertTrue("'$it' -> ${router.route(it)}", router.route(it) is CommandResult.WhatDidIMiss)
        }
    }

    @Test
    fun `briefing is recognised`() {
        listOf("сводка", "доброе утро", "good morning", "что сегодня").forEach {
            assertTrue("'$it' -> ${router.route(it)}", router.route(it) is CommandResult.MorningBrief)
        }
    }

    @Test
    fun `notes are captured with their text`() {
        val r = router.route("запиши идея для проекта")
        assertTrue("got $r", r is CommandResult.CreateNote)
        assertEquals("идея для проекта", (r as CommandResult.CreateNote).text)

        assertTrue(router.route("note that the key is under the mat") is CommandResult.CreateNote)
    }

    @Test
    fun `volume up and down`() {
        assertEquals(
            DeviceAction.VOLUME_UP,
            (router.route("сделай громче") as CommandResult.DeviceControl).action
        )
        assertEquals(
            DeviceAction.VOLUME_DOWN,
            (router.route("тише") as CommandResult.DeviceControl).action
        )
    }

    @Test
    fun `explicit volume level is captured`() {
        val r = router.route("громкость на 30") as CommandResult.DeviceControl
        assertEquals(DeviceAction.VOLUME_SET, r.action)
        assertEquals(30, r.level)
    }

    @Test
    fun `volume level is clamped to a sane range`() {
        val r = router.route("volume 300") as CommandResult.DeviceControl
        assertTrue("got ${r.level}", (r.level ?: 0) <= 100)
    }

    @Test
    fun `mute and unmute`() {
        assertEquals(
            DeviceAction.MUTE,
            (router.route("выключи звук") as CommandResult.DeviceControl).action
        )
        assertEquals(
            DeviceAction.UNMUTE,
            (router.route("включи звук") as CommandResult.DeviceControl).action
        )
    }

    @Test
    fun `do not disturb on and off`() {
        assertEquals(
            DeviceAction.DND_ON,
            (router.route("включи не беспокоить") as CommandResult.DeviceControl).action
        )
        assertEquals(
            DeviceAction.DND_OFF,
            (router.route("выключи не беспокоить") as CommandResult.DeviceControl).action
        )
    }

    @Test
    fun `wifi and bluetooth say what is wanted, the controller decides how`() {
        // Android forbids apps from switching these; the intent still routes
        // so Friday can check the state, say so and open the setting.
        assertEquals(
            DeviceAction.WIFI_ON,
            (router.route("включи wifi") as CommandResult.DeviceControl).action
        )
        assertEquals(
            DeviceAction.BLUETOOTH_ON,
            (router.route("включи блютуз") as CommandResult.DeviceControl).action
        )
    }

    @Test
    fun `weather day offset is read from the question`() {
        assertEquals(0, (router.route("какая погода") as CommandResult.Weather).dayOffset)
        assertEquals(1, (router.route("какая погода завтра") as CommandResult.Weather).dayOffset)
        assertEquals(2, (router.route("какая погода послезавтра") as CommandResult.Weather).dayOffset)
        assertEquals(1, (router.route("what's the weather tomorrow") as CommandResult.Weather).dayOffset)
    }

    @Test
    fun `ordinary conversation is untouched by the new patterns`() {
        listOf("расскажи анекдот", "как дела", "что такое квантовая физика").forEach {
            assertTrue("'$it' -> ${router.route(it)}", router.route(it) is CommandResult.ChatMessage)
        }
    }
}
