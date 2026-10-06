package com.friday.ai.core

import com.friday.ai.domain.model.AssistantMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTimeTest {

    private val builder = SystemPromptBuilder()

    @Test
    fun `the model is told the weekday, the zone and the offset`() {
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 21, 3), ZoneId.of("Asia/Tashkent"))
        val line = builder.currentDateTime(now)
        assertTrue(line, line.contains("2026-10-05 21:03"))
        assertTrue(line, line.contains("Monday"))
        assertTrue(line, line.contains("Asia/Tashkent"))
        assertTrue(line, line.contains("UTC+05:00"))
    }

    @Test
    fun `UTC is written as an offset, not as Z`() {
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 9, 0), ZoneId.of("UTC"))
        assertTrue(builder.currentDateTime(now).contains("UTC+00:00"))
    }

    @Test
    fun `the full prompt carries it`() {
        val prompt = builder.build(AssistantMode.DEFAULT)
        assertTrue(prompt.contains("Current local date/time:"))
        assertTrue(prompt.contains("time zone"))
    }
}
