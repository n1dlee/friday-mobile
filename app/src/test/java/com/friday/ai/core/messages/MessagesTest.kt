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
        assertEquals("Семья в WhatsApp: «Аня: Ужин в 7».", Conversations.retell(listOf(g), true))
    }

    @Test
    fun `who wrote is listed newest first, and the owner is asked whose first`() {
        val firdavs = chat("Фирдавс", 300, "Привет")
        assertEquals(
            "Сообщения от: Фирдавс, Иван Петров и Мамуля. Чьё прочитать первым?",
            Unread.whoWrote(listOf(mum, firdavs, ivan), true)
        )
        assertEquals("Ещё не прочитаны: Мамуля.", Unread.stillUnread(listOf(mum), true))
    }

    @Test
    fun `a name said alone picks the chat`() {
        val chats = listOf(mum, ivan, chat("Фирдавс", 300, "Привет"))
        assertEquals("Фирдавс", Unread.picked("Фирдавс", chats)?.title)
        assertEquals("Фирдавс", Unread.picked("давай сначала от Фирдавса", chats)?.title)
        assertEquals(ivan, Unread.picked("Иван", chats))
        assertNull(Unread.picked("ответь ему что я буду через 30 минут", chats))
        assertNull(Unread.picked("какая погода", chats))
    }

    @Test
    fun `a long chat is retold, a short one read`() {
        val milana = chat("Милана", 1, "Ты где", "Ау", "Перезвони")
        assertTrue(Unread.isLong(milana))
        assertTrue(!Unread.isLong(ivan))
        assertEquals(
            "Милана в WhatsApp. Коротко: просит перезвонить.",
            Unread.tell(milana, "Просит перезвонить.".lowercase(), true)
        )
        assertEquals("Иван Петров в WhatsApp: «Ок».", Unread.tell(ivan, null, true))
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
    fun `asking what is unread`() {
        listOf(
            "Есть непрочитанные сообщения?", "У меня есть непрочитанные?", "Какие сообщения я не прочёл?",
            "какие сообщения я не прочитал", "Кто мне писал?", "any unread messages?"
        ).forEach { assertEquals(it, CommandResult.ReadMessages(null), router.route(it)) }
    }

    @Test
    fun `answering him after the chat is read`() {
        assertEquals(
            CommandResult.ReplyMessage("ему", "я буду через 30 минут"),
            router.route("Ответь ему что я буду через 30 минут.")
        )
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
    fun `a bare ответь is a reply with the rest as the message`() {
        assertEquals(CommandResult.ReplyMessage(null, "хорошо", "хорошо"), router.route("ответь хорошо"))
        assertEquals(CommandResult.ReplyMessage(null, "Хорошо.", "Хорошо."), router.route("Ответь. Хорошо."))
        assertEquals(
            CommandResult.ReplyMessage("хорошо", "спасибо", "хорошо спасибо"),
            router.route("ответь хорошо спасибо")
        )
    }

    @Test
    fun `a separated reply is not loose`() {
        assertEquals(
            CommandResult.ReplyMessage("Ивану", "буду в пять"),
            router.route("ответь Ивану: буду в пять")
        )
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
    private val ivan = chat("Иван", 200, "Ок?")

    init {
        every { inbox.justRead(any()) } returns null
    }

    @Test
    fun `a reply is read back and sent only on yes`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum)
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
        every { inbox.answerable(any()) } returns listOf(mum)
        assistant.reply(null, "еду", true)
        assertEquals("Не отправляю.", assistant.answerPending("нет", true))
        verify(exactly = 0) { inbox.reply(any(), any(), any()) }
    }

    @Test
    fun `a stale question is not answered by a later yes`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum)
        assistant.reply(null, "еду", true)
        now += 3 * 60 * 1000L
        assertNull(assistant.answerPending("да", true))
    }

    @Test
    fun `someone with no recent chat gets an e-mail reply`() = runTest {
        every { inbox.answerable(any()) } returns emptyList()
        coEvery { mail.handle(any(), any()) } returns MailAssistant.Answer("Ответить Ивану письмом?")
        assistant.reply("Ивану", "буду в пять", true)
        coVerify { mail.handle(MailCommands.Request.Reply("Ивану", "буду в пять"), true) }
    }

    @Test
    fun `an app without a reply field is said so`() = runTest {
        every { inbox.answerable(any()) } returns listOf(chat("Мамуля", 1, "?", canReply = false))
        assertTrue(assistant.reply("маме", "еду", true).contains("не даёт ответить"))
    }

    @Test
    fun `a closed notification cannot be replied from, and that is said`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum)
        every { inbox.reply(any(), any(), any()) } returns false
        assistant.reply(null, "еду", true)
        assertTrue(assistant.answerPending("да", true)!!.contains("уже закрыто"))
    }

    @Test
    fun `right after a chat is read out, ответь sends at once and says what went`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum, ivan)
        every { inbox.justRead(any()) } returns mum
        every { inbox.reply(any(), mum, "хорошо") } returns true
        assertEquals("Отправила «Мамуля»: «хорошо».", assistant.reply(null, "хорошо", true, loose = "хорошо"))
        verify { inbox.reply(any(), mum, "хорошо") }
        assertNull(assistant.answerPending("да", true))
    }

    @Test
    fun `with nothing read just now, the reply is still read back`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum)
        assertEquals("Ответить «Мамуля» в WhatsApp: «хорошо»?", assistant.reply(null, "хорошо", true, "хорошо"))
        verify(exactly = 0) { inbox.reply(any(), any(), any()) }
    }

    @Test
    fun `a first word that is no name is part of the answer, not an e-mail`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum)
        every { inbox.justRead(any()) } returns mum
        every { inbox.reply(any(), mum, "хорошо спасибо") } returns true
        assistant.reply("хорошо", "спасибо", true, loose = "хорошо спасибо")
        verify { inbox.reply(any(), mum, "хорошо спасибо") }
        coVerify(exactly = 0) { mail.handle(any(), any()) }
    }

    @Test
    fun `a gone notification after a direct reply is said`() = runTest {
        every { inbox.answerable(any()) } returns listOf(mum)
        every { inbox.justRead(any()) } returns mum
        every { inbox.reply(any(), any(), any()) } returns false
        assertTrue(assistant.reply(null, "хорошо", true, "хорошо").contains("уже закрыто"))
    }

    @Test
    fun `several chats - who wrote is asked first, nothing is read yet`() = runTest {
        every { inbox.chats(any()) } returns listOf(mum, ivan)
        assertEquals("Сообщения от: Иван и Мамуля. Чьё прочитать первым?", assistant.read(null, true))
        verify(exactly = 0) { inbox.markRead(any(), any()) }
    }

    @Test
    fun `a name in answer reads that chat, names the rest, and keeps listening`() = runTest {
        var unread = listOf(mum, ivan)
        every { inbox.chats(any()) } answers { unread }
        every { inbox.markRead(ivan.key, any()) } answers { unread = listOf(mum) }
        assistant.read(null, true)
        assertEquals("Иван в WhatsApp: «Ок?». Ещё не прочитаны: Мамуля.", assistant.answerPending("Иван", true))
        assertTrue(assistant.takeFollowUp())
        assertTrue(!assistant.takeFollowUp())
    }

    @Test
    fun `yes reads the newest, no leaves them`() = runTest {
        every { inbox.chats(any()) } returns listOf(mum, ivan)
        assistant.read(null, true)
        assertEquals("Хорошо, позже.", assistant.answerPending("нет", true))
        assertNull(assistant.answerPending("Иван", true))
        assistant.read(null, true)
        assertTrue(assistant.answerPending("да", true)!!.startsWith("Иван в WhatsApp"))
    }

    @Test
    fun `a single chat is read at once`() = runTest {
        every { inbox.chats(any()) } returnsMany listOf(listOf(mum), emptyList())
        assertEquals("Мамуля в WhatsApp: «Ты где?».", assistant.read(null, true))
        verify { inbox.markRead(mum.key, any()) }
    }

    @Test
    fun `a long chat is summarised, and read word for word if that fails`() = runTest {
        val milana = chat("Милана", 300, "Ты где", "Ау", "Перезвони")
        every { inbox.chats(any()) } returnsMany listOf(listOf(milana), emptyList(), listOf(milana), emptyList())
        val summarising = MessageAssistant(mockk(), inbox, mail, summarize = { _, _ -> "Просит перезвонить" })
        assertEquals("Милана в WhatsApp. Коротко: Просит перезвонить.", summarising.read(null, true))
        assertTrue(assistant.read(null, true).startsWith("Милана в WhatsApp: «Ты где» «Ау» «Перезвони»"))
    }

    @Test
    fun `nothing unread is said so`() = runTest {
        every { inbox.chats(any()) } returns emptyList()
        assertEquals("Непрочитанных сообщений нет.", assistant.read(null, true))
    }
}

