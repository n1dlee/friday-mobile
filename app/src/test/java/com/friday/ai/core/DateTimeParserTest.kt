package com.friday.ai.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimeParserTest {

    // Wednesday, 30 July 2026, 13:00 — fixed so tests never depend on the clock.
    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 30, 13, 0)

    private fun parse(text: String) = DateTimeParser.parse(text, now)

    @Test
    fun `tomorrow with an explicit time`() {
        val p = parse("завтра в 15:00 встреча с врачом")!!
        assertEquals(LocalDateTime.of(2026, 7, 31, 15, 0), p.instant)
        assertTrue(p.hadExplicitTime)
    }

    @Test
    fun `tomorrow without a time falls back to a sensible hour`() {
        val p = parse("напомни завтра позвонить папе")!!
        assertEquals(LocalDateTime.of(2026, 7, 31, DateTimeParser.DEFAULT_HOUR, 0), p.instant)
        assertTrue("no time was given", !p.hadExplicitTime)
    }

    @Test
    fun `today with a time later the same day`() {
        assertEquals(LocalDateTime.of(2026, 7, 30, 18, 30), parse("сегодня в 18:30")!!.instant)
    }

    @Test
    fun `day after tomorrow`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 1, DateTimeParser.DEFAULT_HOUR, 0),
            parse("послезавтра")!!.instant
        )
    }

    @Test
    fun `a bare time already past today rolls to tomorrow`() {
        // Said at 13:00; "в 9" almost certainly means tomorrow morning.
        assertEquals(LocalDateTime.of(2026, 7, 31, 9, 0), parse("в 9:00")!!.instant)
    }

    @Test
    fun `a bare time still ahead today stays today`() {
        assertEquals(LocalDateTime.of(2026, 7, 30, 17, 0), parse("в 17:00")!!.instant)
    }

    @Test
    fun `russian day parts shift the hour`() {
        assertEquals(LocalDateTime.of(2026, 7, 31, 15, 0), parse("завтра в 3 дня")!!.instant)
        assertEquals(LocalDateTime.of(2026, 7, 31, 8, 0), parse("завтра в 8 утра")!!.instant)
        assertEquals(LocalDateTime.of(2026, 7, 31, 19, 0), parse("завтра в 7 вечера")!!.instant)
    }

    @Test
    fun `english am pm`() {
        assertEquals(LocalDateTime.of(2026, 7, 31, 15, 0), parse("tomorrow at 3pm")!!.instant)
        assertEquals(LocalDateTime.of(2026, 7, 31, 9, 30), parse("tomorrow at 9:30 am")!!.instant)
    }

    @Test
    fun `relative offsets in russian`() {
        val inAnHour = parse("через час")?.instant ?: parse("через 1 час")!!.instant
        assertEquals(LocalDateTime.of(2026, 7, 30, 14, 0), inAnHour)
        assertEquals(LocalDateTime.of(2026, 7, 30, 13, 30), parse("через 30 минут")!!.instant)
        assertEquals(LocalDateTime.of(2026, 8, 2, 13, 0), parse("через 3 дня")!!.instant)
    }

    @Test
    fun `relative offsets in english`() {
        assertEquals(LocalDateTime.of(2026, 7, 30, 15, 0), parse("in 2 hours")!!.instant)
        assertEquals(LocalDateTime.of(2026, 7, 30, 13, 45), parse("in 45 minutes")!!.instant)
    }

    @Test
    fun `next weekday resolves forward`() {
        // Wednesday -> next Monday is 3 August.
        val p = parse("в понедельник в 10:00")!!
        assertEquals(LocalDateTime.of(2026, 8, 3, 10, 0), p.instant)
        assertEquals(DayOfWeek.MONDAY, p.instant.dayOfWeek)
    }

    @Test
    fun `english weekday resolves forward`() {
        assertEquals(DayOfWeek.FRIDAY, parse("on friday at 12:00")!!.instant.dayOfWeek)
    }

    @Test
    fun `text with no time information returns null`() {
        // Better to admit we don't know than to book an arbitrary slot.
        assertNull(parse("напомни купить молоко"))
        assertNull(parse("hello there"))
        assertNull(parse(""))
    }

    @Test
    fun `nonsense times are rejected`() {
        assertNull(parse("в 99:99"))
    }
}
