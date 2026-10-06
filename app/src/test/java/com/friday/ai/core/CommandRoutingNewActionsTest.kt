package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing for the actions added on top of the original command set. */
class CommandRoutingNewActionsTest {

    private val router = CommandRouter()

    // ---------- weather ----------

    @Test
    fun `weather without a place uses the default location`() {
        listOf("какая погода", "погода", "what's the weather", "weather").forEach {
            val r = router.route(it)
            assertTrue("'$it' -> $r", r is CommandResult.Weather)
            assertNull((r as CommandResult.Weather).place)
        }
    }

    @Test
    fun `weather with a place captures the place`() {
        assertEquals("Ташкенте", (router.route("какая погода в Ташкенте") as CommandResult.Weather).place)
        assertEquals("London", (router.route("what's the weather in London") as CommandResult.Weather).place)
    }

    @Test
    fun `question marks do not leak into the place`() {
        assertEquals("Москве", (router.route("какая погода в Москве?") as CommandResult.Weather).place)
    }

    // ---------- calendar ----------

    @Test
    fun `reminder with a time becomes a calendar event`() {
        val r = router.route("напомни мне завтра в 15:00 встреча с врачом")
        assertTrue("got $r", r is CommandResult.CreateEvent)
        r as CommandResult.CreateEvent
        assertTrue("title should keep the subject: '${r.title}'", r.title.contains("встреча"))
        assertTrue("title should drop the time words: '${r.title}'", !r.title.contains("15:00"))
    }

    @Test
    fun `english reminder becomes a calendar event`() {
        val r = router.route("remind me tomorrow at 3pm dentist appointment")
        assertTrue("got $r", r is CommandResult.CreateEvent)
        assertTrue((r as CommandResult.CreateEvent).title.contains("dentist"))
    }

    @Test
    fun `event phrasing works too`() {
        assertTrue(router.route("добавь встречу завтра в 10:00 обсудить проект") is CommandResult.CreateEvent)
        assertTrue(router.route("schedule a meeting tomorrow at 11:00 standup") is CommandResult.CreateEvent)
    }

    @Test
    fun `a reminder with no time is never turned into a calendar entry`() {
        // The original guarantee: no arbitrary hour gets invented. An errand
        // like this now becomes a *place* reminder instead, which is the point
        // of location reminders — but it must still never land in the diary.
        val r = router.route("напомни купить молоко")
        assertTrue("got $r", r !is CommandResult.CreateEvent)
        assertTrue("got $r", r is CommandResult.CreateErrand)
    }

    @Test
    fun `a timeless reminder with nowhere to go stays conversation`() {
        val r = router.route("напомни позвонить маме")
        assertTrue("got $r", r is CommandResult.ChatMessage)
    }

    // ---------- regressions ----------

    @Test
    fun `alarms still route to the clock, not the calendar`() {
        val r = router.route("поставь будильник на 7:30")
        assertTrue("got $r", r is CommandResult.SetAlarm)
    }

    @Test
    fun `calls still route to the dialer`() {
        assertEquals("папе", (router.route("позвони папе") as CommandResult.PhoneCall).target)
    }

    @Test
    fun `ordinary questions are still conversation`() {
        assertTrue(router.route("расскажи анекдот") is CommandResult.ChatMessage)
        assertTrue(router.route("как дела") is CommandResult.ChatMessage)
    }

    @Test
    fun `nearest-place search works in russian`() {
        // Regression: the pattern used \w+ after "ближайш", and \w is
        // ASCII-only in Java regex, so the Russian ending never matched and
        // this silently fell through to plain chat.
        val r = router.route("найди ближайший банк")
        assertTrue("got $r", r is CommandResult.FindNearby)
        assertEquals("банк", (r as CommandResult.FindNearby).query)

        assertTrue(router.route("найди ближайшую кофейню") is CommandResult.FindNearby)
        assertTrue(router.route("где ближайшая аптека") is CommandResult.FindNearby)
    }

    // ---------- rescheduling ----------

    @Test
    fun `moving an event is not mistaken for creating one`() {
        // The bug this guards: "перенеси встречу на 11" used to fall through
        // and add a second event instead of moving the existing one.
        val r = router.route("перенеси встречу на 11:00")
        assertTrue("got $r", r is CommandResult.RescheduleEvent)
    }

    @Test
    fun `reschedule captures the new time`() {
        val r = router.route("перенеси dentist на 11:00") as CommandResult.RescheduleEvent
        assertEquals("dentist", r.titleHint)
        assertTrue(r.whenText.contains("11:00"))
    }

    @Test
    fun `reschedule without a name leaves the target open`() {
        val r = router.route("перенеси на 11:00") as CommandResult.RescheduleEvent
        assertNull("no name was given", r.titleHint)
    }

    @Test
    fun `generic words are not treated as the event name`() {
        // "встречу" is the word for "meeting", not the title of one.
        val r = router.route("перенеси встречу на 11:00") as CommandResult.RescheduleEvent
        assertNull("'встречу' should not become the title hint", r.titleHint)
    }

    @Test
    fun `english reschedule works`() {
        val r = router.route("move dentist to 11:00")
        assertTrue("got $r", r is CommandResult.RescheduleEvent)
        assertEquals("dentist", (r as CommandResult.RescheduleEvent).titleHint)
    }

    @Test
    fun `reschedule without a readable time is not a calendar command`() {
        assertTrue(router.route("перенеси разговор на потом") is CommandResult.ChatMessage)
    }

    // ---------- reminder lead time ----------

    @Test
    fun `reminder lead time is read from the phrasing`() {
        assertEquals(60, DateTimeParser.parseReminderLeadMinutes("напомни за час до встречи"))
        assertEquals(120, DateTimeParser.parseReminderLeadMinutes("за 2 часа до"))
        assertEquals(30, DateTimeParser.parseReminderLeadMinutes("за 30 минут до"))
        assertEquals(90, DateTimeParser.parseReminderLeadMinutes("remind me 90 minutes before"))
    }

    @Test
    fun `no lead time phrasing leaves the default in place`() {
        assertNull(DateTimeParser.parseReminderLeadMinutes("завтра в 15:00 встреча"))
        assertNull(DateTimeParser.parseReminderLeadMinutes(""))
    }

    @Test
    fun `default reminder is useful rather than last-minute`() {
        // Ten minutes isn't enough warning to actually act on anything.
        assertTrue(DateTimeParser.DEFAULT_REMINDER_MINUTES >= 30)
    }

    @Test
    fun `event title drops the day word too`() {
        val r = router.route("напомни мне завтра в 15:00 встреча с врачом") as CommandResult.CreateEvent
        assertTrue("'завтра' should be stripped from the title: '${r.title}'",
            !r.title.contains("завтра"))
        assertEquals("встреча с врачом", r.title)
    }
}
