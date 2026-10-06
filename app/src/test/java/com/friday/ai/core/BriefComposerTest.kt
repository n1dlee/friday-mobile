package com.friday.ai.core

import com.friday.ai.core.BriefComposer.AgendaItem
import com.friday.ai.core.BriefComposer.NotificationSummary
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of a spoken summary is that it stays short. These tests
 * guard against the obvious failure: reading twenty notifications out loud.
 */
class BriefComposerTest {

    private fun n(app: String, title: String = "", text: String = "") =
        NotificationSummary(app, title, text)

    @Test
    fun `nothing waiting says so plainly`() {
        assertEquals("Ничего нового.", BriefComposer.missedSummary(emptyList(), russian = true))
        assertEquals("Nothing new.", BriefComposer.missedSummary(emptyList(), russian = false))
    }

    @Test
    fun `a single message is read out`() {
        val s = BriefComposer.missedSummary(
            listOf(n("Telegram", "Ivan", "договорились на завтра")), russian = true
        )
        assertTrue("got: $s", s.contains("Telegram"))
        assertTrue("the one message should be included: $s", s.contains("договорились"))
    }

    @Test
    fun `many messages are grouped by app rather than listed`() {
        val many = List(8) { n("Telegram", "msg $it", "body $it") } +
            List(3) { n("Gmail", "mail $it") }
        val s = BriefComposer.missedSummary(many, russian = true)

        assertTrue("total should be stated: $s", s.contains("11"))
        assertTrue("apps should be named: $s", s.contains("Telegram"))
        // The individual bodies must not be read out.
        assertTrue("must not read every message: $s", !s.contains("body 5"))
    }

    @Test
    fun `only a few apps are named and the rest are counted`() {
        val across = listOf(
            n("Telegram"), n("Gmail"), n("Slack"), n("Bank"), n("Instagram")
        )
        val s = BriefComposer.missedSummary(across, russian = false)
        assertTrue("should mention there are more: $s", s.contains("more"))
    }

    @Test
    fun `russian plurals are correct`() {
        assertTrue(BriefComposer.missedSummary(List(1) { n("A") }, true).contains("уведомление"))
        assertTrue(BriefComposer.missedSummary(List(3) { n("A") }, true).contains("уведомления"))
        assertTrue(BriefComposer.missedSummary(List(7) { n("A") }, true).contains("уведомлений"))
        // 11-14 are the case naive pluralisation gets wrong.
        assertTrue(BriefComposer.missedSummary(List(11) { n("A") }, true).contains("уведомлений"))
    }

    @Test
    fun `morning brief leads with the greeting and includes each part`() {
        val s = BriefComposer.morningBrief(
            weather = "В Ташкенте сейчас 24°, ясно.",
            agenda = listOf(AgendaItem("встреча с врачом", "15:00")),
            missedCount = 3,
            russian = true,
            localTime = LocalTime.of(8, 0)
        )
        assertTrue(s.startsWith("Доброе утро."))
        assertTrue("got: $s", s.contains("24°"))
        assertTrue("got: $s", s.contains("встреча с врачом"))
        assertTrue("got: $s", s.contains("15:00"))
        assertTrue("got: $s", s.contains("3"))
    }

    @Test
    fun `an empty day is stated rather than skipped`() {
        val s = BriefComposer.morningBrief(null, emptyList(), 0, russian = true, localTime = LocalTime.of(8, 0))
        assertTrue("got: $s", s.contains("пусто"))
    }

    @Test
    fun `missing weather simply drops out`() {
        val s = BriefComposer.morningBrief(
            null, listOf(AgendaItem("standup", "9:30")), 0, false, localTime = LocalTime.of(8, 0)
        )
        assertTrue(s.startsWith("Good morning."))
        assertTrue("got: $s", s.contains("standup"))
    }

    @Test
    fun `a packed day is truncated rather than recited`() {
        val agenda = List(9) { AgendaItem("event $it", "1$it:00") }
        val s = BriefComposer.morningBrief(null, agenda, 0, russian = false, localTime = LocalTime.of(8, 0))
        assertTrue("should say how many more: $s", s.contains("more"))
        assertTrue("must not list all nine: $s", !s.contains("event 8"))
    }

    @Test
    fun `zero unread is not mentioned at all`() {
        val s = BriefComposer.morningBrief(null, emptyList(), 0, russian = false, localTime = LocalTime.of(8, 0))
        assertTrue("got: $s", !s.contains("unread"))
    }

    @Test
    fun `time labels are zero padded on minutes only`() {
        assertEquals("9:05", BriefComposer.timeLabel(9, 5))
        assertEquals("15:00", BriefComposer.timeLabel(15, 0))
    }

    @Test
    fun `asked for in the afternoon, the brief does not say good morning`() {
        val s = BriefComposer.morningBrief(
            null, emptyList(), 0, russian = true, localTime = LocalTime.of(16, 0)
        )
        assertTrue("got: $s", s.startsWith("Добрый день."))
        assertFalse("got: $s", s.contains("утро"))
    }
}
