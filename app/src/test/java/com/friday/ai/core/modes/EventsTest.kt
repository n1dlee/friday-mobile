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
import com.friday.ai.data.local.entity.ModeEventEntity
import com.friday.ai.data.local.entity.ModeRunEntity
import com.friday.ai.data.local.entity.ModeScheduleEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.domain.model.CommandResult
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

class EventPhrasesTest {

    @Test
    fun `the car, either way round`() {
        val a = EventPhrases.parse("Пятница, включай режим вождения, когда подключаюсь к машине")!!
        assertEquals(EventPhrases.Request("вождения", Trigger.BLUETOOTH, "машине", onConnect = true, exit = false), a)
        val b = EventPhrases.parse("когда сажусь в машину — режим вождения")!!
        assertEquals(Trigger.BLUETOOTH, b.trigger)
        assertEquals("машину", b.target)
        assertEquals("вождения", b.rest)
    }

    @Test
    fun `disconnecting ends a mode`() {
        val r = EventPhrases.parse("когда отключаюсь от машины, выключай режим вождения")!!
        assertFalse(r.onConnect)
        assertTrue(r.exit)
        assertEquals("машины", r.target)
    }

    @Test
    fun `the charger`() {
        assertEquals(Trigger.CHARGER, EventPhrases.parse("когда ставлю телефон на зарядку, включай режим сна")!!.trigger)
        val off = EventPhrases.parse("когда снимаю с зарядки — выключай режим сна")!!
        assertEquals(Trigger.CHARGER, off.trigger)
        assertFalse(off.onConnect)
        assertTrue(off.exit)
    }

    @Test
    fun `wifi and coming home`() {
        val wifi = EventPhrases.parse("когда подключаюсь к домашнему вайфаю — режим дома")!!
        assertEquals(Trigger.WIFI, wifi.trigger)
        assertEquals("домашнему", wifi.target)
        assertEquals(Trigger.WIFI, EventPhrases.parse("когда прихожу домой включай режим дома")!!.trigger)
    }

    @Test
    fun `no when, no event`() {
        assertNull(EventPhrases.parse("включи режим вождения"))
        assertNull(EventPhrases.parse("подключись к машине"))
    }

    @Test
    fun `a paired device by its words`() {
        assertEquals("JBL Flip 6", EventPhrases.device("колонке jbl", listOf("Galaxy Buds", "JBL Flip 6")))
        assertNull(EventPhrases.device("машине", listOf("Toyota Touch", "Galaxy Buds")))
    }
}

private class EventTable : ModeScheduleDao {
    val schedules = MutableStateFlow<List<ModeScheduleEntity>>(emptyList())
    val events = MutableStateFlow<List<ModeEventEntity>>(emptyList())
    override suspend fun all() = schedules.value
    override fun observeAll() = schedules.asStateFlow()
    override suspend fun byId(id: String) = schedules.value.firstOrNull { it.id == id }
    override suspend fun upsert(schedule: ModeScheduleEntity) { schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule }
    override suspend fun delete(id: String) { schedules.value = schedules.value.filterNot { it.id == id } }
    override suspend fun deleteForMode(modeId: String) { schedules.value = schedules.value.filterNot { it.modeId == modeId } }
    override suspend fun events() = events.value
    override fun observeEvents() = events.asStateFlow()
    override suspend fun upsertEvent(event: ModeEventEntity) { events.value = events.value.filterNot { it.id == event.id } + event }
    override suspend fun deleteEvent(id: String) { events.value = events.value.filterNot { it.id == id } }
    override suspend fun deleteEventsForMode(modeId: String) { events.value = events.value.filterNot { it.modeId == modeId } }
    override suspend fun logRun(run: ModeRunEntity) = Unit
    override suspend fun runsSince(modeId: String, since: Long) = emptyList<ModeRunEntity>()
    override suspend fun pruneRuns(before: Long) = Unit
}

private class NoPrefs : UserPreferenceDao {
    override suspend fun get(key: String): String? = null
    override suspend fun set(preference: UserPreferenceEntity) = Unit
    override suspend fun withPrefix(prefix: String) = emptyList<UserPreferenceEntity>()
    override suspend fun delete(key: String) = Unit
    override suspend fun deleteWithPrefix(prefix: String) = Unit
}

