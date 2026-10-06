package com.friday.ai.core.messages

import android.content.Context
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.ModelCatalog
import com.friday.ai.service.WebResearch
import com.friday.ai.service.mail.MailAssistant
import com.friday.ai.service.messages.MessageAssistant
import com.friday.ai.service.messages.MessengerInbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun chat(title: String, at: Long, vararg texts: String, group: Boolean = false, canReply: Boolean = true) =
    Conversation(
        key = "wa|$title", packageName = "com.whatsapp", app = "WhatsApp", title = title,
        messages = texts.mapIndexed { i, t -> InboxMessage(if (group) "Аня" else title, t, at + i) },
        canReply = canReply, updatedAt = at, isGroup = group
    )

class ConversationsTest {

    private val mum = chat("Мамуля", 100, "Ты где?", "Позвони")
    private val ivan = chat("Иван Петров", 200, "Ок")

    @Test
    fun `a chat is found the way a contact is`() {
        assertEquals(mum, Conversations.find("маме", listOf(mum, ivan)))
        assertEquals(ivan, Conversations.find("Ивану", listOf(mum, ivan)))
        assertNull(Conversations.find("Зигфриду", listOf(mum, ivan)))
    }

    @Test
    fun `a pronoun or nobody means whoever wrote last`() {
        assertEquals(ivan, Conversations.find("ей", listOf(mum, ivan)))
        assertEquals(ivan, Conversations.find(null, listOf(mum, ivan)))
    }

    @Test
    fun `chats are retold newest first, with the app`() {
        val said = Conversations.retell(listOf(mum, ivan), russian = true)
        assertTrue(said, said.startsWith("Иван Петров в WhatsApp: «Ок»."))
        assertTrue(said, said.contains("Мамуля в WhatsApp: «Ты где?» «Позвони»."))
        assertEquals("Новых сообщений нет.", Conversations.retell(emptyList(), true))
    }

    @Test
    fun `in a group the sender is named`() {
        val g = chat("Семья", 1, "Ужин в 7", group = true)
        assertEquals("Аня в «Семья» в WhatsApp: «Ужин в 7»", Conversations.announcement(g, g.messages[0], true))
    }

    @Test
    fun `a call is announced with the app only when it is a messenger`() {
        assertEquals("Звонит мама", Conversations.incomingCall("мама", null, true))
        assertEquals("Мама звонит в WhatsApp", Conversations.incomingCall("Мама", "WhatsApp", true))
        assertEquals("Mom is calling", Conversations.incomingCall("Mom", null, false))
    }
}

class ChatRoutingTest {

    private val router = CommandRouter()

    @Test
    fun `asking to read messages`() {
        assertEquals(CommandResult.ReadMessages(null), router.route("прочитай сообщения"))
        assertEquals(CommandResult.ReadMessages(null), router.route("есть новые сообщения?"))
        assertEquals(CommandResult.ReadMessages("мама"), router.route("что пишет мама"))
        assertEquals(CommandResult.ReadMessages("мамы"), router.route("прочитай сообщения от мамы"))
        assertEquals(CommandResult.ReadMessages("mom"), router.route("what did mom write"))
    }

    @Test
    fun `replies go to the chat, named or not`() {
        assertEquals(
            CommandResult.ReplyMessage("маме", "буду через 10 минут"),
            router.route("ответь маме: буду через 10 минут")
        )
        assertEquals(CommandResult.ReplyMessage(null, "еду"), router.route("ответь, что еду"))
        assertEquals(CommandResult.ReplyMessage("ей", "еду"), router.route("ответь ей что еду"))
    }

    @Test
    fun `mail itself is still mail`() {
        assertTrue(router.route("проверь почту") is CommandResult.Mail)
        assertTrue(router.route("прочитай письмо от Ивана") is CommandResult.Mail)
    }

    @Test
    fun `googling is answered aloud`() {
        assertEquals(CommandResult.LookUp("курс доллара"), router.route("загугли курс доллара"))
    }
}

class MessageAssistantTest {

    private val inbox = mockk<MessengerInbox>(relaxed = true)
    private val mail = mockk<MailAssistant>()
    private var now = 0L
    private val assistant = MessageAssistant(mockk<Context>(), inbox, mail, clock = { now })
    private val mum = chat("Мамуля", 100, "Ты где?")

