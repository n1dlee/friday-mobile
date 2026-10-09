// Long lines on purpose: tool-call JSON is kept whole so it reads as the model sends it.
@file:Suppress("MaxLineLength")

package com.friday.ai.agent

import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.CommandRouter
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.ChatEvent
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.FunctionCall
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.data.remote.dto.ToolDefinition
import com.friday.ai.data.remote.groqJson
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.voice.sourceLabel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolKitTest {

    @Test
    fun `talk gets the light kit`() {
        listOf("расскажи анекдот", "как дела", "кто выиграл последний чемпионат мира", "сколько будет 17 на 23")
            .forEach { assertEquals(it, ToolKit.Kit.LIGHT, ToolKit.forText(it)) }
    }

    private fun offered(text: String) = AgentTools.definitions(ToolKit.forText(text)).map { it.function.name }.toSet()

    @Test
    fun `a request gets the tools it needs`() {
        mapOf(
            "закинь будильник на полвосьмого" to listOf("set_alarm"),
            "включи Imagine Dragons" to listOf("play"),
            "turn it up" to listOf("phone_control"),
            "напиши маме, что опоздаю" to listOf("send_message"),
            "сделай погромче и открой селфи-камеру" to listOf("phone_control", "camera"),
            "поставь будильник на семь и напиши маме, что опоздаю" to listOf("set_alarm", "send_message"),
            "есть непрочитанные сообщения?" to listOf("read_messages"),
            "какая погода завтра" to listOf("weather"),
            "запусти режим отдыха" to listOf("run_mode")
        ).forEach { (text, tools) -> assertTrue("$text: ${offered(text)}", offered(text).containsAll(tools)) }
    }

    @Test
    fun `a focused kit can always ask for the rest`() {
        assertTrue(ToolKit.ESCALATE in offered("поставь будильник на семь"))
        assertTrue(ToolKit.forText("поставь будильник на семь") is ToolKit.Kit.Focused)
    }

    @Test
    fun `a focused kit is a fraction of the full one`() {
        val list = kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer())
        val alarm = groqJson.encodeToString(list, AgentTools.definitions(ToolKit.forText("поставь будильник на семь")))
        val full = groqJson.encodeToString(list, AgentTools.definitions)
        assertTrue("alarm ${alarm.length} vs full ${full.length}", alarm.length * 3 < full.length)
    }

    @Test
    fun `the light kit is search, arithmetic and a way to ask for the rest`() {
        val names = AgentTools.definitions(ToolKit.Kit.LIGHT).map { it.function.name }.toSet()
        assertEquals(setOf("web_search", "calculate", ToolKit.ESCALATE), names)
        val list = kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer())
        val light = groqJson.encodeToString(list, AgentTools.definitions(ToolKit.Kit.LIGHT))
        val full = groqJson.encodeToString(list, AgentTools.definitions)
        assertTrue("light ${light.length} vs full ${full.length}", light.length * 4 < full.length)
    }
}

class EscalationAndLearningTest {

    private val groq = mockk<GroqApiService>()
    private val commands = mockk<CommandExecutor>()
    private val learned = mockk<LearnedCommands>(relaxed = true)
    private val agent = FridayAgent(groq, commands, now = { LocalDateTime.of(2026, 10, 5, 22, 0) }, learned = learned)
    private val offered = mutableListOf<Set<String>?>()

    private fun script(vararg turns: kotlinx.coroutines.flow.Flow<ChatEvent>) {
        val queue = ArrayDeque(turns.toList())
        every { groq.streamChat(any(), any(), any(), any(), any()) } answers {
            offered += arg<List<ToolDefinition>?>(4)?.map { it.function.name }?.toSet()
            queue.removeFirst()
        }
    }

    private fun calls(vararg c: Pair<String, String>) =
        flowOf(ChatEvent.ToolCalls(c.mapIndexed { i, (n, a) -> ToolCall("c$i", function = FunctionCall(n, a)) }))

    private suspend fun ask(q: String) =
        agent.reply(FridayAgent.Settings("k", "m", 100), listOf(ApiMessage("user", q)), true).toList().joinToString("")

    @Test
    fun `a missed guess costs one round, not a refusal`() = runTest {
        // "Хочу проснуться в полвосьмого" names no verb or device the kit looks for.
        script(calls(ToolKit.ESCALATE to "{}"), calls("set_alarm" to """{"hour":7,"minute":30}"""), flowOf(ChatEvent.Text("Готово.")))
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("Будильник на 07:30 поставлен.")
        assertEquals("Готово.", ask("хочу проснуться в полвосьмого"))
        assertTrue(offered[0]!!.contains(ToolKit.ESCALATE))
        assertTrue(offered[1]!!.contains("set_alarm"))
        coVerify { commands.execute(CommandResult.SetAlarm(7, 30, null), true) }
    }

    @Test
    fun `one successful call is remembered for next time`() = runTest {
        script(calls("set_alarm" to """{"hour":7,"minute":30}"""), flowOf(ChatEvent.Text("Готово.")))
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("ok")
        ask("закинь будильник на полвосьмого")
        verify { learned.learn("закинь будильник на полвосьмого", "set_alarm", """{"hour":7,"minute":30}""") }
    }

    @Test
    fun `a failed or split request is not`() = runTest {
        script(calls("flashlight" to """{"state":"on"}"""), flowOf(ChatEvent.Text("Не вышло.")))
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("Не получилось: камера занята")
        ask("зажги свет на телефоне")
        script(calls("flashlight" to """{"state":"on"}""", "set_timer" to """{"seconds":60}"""), flowOf(ChatEvent.Text("Ок.")))
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("ok")
        ask("свет и минуту на таймере")
        verify(exactly = 0) { learned.learn(any(), any(), any()) }
    }
}

