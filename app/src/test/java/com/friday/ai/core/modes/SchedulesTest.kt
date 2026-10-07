@file:Suppress("MaxLineLength")

package com.friday.ai.core.modes

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.core.DeviceController
import com.friday.ai.core.DeviceKnobs
import com.friday.ai.core.Radio
import com.friday.ai.core.Ringer
import com.friday.ai.data.local.dao.ModeDao
import com.friday.ai.data.local.dao.ModeScheduleDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.ModeEntity
import com.friday.ai.data.local.entity.ModeRunEntity
import com.friday.ai.data.local.entity.ModeScheduleEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.domain.model.CommandResult
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleMathTest {

    // A Wednesday.
    private val wed2200 = LocalDateTime.of(2026, 10, 7, 22, 0)

    @Test
    fun `later today if still ahead, else the next allowed day`() {
        val s = Schedule("1", "m", false, LocalTime.of(23, 0), Days.ALL)
        assertEquals(LocalDateTime.of(2026, 10, 7, 23, 0), ScheduleMath.next(s, wed2200))
        assertEquals(LocalDateTime.of(2026, 10, 8, 23, 0), ScheduleMath.next(s, wed2200.withHour(23).withMinute(30)))
    }

    @Test
    fun `weekdays skip the weekend`() {
        val s = Schedule("1", "m", false, LocalTime.of(9, 0), Days.WEEKDAYS)
        val friEvening = LocalDateTime.of(2026, 10, 9, 20, 0)
        assertEquals(DayOfWeek.MONDAY, ScheduleMath.next(s, friEvening).dayOfWeek)
    }

    @Test
    fun `days survive the bitmask`() {
        listOf(Days.ALL, Days.WEEKDAYS, Days.WEEKEND, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)).forEach {
            assertEquals(it, Days.of(Days.mask(it)))
        }
    }

    @Test
    fun `described the way people say it`() {
        assertEquals("каждый день в 23:00", ScheduleMath.describe(Days.ALL, LocalTime.of(23, 0), true))
        assertEquals("по будням в 7:30", ScheduleMath.describe(Days.WEEKDAYS, LocalTime.of(7, 30), true))
        assertEquals("по пн, пт в 9:00", ScheduleMath.describe(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY), LocalTime.of(9, 0), true))
    }
}

class SchedulePhrasesTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)
    private fun set(text: String) = SchedulePhrases.parse(text, now) as? SchedulePhrases.Request.Set

    @Test
    fun `the common ways of saying it`() {
        assertEquals(SchedulePhrases.Request.Set("отдыха каждый день в 23:00", false, LocalTime.of(23, 0), Days.ALL), set("Пятница, включай режим отдыха каждый день в 23:00."))
        assertEquals(LocalTime.of(23, 0), set("каждый вечер в 11 включай режим отдыха")?.time)
        assertEquals("отдыха", set("каждый вечер в 11 включай режим отдыха")?.rest)
        val weekdays = set("по будням в 7 утра выключай режим сна")!!
        assertTrue(weekdays.exit)
        assertEquals(LocalTime.of(7, 0), weekdays.time)
        assertEquals(Days.WEEKDAYS, weekdays.days)
        assertEquals(setOf(DayOfWeek.FRIDAY), set("по пятницам в 18:00 режим отдыха")?.days)
    }

    @Test
    fun `no time is kept as a question for later`() {
        assertNull(set("включай режим отдыха каждый день")?.time)
    }

    @Test
    fun `clearing`() {
        assertEquals(SchedulePhrases.Request.Clear("отдыха"), SchedulePhrases.parse("убери расписание режима отдыха", now))
        assertEquals(SchedulePhrases.Request.Clear("отдыха"), SchedulePhrases.parse("не включай режим отдыха по расписанию", now))
    }

    @Test
    fun `an ordinary run is not a schedule`() {
        assertNull(SchedulePhrases.parse("включи режим отдыха", now))
    }
}

