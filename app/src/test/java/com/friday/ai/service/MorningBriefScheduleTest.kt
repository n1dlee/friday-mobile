package com.friday.ai.service

import com.friday.ai.core.BriefTiming
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the brief fires. The delay maths was right all along; what kept the
 * brief at 16:00 was the daily periodic job above it, which these tests now
 * cover too through the delivery window.
 */
class MorningBriefScheduleTest {

    private val tashkent = ZoneId.of("Asia/Tashkent")

    private fun at(hour: Int, minute: Int = 0, zone: ZoneId = tashkent) =
        ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, hour, minute), zone)

    private fun hours(d: Duration) = d.toMinutes() / 60.0

    // --- delay -------------------------------------------------------------

    @Test
    fun `before the hour it waits until later today`() {
        assertEquals(2.0, hours(BriefTiming.delayUntilNext(at(6), 8)), 0.01)
    }

    @Test
    fun `after the hour it waits until tomorrow`() {
        assertEquals(15.0, hours(BriefTiming.delayUntilNext(at(17), 8)), 0.01)
    }

    @Test
    fun `exactly on the hour rolls to tomorrow rather than firing twice`() {
        assertEquals(24.0, hours(BriefTiming.delayUntilNext(at(8), 8)), 0.01)
    }

    @Test
    fun `delay is always within one day`() {
        for (nowHour in 0..23) for (target in 0..23) {
            val d = BriefTiming.delayUntilNext(at(nowHour), target)
            assertTrue("now=$nowHour target=$target gave $d", !d.isNegative && !d.isZero && hours(d) <= 24.0)
        }
    }

    @Test
    fun `the target is local morning in whatever zone the phone is in`() {
        // 16:00 in Tashkent is 11:00 UTC. From there the next local 08:00 is
        // 16 h away; the next UTC 08:00 would be 21 h away.
        assertEquals(16.0, hours(BriefTiming.delayUntilNext(at(16), 8)), 0.01)
        assertEquals(21.0, hours(BriefTiming.delayUntilNext(at(11, zone = ZoneId.of("UTC")), 8)), 0.01)
    }

    @Test
    fun `a daylight saving night still lands on 08_00 local`() {
        // Berlin, 25 Oct 2026: clocks go back overnight, so the night is
        // 25 h long. A fixed 24 h period would arrive at 07:00.
        val berlin = ZoneId.of("Europe/Berlin")
        val evening = ZonedDateTime.of(LocalDateTime.of(2026, 10, 24, 20, 0), berlin)
        val delay = BriefTiming.delayUntilNext(evening, 8)
        assertEquals(LocalTime.of(8, 0), evening.plus(delay).withZoneSameInstant(berlin).toLocalTime())
        assertEquals(13.0, hours(delay), 0.01)
    }

    @Test
    fun `an out of range hour is clamped rather than crashing`() {
        assertTrue(BriefTiming.delayUntilNext(at(10), 99).toMillis() > 0)
        assertTrue(BriefTiming.delayUntilNext(at(10), -5).toMillis() > 0)
    }

    @Test
    fun `default hour is a morning hour`() {
        assertTrue(MorningBriefWorker.DEFAULT_HOUR in 5..11)
    }

    // --- delivery window ---------------------------------------------------

    @Test
    fun `a brief due at 8 is delivered around 8`() {
        assertTrue(BriefTiming.shouldDeliver(LocalTime.of(8, 0), 8))
        assertTrue(BriefTiming.shouldDeliver(LocalTime.of(7, 30), 8))
        assertTrue(BriefTiming.shouldDeliver(LocalTime.of(11, 59), 8))
    }

    @Test
    fun `a brief that wakes at 16_00 is not delivered`() {
        // The reported bug: no "good morning" in the afternoon. Tomorrow's
        // brief is booked instead.
        assertFalse(BriefTiming.shouldDeliver(LocalTime.of(16, 0), 8))
        assertFalse(BriefTiming.shouldDeliver(LocalTime.of(12, 0), 8))
        assertFalse(BriefTiming.shouldDeliver(LocalTime.of(3, 0), 8))
    }

    @Test
    fun `the window wraps around midnight`() {
        assertTrue(BriefTiming.shouldDeliver(LocalTime.of(1, 0), 23))
        assertTrue(BriefTiming.shouldDeliver(LocalTime.of(23, 30), 0))
        assertFalse(BriefTiming.shouldDeliver(LocalTime.of(12, 0), 23))
    }

    // --- greeting ----------------------------------------------------------

    @Test
    fun `the greeting follows the clock`() {
        assertEquals("Доброе утро.", BriefTiming.greeting(LocalTime.of(8, 0), true))
        assertEquals("Добрый день.", BriefTiming.greeting(LocalTime.of(16, 0), true))
        assertEquals("Добрый вечер.", BriefTiming.greeting(LocalTime.of(20, 0), true))
        assertEquals("Доброй ночи.", BriefTiming.greeting(LocalTime.of(2, 0), true))
        assertEquals("Good afternoon.", BriefTiming.greeting(LocalTime.of(16, 0), false))
    }

    @Test
    fun `boundaries fall on the hour`() {
        assertEquals("Доброе утро.", BriefTiming.greeting(LocalTime.of(5, 0), true))
        assertEquals("Доброе утро.", BriefTiming.greeting(LocalTime.of(11, 59), true))
        assertEquals("Добрый день.", BriefTiming.greeting(LocalTime.of(12, 0), true))
        assertEquals("Добрый вечер.", BriefTiming.greeting(LocalTime.of(18, 0), true))
    }
}
