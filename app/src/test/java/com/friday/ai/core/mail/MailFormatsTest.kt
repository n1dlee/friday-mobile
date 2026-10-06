package com.friday.ai.core.mail

import com.friday.ai.core.mail.Confirmation.Answer
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationTest {

    @Test
    fun `yes`() {
        listOf("да", "Да.", "отправь", "давай", "да, отправляй", "yes", "send it", "ок")
            .forEach { assertEquals(it, Answer.YES, Confirmation.classify(it)) }
    }

    @Test
    fun `no wins over the verb it contains`() {
        listOf("нет", "не отправляй", "отмена", "Нет, не надо", "cancel", "don't send it", "стоп")
            .forEach { assertEquals(it, Answer.NO, Confirmation.classify(it)) }
    }

    @Test
    fun `a longer reply is a new request, not a go-ahead`() {
        // "Да, и добавь что опоздаю" is an edit. Sending at this point would
        // send the old text the user just asked to change.
        assertEquals(Answer.OTHER, Confirmation.classify("да, и добавь что я опоздаю"))
        assertEquals(Answer.OTHER, Confirmation.classify("какая погода"))
        assertEquals(Answer.OTHER, Confirmation.classify(""))
    }
}

class MimeMessageTest {

    private fun decodeRaw(raw: String) = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)

    private fun header(msg: String, name: String) =
        msg.lines().firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")

    private fun bodyOf(msg: String): String {
        val b64 = msg.substringAfter("\r\n\r\n").replace("\r\n", "")
        return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    }

    @Test
    fun `cyrillic subject and body survive the round trip`() {
        val raw = MimeMessage.raw(MimeMessage.Draft("ivan@example.com", "Встреча завтра", "Буду в пять."))
        val msg = decodeRaw(raw)
        assertEquals("ivan@example.com", header(msg, "To"))
        val subject = header(msg, "Subject")!!
        assertTrue(subject, subject.startsWith("=?UTF-8?B?"))
        val decoded = String(
            Base64.getDecoder().decode(subject.removePrefix("=?UTF-8?B?").removeSuffix("?=")), Charsets.UTF_8
        )
        assertEquals("Встреча завтра", decoded)
        assertEquals("Буду в пять.", bodyOf(msg))
        assertTrue(msg.contains("Content-Type: text/plain; charset=UTF-8"))
    }

    @Test
    fun `raw is url-safe base64 without padding`() {
        val raw = MimeMessage.raw(MimeMessage.Draft("a@b.c", "x", "длинное тело ".repeat(40)))
        assertFalse(raw.contains('+') || raw.contains('/') || raw.contains('='))
    }

    @Test
    fun `ascii subjects are left readable`() {
        assertEquals("Hello", MimeMessage.encodeHeader("Hello"))
    }

    @Test
    fun `a newline cannot inject a header`() {
        val msg = MimeMessage.build(MimeMessage.Draft("a@b.c", "hi\r\nBcc: evil@x.y", "x"))
        assertNull(header(msg, "Bcc"))
    }

    @Test
    fun `a reply threads`() {
        val msg = MimeMessage.build(MimeMessage.Draft("a@b.c", "Re: x", "ok", inReplyTo = "<m1@mail>"))
        assertEquals("<m1@mail>", header(msg, "In-Reply-To"))
        assertEquals("<m1@mail>", header(msg, "References"))
    }

    @Test
    fun `body lines are wrapped as base64 requires`() {
        val msg = MimeMessage.build(MimeMessage.Draft("a@b.c", "x", "я".repeat(500)))
        msg.substringAfter("\r\n\r\n").split("\r\n").forEach { assertTrue(it.length <= 76) }
    }

    @Test
    fun `reply subject gets one Re`() {
        assertEquals("Re: Встреча", MimeMessage.replySubject("Встреча"))
        assertEquals("RE: Встреча", MimeMessage.replySubject("RE: Встреча"))
    }

    @Test
    fun `a subject is taken from the opening of the body`() {
        assertEquals("Буду поздно", MimeMessage.subjectFrom("Буду поздно. Не ждите к ужину."))
        val long = MimeMessage.subjectFrom("слово ".repeat(30).trim())
        assertTrue(long, long.length <= 51 && long.endsWith("…") && !long.contains("сло…"))
    }

    @Test
    fun `dictated text is tidied into a sentence`() {
        assertEquals("Буду в пять.", MimeMessage.tidyBody("буду в пять"))
        assertEquals("Ты где?", MimeMessage.tidyBody("ты где?"))
        assertEquals("", MimeMessage.tidyBody("  "))
    }
}