class PromoFilterTest {

    private fun spam(title: String, vararg texts: String, contacts: Set<String> = emptySet(), group: Boolean = false) =
        PromoFilter.isPromotional(chat(title, 1, *texts, group = group), contacts)

    @Test
    fun `people get through`() {
        assertTrue(!spam("Милана", "Ты где?"))
        assertTrue(!spam("Фирдавс", "Скинь фото"))
        assertTrue(!spam("+998 90 123 45 67", "Это Азиз, новый номер"))
        assertTrue(!spam("Аня", "В Зару скидки"))
    }

    @Test
    fun `companies do not`() {
        assertTrue(spam("900", "Ваш баланс 120 руб"))
        assertTrue(spam("BEELINE", "Подключите тариф"))
        assertTrue(spam("Uzum Bank", "Платёж выполнен"))
        assertTrue(spam("Korzinka", "Скидка 30% на всё! Подробнее https://example.com"))
        assertTrue(spam("Сервис", "Код подтверждения: 4821. Никому не сообщайте"))
    }

    @Test
    fun `the phone book wins`() {
        assertTrue(!spam("ANVAR", "Привет", contacts = setOf("anvar")))
        assertTrue(!spam("Семья", "Скидка 30% https://x.y", group = true))
    }
}

class ReplyTargetTest {

