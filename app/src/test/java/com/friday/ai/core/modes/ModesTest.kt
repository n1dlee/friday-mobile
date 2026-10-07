@file:Suppress("MaxLineLength")

package com.friday.ai.core.modes

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.core.DeviceController
import com.friday.ai.core.DeviceKnobs
import com.friday.ai.core.Radio
import com.friday.ai.core.Ringer
import com.friday.ai.data.local.dao.ModeDao
import com.friday.ai.data.local.entity.ModeEntity
import com.friday.ai.data.remote.dto.FunctionCall
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.MediaAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePhrasesTest {

    private fun create(text: String) = ModePhrases.parse(text) as? CommandResult.Mode.Create

    @Test
    fun `the owner's own example, as Whisper writes it down`() {
        val c = create("Пятница, создай режим грусти. Это режим, где включается Spotify с грустными песнями.")!!
        assertEquals("грусти", c.name)
        assertEquals("включается spotify с грустными песнями", c.description)
    }

    @Test
    fun `name and description split at a colon, a dash, or eto`() {
        assertEquals(
            CommandResult.Mode.Create("отдыха", "полный беззвучный режим и яркость на минимум"),
            create("Создай режим отдыха: полный беззвучный режим и яркость на минимум")
        )
        assertEquals("работы", create("сделай режим работы — не беспокоить и громкость 20")?.name)
        assertEquals(
            CommandResult.Mode.Create("сна", "телефон молчит до утра"),
            create("создай режим сна это когда телефон молчит до утра")
        )
    }

    @Test
    fun `a name without a description is asked about, not guessed`() {
        assertEquals(CommandResult.Mode.Create("отдыха", null), create("создай режим отдыха"))
    }

    @Test
    fun `english creation`() {
        assertEquals(CommandResult.Mode.Create("focus", "do not disturb and brightness 30"), create("Create a focus mode: do not disturb and brightness 30"))
    }

    @Test
    fun `run, exit, describe, delete and list`() {
        assertEquals(CommandResult.Mode.Run("грусти"), ModePhrases.parse("режим грусти"))
        assertEquals(CommandResult.Mode.Run("грусти"), ModePhrases.parse("Пятница, включи режим грусти!"))
        assertEquals(CommandResult.Mode.Exit("отдыха"), ModePhrases.parse("выключи режим отдыха"))
        assertEquals(CommandResult.Mode.Exit("отдыха"), ModePhrases.parse("выйди из режима отдыха"))
        assertEquals(CommandResult.Mode.Describe("отдыха"), ModePhrases.parse("что делает режим отдыха?"))
        assertEquals(CommandResult.Mode.Delete("грусти"), ModePhrases.parse("удали режим грусти"))
        assertEquals(CommandResult.Mode.ListAll, ModePhrases.parse("какие у меня есть режимы"))
        assertEquals(CommandResult.Mode.Run("focus"), ModePhrases.parse("start focus mode"))
        assertNull(ModePhrases.parse("какая погода завтра"))
    }

    @Test
    fun `names match across the case russian puts them in`() {
        assertTrue(ModePhrases.same("грусти", "грусть"))
        assertTrue(ModePhrases.same("отдыха", "отдых"))
        assertTrue(ModePhrases.same("грусти пожалуйста", "грусти"))
        assertTrue(ModePhrases.same("утренний", "утреннего"))
        assertFalse(ModePhrases.same("грустный", "отдых"))
        assertFalse(ModePhrases.same("работы", "отдыха"))
        assertFalse("too many extra words", ModePhrases.same("грусти и ещё что-то", "грусти"))
    }
}

/** The modes table in memory. */
private class MemoryModeDao : ModeDao {
    val rows = MutableStateFlow<List<ModeEntity>>(emptyList())
    override suspend fun all() = rows.value
    override fun observeAll() = rows.asStateFlow()
    override suspend fun upsert(mode: ModeEntity) { rows.value = rows.value.filterNot { it.id == mode.id } + mode }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

/** A phone's settings in memory. */
private class PhoneSettings : DeviceKnobs {
    var volume = 70
    var dndState = false
    var ringerMode = Ringer.NORMAL
    var brightness = 60
    var auto = false
    override fun volumePercent() = volume
    override fun setVolumePercent(percent: Int) { volume = percent }
    override fun dndAccess() = true
    override fun dndOn() = dndState
    override fun setDnd(on: Boolean) { dndState = on }
    override fun openDndAccessSettings() = Unit
    override fun ringer() = ringerMode
    override fun setRinger(mode: Ringer) { ringerMode = mode }
    override fun canWriteSettings() = true
    override fun openWriteSettingsPermission() = Unit
    override fun brightnessPercent() = brightness
    override fun setBrightnessPercent(percent: Int) { brightness = percent }
    override fun brightnessAuto() = auto
    override fun setBrightnessAuto(auto: Boolean) { this.auto = auto }
    override fun radioOn(radio: Radio): Boolean? = null
    override fun openPanel(radio: Radio) = Unit
}

class ModeEngineTest {

