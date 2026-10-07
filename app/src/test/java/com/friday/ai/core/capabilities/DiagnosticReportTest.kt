package com.friday.ai.core.capabilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {

    @Test
    fun `keys, numbers, mail and quoted words never leave the phone`() {
        val line = "10-07 12:34:56.789 I/FridayAgent( 4321): send_message({\"contact\":\"мама\",\"text\":\"еду\"}) " +
            "-> Пишу маме: «буду в семь» on +1 (415) 555-0132, key gsk_abcDEF123456, mail me@example.com"
        val r = DiagnosticReport.redact(line)
        listOf("мама", "еду", "буду в семь", "415", "gsk_", "example.com").forEach {
            assertFalse("\"$it\" leaked: $r", r.contains(it))
        }
        assertTrue("timestamp and tag are kept: $r", r.startsWith("10-07 12:34:56.789 I/FridayAgent( 4321)"))
        assertTrue(r.contains("[номер]") && r.contains("[ключ]") && r.contains("[email]"))
    }

    @Test
    fun `sealed values are hidden too`() {
        assertEquals("groq_api_key=[зашифровано]", DiagnosticReport.redact("groq_api_key=enc:v1:QUJDRA=="))
    }

    @Test
    fun `the report lists every check`() {
        val rows = listOf(
            Diagnostics.Row("mic", Diagnostics.Group.ESSENTIALS, "Микрофон", Diagnostics.Status.OK, "Разрешён"),
            Diagnostics.Row("dnd", Diagnostics.Group.PHONE, "Не беспокоить", Diagnostics.Status.PROBLEM, "Нет")
        )
        val report = DiagnosticReport.build("0.13.0", "samsung SM-S938B", rows, listOf("line"))
        assertTrue(report.startsWith("Friday 0.13.0 · samsung SM-S938B"))
        assertTrue(report.contains("[PROBLEM] Телефон и люди · Не беспокоить: Нет"))
    }
}
