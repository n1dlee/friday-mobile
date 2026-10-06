package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCommandsTest {

    private val router = CommandRouter()

    @Test
    fun `grammar is cyrillic only`() {
        // A word outside the on-device model's vocabulary can break the
        // recogniser, and that model is Russian.
        OfflineCommands.GRAMMAR.forEach { phrase ->
            assertTrue(
                "'$phrase' is not usable in the offline grammar",
                phrase.all { it in 'а'..'я' || it == 'ё' || it == ' ' }
            )
        }
    }

    @Test
    fun `every grammar phrase routes to something actionable`() {
        // A phrase the decoder can return but the router ignores would leave
        // the user talking to a wall.
        val unroutable = OfflineCommands.GRAMMAR.filter { phrase ->
            val command = router.route(phrase)
            !OfflineCommands.isOfflineCapable(command) &&
                !ConversationControl.isFarewell(phrase)
        }
        assertTrue("these offline phrases do nothing: $unroutable", unroutable.isEmpty())
    }

    @Test
    fun `phone-only actions are offline capable`() {
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.ToggleFlashlight))
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.SetAlarm(7, 30, null)))
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.SetTimer(60, null)))
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.OpenApp("камера", null)))
        assertTrue(
            OfflineCommands.isOfflineCapable(
                CommandResult.DeviceControl(DeviceAction.VOLUME_UP)
            )
        )
    }

    @Test
    fun `anything needing the network is not offline capable`() {
        assertFalse(OfflineCommands.isOfflineCapable(CommandResult.ChatMessage("расскажи анекдот")))
        assertFalse(OfflineCommands.isOfflineCapable(CommandResult.Weather(null)))
        assertFalse(OfflineCommands.isOfflineCapable(CommandResult.WebSearch("котики")))
        assertFalse(OfflineCommands.isOfflineCapable(CommandResult.MorningBrief))
        assertFalse(OfflineCommands.isOfflineCapable(null))
    }

    @Test
    fun `refusal states the reason plainly`() {
        val ru = OfflineCommands.offlineRefusal(russian = true)
        assertTrue("got: $ru", ru.contains("сет"))
        val en = OfflineCommands.offlineRefusal(russian = false)
        assertTrue("got: $en", en.contains("connection"))
    }

    @Test
    fun `grammar covers the actions worth having without a network`() {
        val joined = OfflineCommands.GRAMMAR.joinToString(" ")
        listOf("фонарик", "громче", "тише", "камер", "не беспокоить").forEach {
            assertTrue("offline grammar is missing '$it'", joined.contains(it))
        }
    }

    @Test
    fun `alarms are left out because they need a time`() {
        // A closed grammar can't carry arbitrary numbers, and a bare
        // "будильник" would appear to work while doing nothing.
        assertFalse(OfflineCommands.GRAMMAR.any { it.trim() == "будильник" })
        assertFalse(OfflineCommands.GRAMMAR.any { it.trim() == "таймер" })
    }

    @Test
    fun `a bare torch noun still works`() {
        assertTrue(router.route("фонарь") is CommandResult.ToggleFlashlight)
        assertTrue(router.route("фонарик") is CommandResult.ToggleFlashlight)
    }
}