    private val phone = PhoneSettings()
    private val dao = MemoryModeDao()
    private val store = ModeStore(dao, clock = { 1_000 })
    private var clock = 1_000L
    private val ran = mutableListOf<CommandResult>()

    /** What the model would answer for each description. */
    private val compiled = mutableMapOf<String, ModeCompiler.Result>()
    private val engine = ModeEngine(
        store,
        compile = { _, description -> compiled[description] ?: ModeCompiler.Result.Failed("no_steps") },
        device = DeviceController(phone),
        clock = { clock }
    )

    private val runner: suspend (CommandResult) -> String = { c ->
        ran += c
        when (c) {
            is CommandResult.PlayMedia -> "Играет «${c.query}» в Spotify"
            is CommandResult.MediaControl -> "Пауза"
            else -> "Готово"
        }
    }

    private fun envelope(tool: String, json: String) = ActionEnvelope(tool, Json.parseToJsonElement(json) as JsonObject)

    private val rest = listOf(
        envelope("phone_control", """{"target":"ringer","state":"silent"}"""),
        envelope("phone_control", """{"target":"dnd","state":"on"}"""),
        envelope("phone_control", """{"target":"brightness","level":5}""")
    )
    private val sad = listOf(envelope("play", """{"query":"грустные песни","kind":"music","app":"Spotify"}"""))

    private suspend fun say(text: String): String? {
        val request = engine.route(text) ?: return null
        return engine.handle(request, russian = true, runner = runner)
    }

    private suspend fun createRest() {
        compiled["полный беззвучный, не беспокоить и яркость на минимум"] = ModeCompiler.Result.Steps(rest, emptyList())
        say("Создай режим отдыха: полный беззвучный, не беспокоить и яркость на минимум")
    }

    @Test
    fun `creating reads the steps back and saves them`() = runTest {
        compiled["включается spotify с грустными песнями"] = ModeCompiler.Result.Steps(sad, emptyList())
        val reply = say("Создай режим грусти. Это режим, где включается Spotify с грустными песнями.")!!
        assertTrue(reply, reply.startsWith("Режим грусти: включу «грустные песни» в Spotify."))
        assertTrue(reply.contains("«отмена»"))
        assertEquals(1, dao.rows.value.size)
        assertTrue("creating must not run anything", ran.isEmpty())
    }

    @Test
    fun `running needs no model and says what each step did`() = runTest {
        createRest()
        val reply = say("режим отдыха")!!
        assertEquals(Ringer.SILENT, phone.ringerMode)
        assertTrue(phone.dndState)
        assertEquals(5, phone.brightness)
        assertTrue(reply, reply.startsWith("Режим отдыха. Полностью беззвучно."))
        assertTrue(reply.contains("Яркость 5%"))
        assertTrue(store.all().single().active)
    }

    @Test
    fun `exit puts back exactly what the mode changed`() = runTest {
        createRest()
        say("режим отдыха")
        val reply = say("выйди из режима отдыха")!!
        assertEquals(Ringer.NORMAL, phone.ringerMode)
        assertFalse(phone.dndState)
        assertEquals(60, phone.brightness)
        assertTrue(reply, reply.startsWith("Режим отдыха выключен."))
        assertFalse(store.all().single().active)
    }

    @Test
    fun `running twice still restores the state from before the first run`() = runTest {
        createRest()
        say("режим отдыха")
        say("режим отдыха")
        say("выключи режим отдыха")
        assertEquals(60, phone.brightness)
        assertFalse(phone.dndState)
    }

    @Test
    fun `music a mode started is paused when it ends`() = runTest {
        compiled["включается spotify с грустными песнями"] = ModeCompiler.Result.Steps(sad, emptyList())
        say("Создай режим грусти. Это режим, где включается Spotify с грустными песнями.")
        say("режим грусти")
        assertEquals(CommandResult.PlayMedia::class, ran.last()::class)
        say("выключи режим грусти")
        assertEquals(CommandResult.MediaControl(MediaAction.PAUSE, null), ran.last())
    }

    @Test
    fun `a failed step is not undone, and is said`() = runTest {
        compiled["включается spotify с грустными песнями"] = ModeCompiler.Result.Steps(sad, emptyList())
        say("Создай режим грусти. Это режим, где включается Spotify с грустными песнями.")
        val failing: suspend (CommandResult) -> String = { "Не получилось: нет сети" }
        val reply = engine.handle(CommandResult.Mode.Run("грусти"), russian = true, runner = failing)
        assertTrue(reply.contains("Не получилось"))
        assertTrue("nothing started, nothing to pause", store.all().single().undo!!.isEmpty())
    }

