package com.friday.ai.core.mail

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailMessagesTest {

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun message(payload: String) = """
        {"id":"m1","threadId":"t1","snippet":"Don&#39;t forget &amp; bring it",
         "payload":$payload}
    """.trimIndent()

    private val headers = """
        "headers":[
          {"name":"From","value":"\"Иван Петров\" <ivan@example.com>"},
          {"name":"Subject","value":"Встреча завтра"},
          {"name":"Message-ID","value":"<abc@mail.example.com>"}
        ]
    """.trimIndent()

    @Test
    fun `a listing with no mail is empty, not an error`() {
        val l = GmailMessages.parseListing("""{"resultSizeEstimate":0}""")
        assertTrue(l.ids.isEmpty())
        assertEquals(0, l.estimate)
    }

    @Test
    fun `a listing keeps order and the estimate`() {
        val l = GmailMessages.parseListing(
            """{"messages":[{"id":"a","threadId":"x"},{"id":"b","threadId":"y"}],"resultSizeEstimate":14}"""
        )
        assertEquals(listOf("a", "b"), l.ids)
        assertEquals(14, l.estimate)
    }

    @Test
    fun `metadata-only mail has headers and no body`() {
        val m = GmailMessages.parseMessage(message("{$headers}"))
        assertEquals("Иван Петров", m.fromName)
        assertEquals("ivan@example.com", m.fromAddress)
        assertEquals("Встреча завтра", m.subject)
        assertEquals("<abc@mail.example.com>", m.messageId)
        assertEquals("t1", m.threadId)
        assertNull(m.body)
        assertEquals("Don't forget & bring it", m.snippet)
    }

    @Test
    fun `plain text is preferred over html in a multipart mail`() {
        val m = GmailMessages.parseMessage(message("""
            {$headers, "mimeType":"multipart/alternative", "parts":[
              {"mimeType":"text/html","body":{"data":"${b64("<p>HTML version</p>")}"}},
              {"mimeType":"text/plain","body":{"data":"${b64("Привет! Буду в пять.")}"}}
            ]}
        """.trimIndent()))
        assertEquals("Привет! Буду в пять.", m.body)
    }

    @Test
    fun `html-only mail is read without its markup`() {
        val html = "<html><style>p{color:red}</style><body>" +
            "<p>Первая строка</p><p>Вторая &amp; последняя</p></body></html>"
        val m = GmailMessages.parseMessage(message("""
            {$headers, "mimeType":"multipart/mixed", "parts":[
              {"mimeType":"multipart/alternative","parts":[
                {"mimeType":"text/html","body":{"data":"${b64(html)}"}}
              ]},
              {"mimeType":"application/pdf","filename":"a.pdf","body":{"attachmentId":"x"}}
            ]}
        """.trimIndent()))
        val body = m.body!!
        assertTrue(body, body.contains("Первая строка") && body.contains("Вторая & последняя"))
        assertFalse(body, body.contains("<") || body.contains("color"))
    }

    @Test
    fun `a single-part mail carries its text in the payload body`() {
        val m = GmailMessages.parseMessage(message(
            """{$headers, "mimeType":"text/plain", "body":{"data":"${b64("Коротко.")}"}}"""
        ))
        assertEquals("Коротко.", m.body)
    }

    @Test
    fun `quoted history is cut off`() {
        val text = "Согласен, давай в пять.\n\n" +
            "On Mon, 5 Oct 2026 at 10:00, Me <me@x.com> wrote:\n> Встретимся?\n> Когда удобно?"
        assertEquals("Согласен, давай в пять.", MailText.stripQuoted(text))
        val ru = "Ок.\n\n5 окт. 2026 г., в 10:00, Иван <i@x.com> пишет:\n> привет"
        assertEquals("Ок.", MailText.stripQuoted(ru))
    }

    @Test
    fun `addresses with and without a name`() {
        assertEquals("Google" to "no-reply@google.com", GmailMessages.parseAddress("Google <no-reply@google.com>"))
        assertEquals("ivan" to "ivan@example.com", GmailMessages.parseAddress("ivan@example.com"))
        assertEquals("ivan" to "ivan@example.com", GmailMessages.parseAddress("<ivan@example.com>"))
    }
}

class MailSpeechTest {

    private fun mail(from: String, subject: String) =
        GmailMessages.Mail("i", "t", from, "x@y.z", subject, "", null, null, null)

    @Test
    fun `no mail is said plainly`() {
        assertEquals("Новых писем нет.", MailSpeech.unread(0, emptyList(), true))
    }

    @Test
    fun `russian counts agree`() {
        assertTrue(MailSpeech.unread(1, listOf(mail("Иван", "А")), true).startsWith("1 непрочитанное письмо."))
        assertTrue(MailSpeech.unread(3, List(3) { mail("Иван", "А") }, true).startsWith("3 непрочитанных письма."))
        assertTrue(MailSpeech.unread(7, List(3) { mail("Иван", "А") }, true).startsWith("7 непрочитанных писем."))
    }

    @Test
    fun `only a few senders are named and the rest counted`() {
        val s = MailSpeech.unread(5, List(5) { mail("Отправитель$it", "Тема$it") }, true)
        assertTrue(s, s.contains("Отправитель0 — «Тема0»."))
        assertFalse(s, s.contains("Отправитель3"))
        assertTrue(s, s.endsWith("И ещё 2."))
    }

    @Test
    fun `a read-out opens with who and what`() {
        assertEquals("Иван Петров пишет: «Встреча».", MailSpeech.header(mail("Иван Петров", "Встреча"), true))
        assertEquals("Иван Петров пишет.", MailSpeech.header(mail("Иван Петров", ""), true))
    }
}
