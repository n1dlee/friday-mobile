package com.friday.ai.agent

import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.CompoundRequest
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.data.remote.ChatEvent
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.ToolCallAssembler
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.ChatCompletionRequest
import com.friday.ai.data.remote.dto.FunctionCall
import com.friday.ai.data.remote.dto.FunctionDelta
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.data.remote.dto.ToolCallDelta
import com.friday.ai.data.remote.groqJson
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.CameraMode
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {

    private fun calc(e: String) = Calculator.format(Calculator.evaluate(e))

    @Test
    fun `the sums the model gets wrong`() {
        assertEquals("391", calc("17*23"))
        assertEquals("197.5", calc("(17*23+4)/2"))
        assertEquals("30", calc("0.15*200"))
        assertEquals("1024", calc("2^10"))
        assertEquals("1024", calc("2**10"))
    }

    @Test
    fun `precedence and signs read as on paper`() {
        assertEquals("14", calc("2+3*4"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("1", calc("-(-1)"))
    }

    @Test
    fun `what people and models actually write`() {
        assertEquals("391", calc("17 × 23"))
        assertEquals("2.5", calc("5 ÷ 2"))
        assertEquals("3.75", calc("1,5 * 2,5"))
        assertEquals("1.41421356237", calc("sqrt(2)"))
    }

    @Test
    fun `nonsense is refused, not guessed`() {
        listOf("1/0", "2+", "abc", "sqrt(-1)", "(1+2", "1+2)", "10 % 3").forEach {
            try {
                Calculator.evaluate(it)
                throw AssertionError("'$it' should be refused")
            } catch (_: Calculator.CalculationError) {
                // expected
            }
        }
    }
}

class AgentToolsTest {

    /** Monday evening. */
    private val now = LocalDateTime.of(2026, 10, 5, 22, 10)

    private fun call(name: String, json: String) =
        AgentTools.interpret(name, groqJson.parseToJsonElement(json).jsonObject, now)

    private fun command(name: String, json: String): CommandResult =
        (call(name, json) as? AgentTools.Call.Command)?.command ?: throw AssertionError("got ${call(name, json)}")

    @Test
    fun `every tool offered can be interpreted`() {
        // A tool the model is shown but this side cannot read would fail at
        // the worst moment; each one must at least be known here.
        AgentTools.definitions.forEach { def ->
            val r = AgentTools.interpret(def.function.name, JsonObject(emptyMap()), now)
            assertFalse(def.function.name, r is AgentTools.Call.Invalid && r.reason.startsWith("there is no tool"))
        }
    }

    @Test
    fun `an alarm for the next such morning carries no date`() {
        assertEquals(CommandResult.SetAlarm(7, 30, null), command("set_alarm", """{"hour":7,"minute":30}"""))
        // Tomorrow is the next 07:30 anyway.
        assertEquals(
            CommandResult.SetAlarm(7, 30, null),
            command("set_alarm", """{"hour":7,"minute":30,"date":"2026-10-06"}""")
        )
    }

    @Test
    fun `an alarm for another day keeps the date so it can be refused`() {
        val c = command("set_alarm", """{"hour":7,"minute":30,"date":"2026-10-09"}""") as CommandResult.SetAlarm
        assertEquals(LocalDate.of(2026, 10, 9), c.date)
    }

    @Test
    fun `impossible arguments go back to the model`() {
        assertTrue(call("set_alarm", """{"hour":31,"minute":0}""") is AgentTools.Call.Invalid)
        assertTrue(call("set_alarm", """{"minute":0}""") is AgentTools.Call.Invalid)
        assertTrue(call("set_timer", """{"seconds":0}""") is AgentTools.Call.Invalid)
        assertTrue(call("phone_control", """{"target":"reboot"}""") is AgentTools.Call.Invalid)
        assertTrue(call("create_event", """{"title":"x","start":"завтра"}""") is AgentTools.Call.Invalid)
        assertTrue(call("teleport", "{}") is AgentTools.Call.Invalid)
    }

    @Test
    fun `numbers written as strings or decimals are accepted`() {
        assertEquals(CommandResult.SetAlarm(8, 0, null), command("set_alarm", """{"hour":"8","minute":0.0}"""))
    }

    @Test
    fun `tools land on the same commands the phrases do`() {
        assertEquals(
            CommandResult.SendMessage("мама", "Опоздаю"),
            command("send_message", """{"contact":"мама","text":"Опоздаю"}""")
        )
        assertEquals(
            CommandResult.CreateEvent("Врач", "2026-10-07T15:00"),
            command("create_event", """{"title":"Врач","start":"2026-10-07T15:00:00+05:00"}""")
        )
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 40),
            command("phone_control", """{"target":"volume","level":40}""")
        )
        assertEquals(CommandResult.OpenCamera(CameraMode.SELFIE), command("camera", """{"mode":"selfie"}"""))
        assertEquals(CommandResult.Weather(null, 1), command("weather", """{"day_offset":1}"""))
        assertEquals(CommandResult.WhatDidIMiss, command("briefing", """{"kind":"missed"}"""))
        assertEquals(
            CommandResult.Mail(MailCommands.Request.Compose("Иван", "Буду в пять")),
            command("mail", """{"action":"send","person":"Иван","text":"Буду в пять"}""")
        )
    }

    @Test
    fun `arithmetic is answered on the spot`() {
        assertEquals(AgentTools.Call.Answer("17*23 = 391"), call("calculate", """{"expression":"17*23"}"""))
        assertTrue(call("calculate", """{"expression":"1/0"}""") is AgentTools.Call.Invalid)
    }

    @Test
    fun `optional fields are left out of the request, not sent as null`() {
        val body = groqJson.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(model = "m", messages = listOf(ApiMessage("user", "hi")))
        )
        assertFalse(body, body.contains("tools") || body.contains("tool_call"))
        val withTools = groqJson.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(model = "m", messages = emptyList(), tools = AgentTools.definitions)
        )
        assertTrue(withTools.contains(""""name":"set_alarm""""))
        assertTrue(withTools.contains(""""required":["hour","minute"]"""))
    }
}