class LearnedCommandsTest {

    private val prefs = mockk<UserPreferenceDao>(relaxed = true)
    private val now = LocalDateTime.of(2026, 10, 5, 22, 0)
    private var clock = 0L

    private fun args(json: String) = groqJson.parseToJsonElement(json).jsonObject

    @Test
    fun `stable calls on self-contained phrases are worth keeping`() {
        assertTrue(LearnedCommands.worthLearning("закинь будильник на полвосьмого", "set_alarm", args("""{"hour":7,"minute":30}""")))
        assertTrue(LearnedCommands.worthLearning("врубай Imagine Dragons", "play", args("""{"query":"Imagine Dragons","kind":"music"}""")))
    }

    @Test
    fun `phrases that lean on what came before are not`() {
        assertFalse(LearnedCommands.worthLearning("а теперь на восемь", "set_alarm", args("""{"hour":8,"minute":0}""")))
        assertFalse(LearnedCommands.worthLearning("включи его снова", "media", args("""{"action":"play"}""")))
    }

    @Test
    fun `nor dated alarms, messages or one-word phrases`() {
        assertFalse(LearnedCommands.worthLearning("будильник в пятницу на 7", "set_alarm", args("""{"hour":7,"minute":0,"date":"2026-10-09"}""")))
        assertFalse(LearnedCommands.worthLearning("скажи маме что я еду", "send_message", args("""{"contact":"мама","text":"я еду"}""")))
        assertFalse(LearnedCommands.worthLearning("светик", "flashlight", args("""{"state":"on"}""")))
    }

    @Test
    fun `a learned phrase is carried out the next time, punctuation and case aside`() = runTest {
        val l = LearnedCommands(prefs, TestScope(testScheduler), clock = { clock })
        l.learn("Закинь будильник на полвосьмого", "set_alarm", """{"hour":7,"minute":30}""")
        assertEquals(CommandResult.SetAlarm(7, 30, null), l.command("закинь будильник на полвосьмого!", now))
        assertNull(l.command("закинь будильник на восемь", now))
    }

    @Test
    fun `a correction right after undoes the lesson`() = runTest {
        val l = LearnedCommands(prefs, TestScope(testScheduler), clock = { clock })
        l.learn("закинь будильник на полвосьмого", "set_alarm", """{"hour":8,"minute":30}""")
        assertEquals("закинь будильник на полвосьмого", l.noteReply("нет, не то"))
        assertNull(l.command("закинь будильник на полвосьмого", now))
    }

    @Test
    fun `a later no is not about it`() = runTest {
        val l = LearnedCommands(prefs, TestScope(testScheduler), clock = { clock })
        l.learn("закинь будильник на полвосьмого", "set_alarm", """{"hour":7,"minute":30}""")
        clock += 5 * 60 * 1000L
        assertNull(l.noteReply("нет"))
        assertTrue(l.command("закинь будильник на полвосьмого", now) is CommandResult.SetAlarm)
    }

    @Test
    fun `lessons survive a restart`() = runTest {
        val stored = mutableListOf<UserPreferenceEntity>()
        coEvery { prefs.set(capture(stored)) } returns Unit
        val scope = TestScope(testScheduler)
        LearnedCommands(prefs, scope).learn("закинь будильник на полвосьмого", "set_alarm", """{"hour":7,"minute":30}""")
        advanceUntilIdle()
        coEvery { prefs.withPrefix("learned:") } returns stored
        val again = LearnedCommands(prefs, scope).apply { load() }
        advanceUntilIdle()
        assertEquals(CommandResult.SetAlarm(7, 30, null), again.command("закинь будильник на полвосьмого", now))
    }

    @Test
    fun `the executor uses a lesson only where the patterns found nothing, and says so`() = runTest {
        val l = LearnedCommands(prefs, TestScope(testScheduler))
        l.learn("закинь будильник на полвосьмого", "set_alarm", """{"hour":7,"minute":30}""")
        val ex = CommandExecutor(CommandRouter(), mockk(), mockk(), mockk(), mockk(), learned = l, now = { now })
        assertEquals(CommandResult.SetAlarm(7, 30, null), ex.route("закинь будильник на полвосьмого"))
        assertTrue(ex.lastRouteLearned)
        assertTrue(ex.route("включи фонарик") is CommandResult.Flashlight)
        assertFalse(ex.lastRouteLearned)
    }
}

class SourceLabelTest {

    @Test
    fun `the panel says who answered`() {
        assertEquals("ИИ", sourceLabel(CommandResult.ChatMessage("привет"), learned = false, russian = true))
        assertEquals("команда", sourceLabel(CommandResult.ToggleFlashlight, learned = false, russian = true))
        assertEquals("команда · выучена", sourceLabel(CommandResult.SetAlarm(7, 30, null), learned = true, russian = true))
        assertEquals("AI", sourceLabel(CommandResult.ChatMessage("hi"), learned = false, russian = false))
    }
}

class PromptBudgetTest {

    @Test
    fun `the last exchange goes whole, older long messages are cut`() {
        val long = "а".repeat(2000)
        assertEquals(long, PromptBudget.older(long, fromEnd = 1))
        assertEquals(long, PromptBudget.older(long, fromEnd = 2))
        assertEquals(PromptBudget.OLDER_CHARS + 1, PromptBudget.older(long, fromEnd = 3).length)
        assertEquals("коротко", PromptBudget.older("коротко", fromEnd = 9))
    }
}