class HabitsTest {

    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, day, h, m)

    @Test
    fun `three evenings around the same time are a habit`() {
        assertEquals(LocalTime.of(23, 0), Habits.suggest(listOf(at(1, 22, 50), at(2, 23, 5), at(4, 23, 0))))
    }

    @Test
    fun `two days, or scattered times, are not`() {
        assertNull(Habits.suggest(listOf(at(1, 23, 0), at(2, 23, 0))))
        assertNull(Habits.suggest(listOf(at(1, 9, 0), at(2, 14, 0), at(3, 23, 0))))
    }

    @Test
    fun `around midnight counts as one time`() {
        assertEquals(LocalTime.of(0, 0), Habits.suggest(listOf(at(1, 23, 50), at(2, 0, 10), at(3, 0, 0))))
    }

    @Test
    fun `several runs in one day count once`() {
        assertNull(Habits.suggest(listOf(at(1, 23, 0), at(1, 23, 10), at(1, 23, 20))))
    }
}

private class Table : ModeScheduleDao {
    val schedules = MutableStateFlow<List<ModeScheduleEntity>>(emptyList())
    val runs = mutableListOf<ModeRunEntity>()
    override suspend fun all() = schedules.value
    override fun observeAll() = schedules.asStateFlow()
    override suspend fun byId(id: String) = schedules.value.firstOrNull { it.id == id }
    override suspend fun upsert(schedule: ModeScheduleEntity) { schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule }
    override suspend fun delete(id: String) { schedules.value = schedules.value.filterNot { it.id == id } }
    override suspend fun deleteForMode(modeId: String) { schedules.value = schedules.value.filterNot { it.modeId == modeId } }
    override suspend fun logRun(run: ModeRunEntity) { runs += run }
    override suspend fun runsSince(modeId: String, since: Long) = runs.filter { it.modeId == modeId && it.at >= since }
    override suspend fun pruneRuns(before: Long) { runs.removeAll { it.at < before } }
}

private class Prefs : UserPreferenceDao {
    val rows = mutableMapOf<String, String>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun set(preference: UserPreferenceEntity) { rows[preference.key] = preference.value }
    override suspend fun withPrefix(prefix: String) = rows.filterKeys { it.startsWith(prefix) }.map { UserPreferenceEntity(it.key, it.value) }
    override suspend fun delete(key: String) { rows.remove(key) }
    override suspend fun deleteWithPrefix(prefix: String) { rows.keys.removeAll { it.startsWith(prefix) } }
}

private class Modes : ModeDao {
    val rows = MutableStateFlow<List<ModeEntity>>(emptyList())
    override suspend fun all() = rows.value
    override fun observeAll() = rows.asStateFlow()
    override suspend fun upsert(mode: ModeEntity) { rows.value = rows.value.filterNot { it.id == mode.id } + mode }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

private class Knobs : DeviceKnobs {
    var dndState = false
    override fun volumePercent() = 50
    override fun setVolumePercent(percent: Int) = Unit
    override fun dndAccess() = true
    override fun dndOn() = dndState
    override fun setDnd(on: Boolean) { dndState = on }
    override fun openDndAccessSettings() = Unit
    override fun ringer() = Ringer.NORMAL
    override fun setRinger(mode: Ringer) = Unit
    override fun canWriteSettings() = true
    override fun openWriteSettingsPermission() = Unit
    override fun brightnessPercent() = 50
    override fun setBrightnessPercent(percent: Int) = Unit
    override fun brightnessAuto() = false
    override fun setBrightnessAuto(auto: Boolean) = Unit
    override fun radioOn(radio: Radio): Boolean? = null
    override fun openPanel(radio: Radio) = Unit
}

class ModeScheduleEngineTest {

