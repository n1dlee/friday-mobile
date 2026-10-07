@file:Suppress("MaxLineLength")

package com.friday.ai.core.modes

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.core.DeviceController
import com.friday.ai.core.DeviceKnobs
import com.friday.ai.core.Radio
import com.friday.ai.core.Ringer
import com.friday.ai.data.local.dao.ModeDao
import com.friday.ai.data.local.entity.ModeEntity
import com.friday.ai.domain.model.CommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTagsTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `a tag written here reads back as its mode`() {
        assertEquals(ModeTags.Read.Mode("m-1"), ModeTags.read(ModeTags.write("m-1", secret), secret))
    }

    @Test
    fun `another phone's secret, or a changed id, is forged`() {
        val tag = ModeTags.write("m-1", secret)
        assertEquals(ModeTags.Read.Forged, ModeTags.read(tag, ByteArray(32)))
        val swapped = String(tag).replace("m-1", "m-2").toByteArray()
        assertEquals(ModeTags.Read.Forged, ModeTags.read(swapped, secret))
    }

    @Test
    fun `anything else is not a tag`() {
        assertEquals(ModeTags.Read.NotATag, ModeTags.read("hello".toByteArray(), secret))
        assertEquals(ModeTags.Read.NotATag, ModeTags.read("""{"v":9,"id":"m","mac":"x"}""".toByteArray(), secret))
    }

    @Test
    fun `the tag names the mode by id, not by name`() {
        assertFalse(String(ModeTags.write("m-1", secret)).contains("отдых"))
    }
}

private class TagModes : ModeDao {
    val rows = MutableStateFlow<List<ModeEntity>>(emptyList())
    override suspend fun all() = rows.value
    override fun observeAll() = rows.asStateFlow()
    override suspend fun upsert(mode: ModeEntity) { rows.value = rows.value.filterNot { it.id == mode.id } + mode }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

private class TagKnobs : DeviceKnobs {
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

private class FakeNfc(var on: Boolean = true) : NfcTags {
    val writing = mutableListOf<String>()
    override fun available() = on
    override fun startWriting(modeId: String, modeName: String) { writing += modeName }
}

class ModeTagEngineTest {

    private val knobs = TagKnobs()
    private val nfc = FakeNfc()
    private val store = ModeStore(TagModes())
    private val runner: suspend (CommandResult) -> String = { "Готово" }

    private val engine = ModeEngine(
        store,
        compile = { _, _ ->
            ModeCompiler.Result.Steps(
                listOf(ActionEnvelope("phone_control", Json.parseToJsonElement("""{"target":"dnd","state":"on"}""") as JsonObject)),
                emptyList()
            )
        },
        device = DeviceController(knobs),
        nfc = nfc
    )

    private suspend fun say(text: String): String? = engine.route(text)?.let { engine.handle(it, true, runner = runner) }

    @Test
    fun `linking a mode to a tag opens the writer`() = runTest {
        say("создай режим отдыха: не беспокоить")
        assertTrue(say("привяжи режим отдыха к метке")!!.startsWith("Поднесите NFC-метку"))
        assertEquals(listOf("отдыха"), nfc.writing)
        assertTrue(say("сделай метку для режима отдыха")!!.startsWith("Поднесите"))
    }

    @Test
    fun `without nfc, it says so`() = runTest {
        nfc.on = false
        say("создай режим отдыха: не беспокоить")
        assertTrue(say("привяжи режим отдыха к метке")!!.startsWith("NFC выключен"))
        assertTrue(nfc.writing.isEmpty())
    }

    @Test
    fun `touching the tag toggles the mode`() = runTest {
        say("создай режим отдыха: не беспокоить")
        val id = store.all().single().id
        assertTrue(engine.onTag(id, true, runner).startsWith("Режим отдыха."))
        assertTrue(knobs.on)
        assertTrue(engine.onTag(id, true, runner).startsWith("Режим отдыха выключен."))
        assertFalse(knobs.on)
    }

    @Test
    fun `a tag for a deleted mode says so`() = runTest {
        assertEquals("Эта метка была для режима, которого больше нет.", engine.onTag("gone", true, runner))
    }
}