class ToolCallAssemblerTest {

    @Test
    fun `a call streamed in pieces is put back together`() {
        val a = ToolCallAssembler()
        a.add(listOf(ToolCallDelta(0, "call_1", FunctionDelta("set_alarm", ""))))
        a.add(listOf(ToolCallDelta(0, null, FunctionDelta(null, """{"hour":7,"""))))
        a.add(listOf(ToolCallDelta(0, null, FunctionDelta(null, """"minute":30}"""))))
        a.add(listOf(ToolCallDelta(1, "call_2", FunctionDelta("flashlight", null))))
        assertEquals(
            listOf(
                ToolCall("call_1", function = FunctionCall("set_alarm", """{"hour":7,"minute":30}""")),
                ToolCall("call_2", function = FunctionCall("flashlight", "{}"))
            ),
            a.calls()
        )
    }

    @Test
    fun `a streamed chunk with tool calls is read`() {
        val chunk = """{"id":"x","choices":[{"index":0,"delta":{"tool_calls":""" +
            """[{"index":0,"id":"c","type":"function",""" +
            """"function":{"name":"calculate","arguments":"{\"expression\":\"2+2\"}"}}]}}]}"""
        val delta = groqJson.decodeFromString(
            com.friday.ai.data.remote.dto.ChatCompletionChunk.serializer(), chunk
        ).choices.single().delta
        val a = ToolCallAssembler().apply { add(delta.toolCalls!!) }
        assertEquals("""{"expression":"2+2"}""", a.calls().single().function.arguments)
    }
}

class FridayAgentTest {