    @Test
    fun `a reply is read back and sent only on yes`() = runTest {
        every { inbox.chats(any()) } returns listOf(mum)
        every { inbox.reply(any(), mum, "еду") } returns true
        assertEquals("Ответить «Мамуля» в WhatsApp: «еду»?", assistant.reply("маме", "еду", russian = true))
        verify(exactly = 0) { inbox.reply(any(), any(), any()) }
        assertEquals("Отправила.", assistant.answerPending("да", russian = true))
        verify { inbox.reply(any(), mum, "еду") }
        // Answered: a second "да" is not a second message.
        assertNull(assistant.answerPending("да", true))
    }

    @Test
    fun `no means nothing goes`() = runTest {
        every { inbox.chats(any()) } returns listOf(mum)
        assistant.reply(null, "еду", true)
        assertEquals("Не отправляю.", assistant.answerPending("нет", true))
        verify(exactly = 0) { inbox.reply(any(), any(), any()) }
    }

    @Test
    fun `a stale question is not answered by a later yes`() = runTest {
        every { inbox.chats(any()) } returns listOf(mum)
        assistant.reply(null, "еду", true)
        now += 3 * 60 * 1000L
        assertNull(assistant.answerPending("да", true))
    }

    @Test
    fun `someone with no recent chat gets an e-mail reply`() = runTest {
        every { inbox.chats(any()) } returns emptyList()
        coEvery { mail.handle(any(), any()) } returns MailAssistant.Answer("Ответить Ивану письмом?")
        assistant.reply("Ивану", "буду в пять", true)
        coVerify { mail.handle(MailCommands.Request.Reply("Ивану", "буду в пять"), true) }
    }

    @Test
    fun `an app without a reply field is said so`() = runTest {
        every { inbox.chats(any()) } returns listOf(chat("Мамуля", 1, "?", canReply = false))
        assertTrue(assistant.reply("маме", "еду", true).contains("не даёт ответить"))
    }

    @Test
    fun `a closed notification cannot be replied from, and that is said`() = runTest {
        every { inbox.chats(any()) } returns listOf(mum)
        every { inbox.reply(any(), any(), any()) } returns false
        assistant.reply(null, "еду", true)
        assertTrue(assistant.answerPending("да", true)!!.contains("уже закрыто"))
    }
}

class WebResearchTest {

    private val groq = mockk<GroqApiService>()
    private val catalog = mockk<ModelCatalog>().also { coEvery { it.model(any()) } returns "openai/gpt-oss-20b" }
    private val prefs = mockk<UserPreferenceDao>().also { coEvery { it.get(any()) } returns "key" }
    private val today = ZonedDateTime.of(2026, 10, 6, 9, 0, 0, 0, ZoneId.of("America/New_York"))
    private val web = WebResearch(groq, catalog, prefs, now = { today })

    @Test
    fun `the search model is told today's date, or it answers from memory`() {
        assertTrue(WebResearch.instructions(today).startsWith("Today is Tuesday, 6 October 2026."))
    }

    @Test
    fun `citation markers and markdown are stripped`() {
        assertEquals(
            "Spain won the 2026 World Cup. Kommersant, 20 July 2026.",
            WebResearch.clean("**Spain** won the 2026 World Cup【1†L5-L13】【1†L28-L35】. Kommersant, 20 July 2026.")
        )
    }

    @Test
    fun `a garbled answer is tried once more`() = runTest {
        var calls = 0
        every { groq.browse(any(), any(), any(), any()) } answers {
            if (++calls == 1) throw GroqApiException(400, "API error (400): output_parse_failed") else "Spain."
        }
        assertEquals("Spain.", web.answer("кто чемпион", true))
    }

    @Test
    fun `a used-up minute is explained, not shown as an error code`() = runTest {
        every { groq.browse(any(), any(), any(), any()) } throws
            GroqApiException(429, "Rate limit", retryAfterMs = 30_000)
        assertTrue(web.answer("кто чемпион", true).contains("минутный лимит"))
    }
}

class NullableToolArgsTest {

    @Test
    fun `an optional field sent as null is the same as one left out`() {
        val args = com.friday.ai.data.remote.groqJson.parseToJsonElement("""{"from":null}""")
            as kotlinx.serialization.json.JsonObject
        val r = com.friday.ai.agent.AgentTools.interpret("read_messages", args, java.time.LocalDateTime.now())
        assertEquals(com.friday.ai.agent.AgentTools.Call.Command(CommandResult.ReadMessages(null)), r)
    }

    @Test
    fun `the schema says so, or Groq rejects the call`() {
        val read = com.friday.ai.agent.AgentTools.definitions.single { it.function.name == "read_messages" }
        assertTrue(read.function.parameters.toString().contains(""""type":["string","null"]"""))
    }
}
