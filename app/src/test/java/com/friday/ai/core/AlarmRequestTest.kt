package com.friday.ai.core

import com.friday.ai.core.AlarmRequest.Check
import com.friday.ai.domain.model.CommandResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRequestTest {

    /** Monday evening: "tomorrow at 8" and "the next 08:00" are the same morning. */
    private val evening = LocalDateTime.of(2026, 10, 5, 22, 10)

    /** Monday 07:00: "tomorrow at 8" is NOT the next 08:00, which is in an hour. */
    private val earlyMorning = LocalDateTime.of(2026, 10, 5, 7, 0)

    private fun parse(text: String, now: LocalDateTime = evening) = AlarmRequest.parse(text, now)

    @Test
    fun `the phrase that went to chat instead is an alarm`() {
        // The reported failure: no minutes and "в" instead of "на" fell
        // through to the model, which said "поставила" and set nothing.
        val a = parse("Поставь будильник в 8 утра на завтра")!!
        assertEquals(LocalTime.of(8, 0), a.time)
        assertNull("tomorrow 08:00 is the next 08:00 when asked at night", a.date)
    }

    @Test
    fun `every usual way of saying it`() {
        mapOf(
            "поставь будильник на 7:30" to LocalTime.of(7, 30),
            "поставь будильник на 8 утра" to LocalTime.of(8, 0),
            "будильник на 6" to LocalTime.of(6, 0),
            "разбуди меня в 7" to LocalTime.of(7, 0),
            "поставь будильник в восемь утра" to LocalTime.of(8, 0),
            "заведи будильник на 9 вечера" to LocalTime.of(21, 0),
            "set alarm at 8:00" to LocalTime.of(8, 0),
            "wake me at 6:45" to LocalTime.of(6, 45)
        ).forEach { (text, time) -> assertEquals(text, time, parse(text)?.time) }
    }

    @Test
    fun `mentioning an alarm is not asking for one`() {
        assertNull(parse("почему будильник в 7 не прозвенел?"))
        assertNull(parse("как поставить будильник на айфоне"))
        assertNull(parse("какая погода"))
    }

    @Test
    fun `no time means no alarm rather than an invented one`() {
        assertNull(parse("разбуди меня завтра"))
        assertNull(parse("поставь будильник"))
    }

    @Test
    fun `a day the clock would not ring on is flagged`() {
        // At 07:00 the Clock's "08:00" is today, not tomorrow.
        val a = parse("поставь будильник в 8 утра на завтра", earlyMorning)!!
        assertEquals(LocalDate.of(2026, 10, 6), a.date)
        assertTrue(AlarmRequest.wrongDay(a, earlyMorning, russian = true).contains("не тот день"))
    }

    @Test
    fun `a time already gone today is said so`() {
        val a = parse("поставь будильник сегодня в 7", LocalDateTime.of(2026, 10, 5, 9, 0))!!
        assertEquals(LocalDate.of(2026, 10, 5), a.date)
        assertTrue(AlarmRequest.wrongDay(a, LocalDateTime.of(2026, 10, 5, 9, 0), true).contains("уже прошло"))
    }

    @Test
    fun `the next ring is today if still ahead, otherwise tomorrow`() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 8, 0), AlarmRequest.nextOccurrence(LocalTime.of(8, 0), earlyMorning))
        assertEquals(LocalDateTime.of(2026, 10, 6, 8, 0), AlarmRequest.nextOccurrence(LocalTime.of(8, 0), evening))
    }

    // --- verification ---------------------------------------------------------

    private val expected = 1_000_000_000L

    @Test
    fun `our alarm being next proves it exists`() {
        assertEquals(Check.CONFIRMED, AlarmRequest.check(expected, expected))
        assertEquals(Check.CONFIRMED, AlarmRequest.check(expected, expected + 30_000))
    }

    @Test
    fun `no alarm at all, or only later ones, means it was not created`() {
        assertEquals(Check.MISSING, AlarmRequest.check(expected, null))
        assertEquals(Check.MISSING, AlarmRequest.check(expected, expected + 3_600_000))
    }

    @Test
    fun `an earlier alarm hides ours, which is not proof of failure`() {
        assertEquals(Check.UNVERIFIABLE, AlarmRequest.check(expected, expected - 3_600_000))
    }

    @Test
    fun `only a confirmed alarm is reported as set`() {
        assertTrue(AlarmRequest.reply(LocalTime.of(8, 0), Check.CONFIRMED, true).contains("поставлен"))
        listOf(Check.UNVERIFIABLE, Check.MISSING).forEach {
            val r = AlarmRequest.reply(LocalTime.of(8, 0), it, true)
            assertTrue(r, !r.contains("поставлен"))
            assertTrue(r, r.contains("08:00"))
        }
    }

    // --- routing ------------------------------------------------------------------

    @Test
    fun `the router sends these to the clock, not to the chat`() {
        val router = CommandRouter(now = { evening })
        val r = router.route("Поставь будильник в 8 утра на завтра")
        assertTrue("got $r", r is CommandResult.SetAlarm)
        r as CommandResult.SetAlarm
        assertEquals(8, r.hour)
        assertEquals(0, r.minute)
        assertNull(r.date)
        assertNotNull(router.route("разбуди меня в 7") as? CommandResult.SetAlarm)
    }

    @Test
    fun `the model is told it cannot act and must not claim to`() {
        val prompt = SystemPromptBuilder().build(com.friday.ai.domain.model.AssistantMode.DEFAULT)
        assertTrue(prompt.contains("Never say an action is done"))
        assertTrue(prompt.contains("NOT carried out"))
    }
}