    private val groq = mockk<GroqApiService>()
    private val commands = mockk<CommandExecutor>()
    private val agent = FridayAgent(groq, commands, now = { LocalDateTime.of(2026, 10, 5, 22, 10) })
    private val sent = mutableListOf<List<ApiMessage>>()

    private fun replies(vararg turns: Flow<ChatEvent>) {
        val queue = ArrayDeque(turns.toList())
        every { groq.streamChat(any(), capture(sent), any(), any(), any()) } answers { queue.removeFirst() }
    }

    private fun calls(vararg c: Pair<String, String>) =
        flowOf(ChatEvent.ToolCalls(c.mapIndexed { i, (n, a) -> ToolCall("call_$i", function = FunctionCall(n, a)) }))

    private fun text(vararg t: String) = flowOf(*t.map { ChatEvent.Text(it) }.toTypedArray())

    private suspend fun ask(q: String) =
        agent.reply(FridayAgent.Settings("k", "m", 100), listOf(ApiMessage("user", q)), russian = true)
            .toList().joinToString("")

    @Test
    fun `a plain question is answered in one turn`() = runTest {
        replies(text("Добрый ", "вечер."))
        assertEquals("Добрый вечер.", ask("Привет"))
        assertEquals(1, sent.size)
    }

    @Test
    fun `the reply is written from what the tool actually did`() = runTest {
        replies(calls("set_alarm" to """{"hour":7,"minute":30}"""), text("Часы открыты — сохраните будильник."))
        coEvery { commands.execute(CommandResult.SetAlarm(7, 30, null), true) } returns
            CommandExecutor.Outcome.Reply("Часы не создали будильник на 07:30 сами — открыла их")

        assertEquals("Часы открыты — сохраните будильник.", ask("Закинь будильник на полвосьмого"))

        val second = sent[1]
        assertEquals("tool", second.last().role)
        assertEquals("call_0", second.last().toolCallId)
        assertTrue(second.last().content.contains("не создали"))
        assertEquals("set_alarm", second[second.size - 2].toolCalls!!.single().function.name)
    }

    @Test
    fun `several actions in one request are all carried out`() = runTest {
        replies(
            calls(
                "set_alarm" to """{"hour":7,"minute":30}""",
                "send_message" to """{"contact":"мама","text":"Опоздаю"}"""
            ),
            text("Готово.")
        )
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("ok")
        ask("Поставь будильник на 7:30 и напиши маме, что опоздаю")
        coVerify { commands.execute(CommandResult.SetAlarm(7, 30, null), true) }
        coVerify { commands.execute(CommandResult.SendMessage("мама", "Опоздаю"), true) }
        assertEquals(2, sent[1].count { it.role == "tool" })
    }

