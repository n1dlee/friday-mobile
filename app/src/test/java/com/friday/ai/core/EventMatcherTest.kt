package com.friday.ai.core

import com.friday.ai.core.CalendarWriter.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventMatcherTest {

    private val hour = 60 * 60 * 1000L
    private val base = 1_785_492_000_000L // tomorrow 15:00 in the dev timezone

    private val upcoming = listOf(
        CalendarEvent(1, "dentist", base, base + hour),
        CalendarEvent(2, "встреча с врачом", base + 24 * hour, base + 25 * hour),
        CalendarEvent(3, "Standup", base + 48 * hour, base + 48 * hour + 1800_000L)
    )

    @Test
    fun `no name picks the soonest event`() {
        // "перенеси на 11" — the one coming up is what people mean.
        assertEquals(1L, EventMatcher.findBest(null, upcoming)?.id)
        assertEquals(1L, EventMatcher.findBest("  ", upcoming)?.id)
    }

    @Test
    fun `exact title wins`() {
        assertEquals(3L, EventMatcher.findBest("Standup", upcoming)?.id)
        assertEquals(1L, EventMatcher.findBest("dentist", upcoming)?.id)
    }

    @Test
    fun `partial title matches`() {
        assertEquals(2L, EventMatcher.findBest("встреча", upcoming)?.id)
        assertEquals(2L, EventMatcher.findBest("врачом", upcoming)?.id)
    }

    @Test
    fun `title match survives a script mismatch`() {
        // The event was typed in Latin, the user speaks Russian, so the two
        // only meet after transliteration.
        assertEquals(1L, EventMatcher.findBest("дентист", upcoming)?.id)
    }

    @Test
    fun `a loose phonetic spelling is not forced to match`() {
        // "стендап" transliterates to "stendap", which genuinely isn't
        // "standup" — the vowels differ. Guessing here would risk moving the
        // wrong event, so no match is the right answer.
        assertNull(EventMatcher.findBest("стендап", upcoming))
    }

    @Test
    fun `case is ignored`() {
        assertEquals(3L, EventMatcher.findBest("STANDUP", upcoming)?.id)
        assertEquals(1L, EventMatcher.findBest("Dentist", upcoming)?.id)
    }

    @Test
    fun `unknown name matches nothing rather than moving the wrong event`() {
        assertNull(EventMatcher.findBest("конференция в Берлине", upcoming))
    }

    @Test
    fun `empty calendar returns nothing`() {
        assertNull(EventMatcher.findBest("dentist", emptyList()))
        assertNull(EventMatcher.findBest(null, emptyList()))
    }

    @Test
    fun `ties go to whichever happens first`() {
        val duplicates = listOf(
            CalendarEvent(10, "Standup", base + 48 * hour, base + 49 * hour),
            CalendarEvent(11, "Standup", base + 2 * hour, base + 3 * hour)
        )
        assertEquals(11L, EventMatcher.findBest("standup", duplicates)?.id)
    }

    @Test
    fun `duration is derived from the event`() {
        assertEquals(hour, upcoming[0].durationMillis)
        assertEquals(1800_000L, upcoming[2].durationMillis)
    }
}
