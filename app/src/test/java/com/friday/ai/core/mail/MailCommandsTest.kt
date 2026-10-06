package com.friday.ai.core.mail

import com.friday.ai.core.mail.MailCommands.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailCommandsTest {

    private fun parse(s: String) = MailCommands.parse(s)

    // --- check ---------------------------------------------------------------

    @Test
    fun `asking about new mail`() {
        listOf(
            "проверь почту", "посмотри мою почту", "есть новые письма?",
            "непрочитанные письма", "что нового в почте", "check my email", "any new emails?"
        ).forEach { assertEquals(it, Request.CheckUnread, parse(it)) }
    }

    @Test
    fun `opening the mail app stays opening the mail app`() {
        // "Открой почту" has always launched the app; it must not become a
        // spoken inbox summary.
        assertNull(parse("открой почту"))
    }

    @Test
    fun `unread notifications are not mail`() {
        assertNull(parse("непрочитанные сообщения"))
        assertNull(parse("что я пропустил"))
    }

    // --- read ------------------------------------------------------------------

    @Test
    fun `reading the newest mail`() {
        assertEquals(Request.Read(null), parse("прочитай последнее письмо"))
        assertEquals(Request.Read(null), parse("Прочитай письмо."))
        assertEquals(Request.Read(null), parse("read my latest email"))
    }

    @Test
    fun `reading mail from someone`() {
        assertEquals(Request.Read("Ивана"), parse("прочитай письмо от Ивана"))
        assertEquals(Request.Read("Google"), parse("Прочти последнее письмо от Google."))
        assertEquals(Request.Read("Ivan"), parse("read the email from Ivan"))
    }

    // --- reply -----------------------------------------------------------------

    @Test
    fun `replying with the usual separators`() {
        assertEquals(Request.Reply("Ивану", "буду в пять"), parse("Ответь Ивану: буду в пять."))
        assertEquals(Request.Reply("Ивану", "буду в пять"), parse("ответь Ивану, буду в пять"))
        assertEquals(Request.Reply("Ивану", "буду в пять"), parse("ответь Ивану что буду в пять"))
        assertEquals(Request.Reply("Ивану", "согласен"), parse("ответь на письмо от Ивану: согласен"))
        assertEquals(Request.Reply("Ivan", "I'll be there"), parse("reply to Ivan saying I'll be there"))
    }

    @Test
    fun `replying without a separator takes the first word as the name`() {
        assertEquals(Request.Reply("Ивану", "буду в пять"), parse("ответь Ивану буду в пять"))
    }

    @Test
    fun `ordinary requests to answer are not mail`() {
        // These go to the model as conversation.
        listOf(
            "ответь мне честно", "ответь на вопрос: сколько будет два плюс два",
            "ответь коротко, что такое квазар", "ответь пожалуйста"
        ).forEach { assertNull(it, parse(it)) }
    }

    // --- compose ---------------------------------------------------------------

    @Test
    fun `writing a new mail`() {
        assertEquals(Request.Compose("маме", "буду поздно"), parse("напиши письмо маме что буду поздно"))
        assertEquals(Request.Compose("Ивану", "привет"), parse("отправь письмо Ивану с текстом привет"))
        assertEquals(Request.Compose("маме", "буду поздно"), parse("напиши маме на почту: буду поздно"))
        assertEquals(Request.Compose("Ivan", "running late"), parse("send an email to Ivan saying running late"))
        assertEquals(Request.Compose("маме", "буду поздно"), parse("напиши письмо маме буду поздно"))
    }

    @Test
    fun `a text message is not an email`() {
        assertNull(parse("напиши сообщение маме что буду поздно"))
    }

    @Test
    fun `a name that swallowed the message is rejected`() {
        assertNull(parse("ответь Ивану Петровичу Сидорову из бухгалтерии: да"))
    }

    @Test
    fun `nothing to say means no command`() {
        assertNull(parse(""))
        assertNull(parse("ответь Ивану:"))
        assertTrue(parse("какая погода") == null)
    }
}