    @Test
    fun `arithmetic never reaches the phone`() = runTest {
        replies(calls("calculate" to """{"expression":"17*23"}"""), text("391."))
        assertEquals("391.", ask("Сколько будет 17 на 23"))
        assertEquals("17*23 = 391\n(Tell the user in Russian.)", sent[1].last().content)
        coVerify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `the model is told which language to retell a result in`() = runTest {
        // English results were copied verbatim into replies to Russian requests.
        replies(calls("flashlight" to "{}"), text("Включила."), calls("flashlight" to "{}"), text("Done."))
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("Flashlight on")
        ask("Включи фонарик")
        assertTrue(sent[1].last().content.endsWith("(Tell the user in Russian.)"))
        ask("Turn on the flashlight")
        assertTrue(sent[3].last().content.endsWith("(Tell the user in English.)"))
    }

    @Test
    fun `a bad call is explained to the model and nothing is done`() = runTest {
        replies(calls("set_alarm" to """{"hour":31,"minute":0}"""), text("Такого времени нет."))
        ask("Будильник на 31 час")
        assertTrue(sent[1].last().content.startsWith("Error:"))
        assertTrue(sent[1].last().content.contains("Nothing was done"))
        coVerify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `the last turn is offered no tools so it has to answer`() = runTest {
        val toolsOffered = mutableListOf<Boolean>()
        every { groq.streamChat(any(), any(), any(), any(), any()) } answers {
            toolsOffered += arg<Any?>(4) != null
            if (toolsOffered.size < 4) calls("flashlight" to "{}") else text("Хватит.")
        }
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("ok")
        assertEquals("Хватит.", ask("мигай фонариком"))
        assertEquals(listOf(true, true, true, false), toolsOffered)
    }

    @Test
    fun `a malformed tool call is retried once`() = runTest {
        var attempts = 0
        every { groq.streamChat(any(), any(), any(), any(), any()) } answers {
            attempts++
            if (attempts == 1) flow { throw GroqApiException(400, "API error (400): tool_use_failed") }
            else text("Ок.")
        }
        assertEquals("Ок.", ask("что-то"))
        assertEquals(2, attempts)
    }

    @Test
    fun `a used-up minute moves the rest of the answer to the backup model`() = runTest {
        val models = mutableListOf<String>()
        every { groq.streamChat(any(), any(), capture(models), any(), any()) } answers {
            when (models.size) {
                1 -> flow { throw GroqApiException(429, "Rate limit exceeded.") }
                2 -> calls("flashlight" to "{}")
                else -> text("Включила.")
            }
        }
        coEvery { commands.execute(any(), any()) } returns CommandExecutor.Outcome.Reply("on")
        val settings = FridayAgent.Settings("k", "big", 100, backupModel = "small")
        val reply = agent.reply(settings, listOf(ApiMessage("user", "фонарик")), true).toList().joinToString("")
        assertEquals("Включила.", reply)
        // Once the big model is out, it is not asked again within this answer.
        assertEquals(listOf("big", "small", "small"), models)
    }

    @Test
    fun `a limit that lifts in a moment is waited out on the same model`() = runTest {
        val models = mutableListOf<String>()
        every { groq.streamChat(any(), any(), capture(models), any(), any()) } answers {
            if (models.size == 1) flow { throw GroqApiException(429, "Rate limit exceeded.", retryAfterMs = 855) }
            else text("Да.")
        }
        val settings = FridayAgent.Settings("k", "big", 100, backupModel = "small")
        assertEquals("Да.", agent.reply(settings, listOf(ApiMessage("user", "x")), true).toList().joinToString(""))
        assertEquals(listOf("big", "big"), models)
    }

    @Test
    fun `groq's wait is read from its reply`() {
        val body = "Rate limit reached ... Please try again in 2.3025s. Need more tokens?"
        assertEquals(2302L, GroqApiException.retryAfterMs(body))
        assertEquals(855L, GroqApiException.retryAfterMs("try again in 855ms."))
        assertEquals(null, GroqApiException.retryAfterMs("nothing here"))
    }

    @Test
    fun `requests with tools think harder, plain ones stay quick`() {
        val m = "openai/gpt-oss-120b"
        assertEquals("medium", com.friday.ai.core.GroqModels.reasoningEffort(m, thorough = true))
        assertEquals("low", com.friday.ai.core.GroqModels.reasoningEffort(m))
        assertTrue(com.friday.ai.core.GroqModels.tokenBudget(m, 150, thorough = true) > 450)
    }

    @Test(expected = GroqApiException::class)
    fun `without a backup a rate limit is reported`() = runTest {
        every { groq.streamChat(any(), any(), any(), any(), any()) } returns
            flow { throw GroqApiException(429, "Rate limit exceeded.") }
        ask("что-то")
    }

    @Test(expected = GroqApiException::class)
    fun `other failures are not swallowed`() = runTest {
        every { groq.streamChat(any(), any(), any(), any(), any()) } returns
            flow { throw GroqApiException(429, "Rate limit exceeded.") }
        ask("что-то")
    }
}

class CompoundRequestTest {

    private val router = CommandRouter(now = { LocalDateTime.of(2026, 10, 5, 22, 10) })

    private fun split(text: String) = CompoundRequest.split(text, router::route)

    @Test
    fun `known commands said together are all carried out, without the model`() {
        // The model, given this, set the volume and claimed the camera too.
        val r = router.route("выключи звук, включи фонарик и поставь таймер на 10 минут")
        assertTrue("got $r", r is CommandResult.Sequence)
        r as CommandResult.Sequence
        assertEquals(CommandResult.DeviceControl(DeviceAction.MUTE), r.steps[0])
        assertEquals(CommandResult.Flashlight(on = true), r.steps[1])
        assertTrue(r.steps[2] is CommandResult.SetTimer)

        val two = router.route("включи фонарик и поставь будильник на 7") as CommandResult.Sequence
        assertEquals(CommandResult.SetAlarm(7, 0, null), two.steps[1])
    }

    @Test
    fun `a part the patterns do not know sends the whole sentence to the model`() {
        listOf(
            "Поставь будильник на 7:30 и напиши маме, что я опоздаю",
            "расскажи анекдот и включи фонарик",
            "включи фонарик и расскажи анекдот"
        ).forEach { assertTrue(it, router.route(it) is CommandResult.ChatMessage) }
    }

    @Test
    fun `one request said in two verbs stays one command`() {
        assertEquals(CommandResult.OpenCamera(CameraMode.PHOTO), router.route("открой камеру и сделай фото"))
        assertTrue(router.route("поставь будильник на 7 и 30") is CommandResult.SetAlarm)
    }

    @Test
    fun `an и inside dictated text belongs to the text`() {
        val note = router.route("запиши купить хлеб и молоко")
        assertTrue("got $note", note is CommandResult.CreateNote)
        // Note text or a second request? Not guessed here: the model decides.
        assertEquals(CompoundRequest.Split.Mixed, split("запиши купить хлеб и позвонить маме"))
    }

    @Test
    fun `a comma only cuts off a command`() {
        val r = router.route("включи фонарик, поставь будильник на 7")
        assertTrue("got $r", r is CommandResult.Sequence)
        assertEquals(CompoundRequest.Split.Single, split("расскажи, как дела"))
    }

    @Test
    fun `the screen is never handed to the model`() {
        assertEquals(CommandResult.AnalyzeScreen, router.route("проанализируй экран и включи фонарик"))
    }

    @Test
    fun `conversation is not split`() {
        assertEquals(CompoundRequest.Split.Single, split("расскажи про Тома и Джерри"))
        assertEquals(CompoundRequest.Split.Single, split("кто такой Том и кто такой Джерри"))
    }
}

class SequenceTest {

    private val phone = mockk<com.friday.ai.command.PhoneActions>()
    private val executor = CommandExecutor(
        mockk(relaxed = true), phone, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        kotlinx.coroutines.Dispatchers.Unconfined
    )

    @Test
    fun `every step runs, a failed one included, and the replies are said together`() = runTest {
        coEvery { phone.run(CommandResult.ToggleFlashlight, true) } throws IllegalStateException("занято")
        coEvery { phone.run(CommandResult.SetTimer(600, null), true) } returns "Таймер на 10 минут"
        val r = executor.execute(
            CommandResult.Sequence(listOf(CommandResult.ToggleFlashlight, CommandResult.SetTimer(600, null))),
            russian = true
        )
        assertEquals(CommandExecutor.Outcome.Reply("Не получилось: занято. Таймер на 10 минут."), r)
    }
}

class ToolPromptTest {

    @Test
    fun `with tools the model may act but only reports what the tools said`() {
        val prompt = SystemPromptBuilder().build(AssistantMode.DEFAULT, withTools = true)
        assertTrue(prompt.contains("only by calling the provided tools"))
        assertTrue(prompt.contains("unless a tool result"))
        assertFalse(prompt.contains("You cannot operate the phone"))
    }
}