    @Test
    fun `cancel right after creating removes the mode, later it does not`() = runTest {
        createRest()
        assertEquals("Убрала режим отдыха. Опишите его ещё раз, по-другому.", say("нет, не так"))
        assertTrue(store.all().isEmpty())

        createRest()
        clock += 5 * 60 * 1000
        assertNull("too late to be about the mode", engine.route("нет, не так"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `an unknown name isn't taken from the rest of Friday`() = runTest {
        createRest()
        assertNull("airplane mode is a system setting", engine.route("включи режим полета"))
        assertNull(engine.route("выключи режим полета"))
    }

    @Test
    fun `a description the model can't use is said, nothing saved`() = runTest {
        val reply = say("создай режим хаоса: сделай что-нибудь")!!
        assertTrue(reply.startsWith("Не поняла"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `no description is asked for`() = runTest {
        assertTrue(say("создай режим отдыха")!!.startsWith("Что должно происходить в режиме отдыха?"))
    }

    @Test
    fun `describe, list, delete`() = runTest {
        createRest()
        assertEquals(
            "Режим отдыха: полностью беззвучно, «Не беспокоить», яркость 5 %. Сейчас выключен.",
            say("что делает режим отдыха")
        )
        assertEquals("Ваши режимы: отдыха.", say("какие у меня режимы"))
        assertEquals("Удалила режим отдыха.", say("удали режим отдыха"))
        assertTrue(say("какие у меня режимы")!!.startsWith("Режимов пока нет"))
    }

    @Test
    fun `recreating a mode by the same name replaces its steps`() = runTest {
        createRest()
        compiled["только вибрация"] = ModeCompiler.Result.Steps(
            listOf(envelope("phone_control", """{"target":"ringer","state":"vibrate"}""")), emptyList()
        )
        val reply = say("создай режим отдыха: только вибрация")!!
        assertTrue(reply.startsWith("Обновила режим отдыха"))
        assertEquals(1, store.all().size)
        assertEquals(1, store.all().single().steps.size)
    }

    private val router = com.friday.ai.core.CommandRouter()

    private suspend fun sayRouted(text: String): String? {
        engine.answerPending(text, russian = true)?.let { return it }
        val request = engine.route(text) ?: return null
        return engine.handle(request, russian = true, router = { router.route(it) }, runner = runner)
    }

    @Test
    fun `a correction right after a run is done, then offered to the mode`() = runTest {
        compiled["включается spotify с грустными песнями"] = ModeCompiler.Result.Steps(sad, emptyList())
        sayRouted("Создай режим грусти. Это режим, где включается Spotify с грустными песнями.")
        sayRouted("режим грусти")
        val reply = sayRouted("нет, включи lofi в spotify")!!
        assertTrue(reply, reply.endsWith("Запомнить это для режима грусти вместо «включу «грустные песни» в Spotify»?"))
        assertTrue(ran.last() is CommandResult.PlayMedia)

        val yes = sayRouted("да")!!
        assertTrue(yes, yes.startsWith("Запомнила."))
        val step = store.all().single().steps.single()
        assertEquals("lofi", (step.args["query"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a correction of another kind is offered as a new step, and no keeps the mode`() = runTest {
        compiled["включается spotify с грустными песнями"] = ModeCompiler.Result.Steps(sad, emptyList())
        sayRouted("Создай режим грусти. Это режим, где включается Spotify с грустными песнями.")
        sayRouted("режим грусти")
        assertTrue(sayRouted("нет, включи не беспокоить")!!.endsWith("Добавить это в режим грусти?"))
        assertTrue(phone.dndState)
        assertEquals("Хорошо, режим остаётся как был.", sayRouted("нет"))
        assertEquals(1, store.all().single().steps.size)
    }

    @Test
    fun `no is not a correction long after the run`() = runTest {
        createRest()
        sayRouted("режим отдыха")
        clock += 10 * 60 * 1000
        assertNull(engine.route("нет, включи lofi"))
    }

    @Test
    fun `modes survive a restart, undo included`() = runTest {
        createRest()
        say("режим отдыха")
        val reloaded = ModeStore(dao)
        reloaded.load()
        assertTrue(reloaded.all().single().active)
        assertEquals(3, reloaded.all().single().undo!!.size)
    }
}

class ModeCompilerTest {

    private val compiler = ModeCompiler(
        groq = io.mockk.mockk(relaxed = true),
        access = { ModeCompiler.Access("k", "m", null) },
        capabilities = { null }
    )

    private fun call(name: String, args: String) = ToolCall("1", function = FunctionCall(name, args))

    @Test
    fun `only valid commands become steps, the rest is reported`() {
        val r = compiler.toSteps(
            listOf(
                call("phone_control", """{"target":"dnd","state":"on"}"""),
                call("play", """{"query":"lofi","kind":"music"}"""),
                call("phone_control", """{"target":"teleport"}"""),
                call("calculate", """{"expression":"2+2"}"""),
                call("set_alarm", "not json")
            )
        ) as ModeCompiler.Result.Steps
        assertEquals(listOf("phone_control", "play"), r.steps.map { it.tool })
        assertEquals(3, r.skipped.size)
    }

    @Test
    fun `nothing usable is a failure, not an empty mode`() {
        assertTrue(compiler.toSteps(emptyList()) is ModeCompiler.Result.Failed)
    }
}