    private val table = Table()
    private val booked = mutableListOf<List<Schedule>>()
    private val schedules = ModeSchedules(table, Prefs(), onChanged = { now, _ -> booked += now }, zone = { ZoneOffset.UTC })
    private val store = ModeStore(Modes())
    private val knobs = Knobs()
    private var clock = LocalDateTime.of(2026, 10, 7, 23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val ran = mutableListOf<CommandResult>()
    private val runner: suspend (CommandResult) -> String = { ran += it; "Готово" }

    private fun e(tool: String, json: String) = ActionEnvelope(tool, Json.parseToJsonElement(json) as JsonObject)

    private val engine = ModeEngine(
        store,
        compile = { _, d ->
            when (d) {
                "не беспокоить" -> ModeCompiler.Result.Steps(listOf(e("phone_control", """{"target":"dnd","state":"on"}""")), emptyList())
                "напиши маме что я дома" -> ModeCompiler.Result.Steps(listOf(e("send_message", """{"contact":"мама","text":"я дома"}""")), emptyList())
                else -> ModeCompiler.Result.Failed("no_steps")
            }
        },
        device = DeviceController(knobs),
        clock = { clock },
        now = { LocalDateTime.of(2026, 10, 7, 12, 0) },
        schedules = schedules
    )

    private suspend fun say(text: String): String? {
        engine.answerPending(text, true)?.let { return it }
        val r = engine.route(text) ?: return null
        return engine.handle(r, true, runner = runner)
    }

    @Test
    fun `scheduling a mode by voice books it`() = runTest {
        say("создай режим отдыха: не беспокоить")
        assertEquals("Буду включать режим отдыха каждый день в 23:00.", say("включай режим отдыха каждый день в 23:00"))
        assertEquals(LocalTime.of(23, 0), schedules.all().single().time)
        assertEquals(1, booked.last().size)
        assertEquals("Буду выключать режим отдыха по будням в 7:00.", say("по будням в 7 утра выключай режим отдыха"))
        assertEquals(2, schedules.all().size)
        assertEquals("Режим отдыха больше не включается по расписанию.", say("убери расписание режима отдыха"))
        assertTrue(schedules.all().isEmpty())
        assertTrue(booked.last().isEmpty())
    }

    @Test
    fun `without a time, Friday asks`() = runTest {
        say("создай режим отдыха: не беспокоить")
        assertTrue(say("включай режим отдыха каждый день")!!.startsWith("Во сколько включать режим отдыха?"))
    }

    @Test
    fun `a mode that messages someone never starts on a timer`() = runTest {
        say("создай режим дома: напиши маме что я дома")
        assertTrue(say("включай режим дома каждый день в 19:00")!!.contains("не запускаю"))
        assertTrue(schedules.all().isEmpty())
    }

    @Test
    fun `firing runs the mode, and the exit schedule puts it back`() = runTest {
        say("создай режим отдыха: не беспокоить")
        say("включай режим отдыха каждый день в 23:00")
        say("каждый день в 7 утра выключай режим отдыха")
        val on = schedules.all().single { !it.exit }
        val off = schedules.all().single { it.exit }
        assertTrue(engine.fire(on.id, true, runner)!!.startsWith("Режим отдыха."))
        assertTrue(knobs.dndState)
        assertTrue(engine.fire(off.id, true, runner)!!.startsWith("Режим отдыха выключен."))
        assertTrue(!knobs.dndState)
        assertNull("already off: nothing to say", engine.fire(off.id, true, runner))
    }

    @Test
    fun `a habit is offered once, and yes schedules it`() = runTest {
        say("создай режим отдыха: не беспокоить")
        val day = 24 * 60 * 60 * 1000L
        say("режим отдыха"); say("выключи режим отдыха")
        clock += day
        say("режим отдыха"); say("выключи режим отдыха")
        clock += day
        val third = say("режим отдыха")!!
        assertTrue(third, third.endsWith("включать каждый день в 23:00 само?"))
        assertEquals("Буду включать режим отдыха каждый день в 23:00.", say("да"))
        say("выключи режим отдыха")
        clock += day
        assertTrue("asked once only", !say("режим отдыха")!!.contains("само?"))
    }

    @Test
    fun `deleting a mode removes its schedules`() = runTest {
        say("создай режим отдыха: не беспокоить")
        say("включай режим отдыха каждый день в 23:00")
        say("удали режим отдыха")
        assertTrue(schedules.all().isEmpty())
    }
}
