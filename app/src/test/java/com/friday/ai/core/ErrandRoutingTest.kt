package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reminder with no time is only useful if it becomes a *place* reminder —
 * otherwise "напомни купить молоко" just vanishes into chat, which is what it
 * used to do.
 */
class ErrandRoutingTest {

    private val router = CommandRouter()

    @Test
    fun `a purchase with no time becomes a location errand`() {
        val r = router.route("напомни купить молоко")
        assertTrue("got $r", r is CommandResult.CreateErrand)
        assertEquals("молоко", (r as CommandResult.CreateErrand).what)
    }

    @Test
    fun `the verb is trimmed so the errand reads as the thing`() {
        assertEquals("хлеб", (router.route("напомни купить хлеб") as CommandResult.CreateErrand).what)
        assertEquals(
            "посылку",
            (router.route("напомни забрать посылку") as CommandResult.CreateErrand).what
        )
    }

    @Test
    fun `english errands work too`() {
        val r = router.route("remind me to buy milk")
        assertTrue("got $r", r is CommandResult.CreateErrand)
    }

    @Test
    fun `a reminder with a time is still a calendar event`() {
        // Time wins: that's a diary entry, not something tied to a shop.
        val r = router.route("напомни завтра в 15:00 встреча с врачом")
        assertTrue("got $r", r is CommandResult.CreateEvent)
    }

    @Test
    fun `a non-errand reminder with no time stays conversation`() {
        // Nagging about this in a supermarket would be nonsense.
        assertTrue(router.route("напомни позвонить маме") is CommandResult.ChatMessage)
        assertTrue(router.route("напомни написать отчёт") is CommandResult.ChatMessage)
    }

    @Test
    fun `pharmacy and bank errands are recognised`() {
        assertTrue(router.route("напомни купить лекарства") is CommandResult.CreateErrand)
        assertTrue(router.route("напомни снять наличные") is CommandResult.CreateErrand)
    }

    @Test
    fun `existing commands are unaffected`() {
        assertTrue(router.route("позвони папе") is CommandResult.PhoneCall)
        assertTrue(router.route("какая погода") is CommandResult.Weather)
        assertTrue(router.route("фонарик") is CommandResult.ToggleFlashlight)
    }
}