private class ModesTable : ModeDao {
    val rows = MutableStateFlow<List<ModeEntity>>(emptyList())
    override suspend fun all() = rows.value
    override fun observeAll() = rows.asStateFlow()
    override suspend fun upsert(mode: ModeEntity) { rows.value = rows.value.filterNot { it.id == mode.id } + mode }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

private class Dnd : DeviceKnobs {
    var on = false
    override fun volumePercent() = 50
    override fun setVolumePercent(percent: Int) = Unit
    override fun dndAccess() = true
    override fun dndOn() = on
    override fun setDnd(on: Boolean) { this.on = on }
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

private class Links(var bt: PhoneLinks.Bluetooth, var ssid: String? = null) : PhoneLinks {
    override suspend fun bluetooth() = bt
    override suspend fun wifi() = ssid
}

class ModeEventEngineTest {

    private val dnd = Dnd()
    private val links = Links(PhoneLinks.Bluetooth(true, listOf("Toyota Touch", "Galaxy Buds"), listOf("Toyota Touch")))
    private val schedules = ModeSchedules(EventTable(), NoPrefs())
    private val store = ModeStore(ModesTable())
    private val ran = mutableListOf<CommandResult>()
    private val runner: suspend (CommandResult) -> String = { ran += it; "Играет" }

    private fun e(tool: String, json: String) = ActionEnvelope(tool, Json.parseToJsonElement(json) as JsonObject)

    private val engine = ModeEngine(
        store,
        compile = { _, d ->
            when (d) {
                "не беспокоить и музыка" -> ModeCompiler.Result.Steps(
                    listOf(e("phone_control", """{"target":"dnd","state":"on"}"""), e("play", """{"query":"drive","kind":"music"}""")), emptyList()
                )
                else -> ModeCompiler.Result.Failed("no_steps")
            }
        },
        device = DeviceController(dnd),
        schedules = schedules,
        links = links
    )

    private suspend fun say(text: String): String? {
        val r = engine.route(text) ?: return null
        return engine.handle(r, true, runner = runner)
    }

    @Test
    fun `an unknown target is the device connected right now, and said so`() = runTest {
        say("создай режим вождения: не беспокоить и музыка")
        val reply = say("включай режим вождения, когда подключаюсь к машине")!!
        assertEquals("Буду включать режим вождения при подключении к «Toyota Touch». Взяла то, что подключено сейчас: «Toyota Touch».", reply)
        assertEquals("Toyota Touch", schedules.events().single().value)
    }

    @Test
    fun `nothing connected and no name match means connect first`() = runTest {
        links.bt = PhoneLinks.Bluetooth(true, listOf("Galaxy Buds"), emptyList())
        say("создай режим вождения: не беспокоить и музыка")
        assertTrue(say("включай режим вождения, когда подключаюсь к машине")!!.startsWith("Не знаю, какое устройство"))
        assertTrue(schedules.events().isEmpty())
    }

    @Test
    fun `no bluetooth permission is said`() = runTest {
        links.bt = PhoneLinks.Bluetooth(false, emptyList(), emptyList())
        say("создай режим вождения: не беспокоить и музыка")
        assertTrue(say("включай режим вождения, когда подключаюсь к машине")!!.contains("Устройства поблизости"))
    }

    @Test
    fun `the car connecting runs the mode, disconnecting ends it`() = runTest {
        say("создай режим вождения: не беспокоить и музыка")
        say("включай режим вождения, когда подключаюсь к машине")
        say("когда отключаюсь от машины, выключай режим вождения")
        assertNull("another device does nothing", engine.onLink(Trigger.BLUETOOTH, "Galaxy Buds", true, true, runner))
        val on = engine.onLink(Trigger.BLUETOOTH, "Toyota Touch", true, true, runner)!!
        assertTrue(on, on.startsWith("Режим вождения."))
        assertTrue(dnd.on)
        val off = engine.onLink(Trigger.BLUETOOTH, "Toyota Touch", false, true, runner)!!
        assertTrue(off, off.startsWith("Режим вождения выключен."))
        assertFalse(dnd.on)
    }

    @Test
    fun `the charger needs no device`() = runTest {
        say("создай режим вождения: не беспокоить и музыка")
        assertEquals("Буду включать режим вождения, когда телефон ставят на зарядку.", say("когда ставлю на зарядку, включай режим вождения"))
        assertTrue(engine.onLink(Trigger.CHARGER, "", true, true, runner)!!.startsWith("Режим вождения."))
    }

    @Test
    fun `wifi uses the network the phone is on`() = runTest {
        links.ssid = "Home-5G"
        say("создай режим вождения: не беспокоить и музыка")
        assertEquals("Буду включать режим вождения при подключении к «Home-5G».", say("когда подключаюсь к домашнему вайфаю — режим вождения"))
        assertTrue(engine.onLink(Trigger.WIFI, "Home-5G", true, true, runner) != null)
        assertNull(engine.onLink(Trigger.WIFI, "Cafe", true, true, runner))
    }
}