    private val mum = chat("Мама", 100, "Ты где?")
    private val ivan = chat("Иван Петров", 200, "Ок?")
    private val known = listOf(mum, ivan)

    private fun pick(who: String?, text: String, loose: String?, justRead: Conversation?) =
        ReplyTarget.choose(who, text, loose, known, justRead)

    @Test
    fun `the chat just read is answered at once`() {
        assertEquals(ReplyTarget.Choice(mum, "хорошо", sendNow = true), pick(null, "хорошо", "хорошо", mum))
    }

    @Test
    fun `nothing read just now - the newest chat, asked first`() {
        assertEquals(ReplyTarget.Choice(ivan, "хорошо", sendNow = false), pick(null, "хорошо", "хорошо", null))
    }

    @Test
    fun `a named chat other than the one just read is asked first`() {
        assertEquals(ReplyTarget.Choice(ivan, "буду", sendNow = false), pick("Ивану", "буду", null, mum))
        assertEquals(ReplyTarget.Choice(mum, "еду", sendNow = true), pick("маме", "еду", "маме еду", mum))
    }

    @Test
    fun `a lowercase first word that names no chat belongs to the message`() {
        assertEquals(
            ReplyTarget.Choice(mum, "хорошо спасибо", sendNow = true),
            pick("хорошо", "спасибо", "хорошо спасибо", mum)
        )
        assertTrue(!ReplyTarget.isMailFor("хорошо", "хорошо спасибо"))
    }

    @Test
    fun `a capitalised unknown name is still an e-mail reply`() {
        assertNull(pick("Олегу", "буду в пять", "Олегу буду в пять", mum))
        assertTrue(ReplyTarget.isMailFor("Олегу", "Олегу буду в пять"))
        assertTrue(ReplyTarget.isMailFor("Олегу", null))
    }

    @Test
    fun `a bare name alone leaves nothing to send`() {
        assertEquals("", pick(null, "маме", "маме", ivan)?.text)
    }

    @Test
    fun `a dictated full stop and a leading что are not sent`() {
        assertEquals("еду", ReplyTarget.clean("что еду."))
        assertEquals("Ок, через 5 минут", ReplyTarget.clean("Ок, через 5 минут."))
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
