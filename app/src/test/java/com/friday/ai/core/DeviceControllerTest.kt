@file:Suppress("MaxLineLength")

package com.friday.ai.core

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.agent.ActionSchema
import com.friday.ai.agent.AgentTools
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone's settings in memory, with the same rules Android applies. */
private class FakeKnobs : DeviceKnobs {
    var volume = 60
    var dndGranted = true
    var dndState = false
    var ringerMode = Ringer.NORMAL
    var canWrite = true
    var brightness = 40
    var auto = true
    var wifi: Boolean? = true
    var bluetooth: Boolean? = false
    val opened = mutableListOf<String>()
    /** Simulates a vendor that ignores the request. */
    var ignoreWrites = false

    override fun volumePercent() = volume
    override fun setVolumePercent(percent: Int) { if (!ignoreWrites) volume = percent }
    override fun dndAccess() = dndGranted
    override fun dndOn() = dndState
    override fun setDnd(on: Boolean) { if (!ignoreWrites) dndState = on }
    override fun openDndAccessSettings() { opened += "dnd_access" }
    override fun ringer() = ringerMode
    override fun setRinger(mode: Ringer) {
        if (mode == Ringer.SILENT && !dndGranted) throw SecurityException("Not allowed to change Do Not Disturb state")
        if (!ignoreWrites) ringerMode = mode
    }
    override fun canWriteSettings() = canWrite
    override fun openWriteSettingsPermission() { opened += "write_settings" }
    override fun brightnessPercent() = brightness
    override fun setBrightnessPercent(percent: Int) { if (!ignoreWrites) brightness = percent }
    override fun brightnessAuto() = auto
    override fun setBrightnessAuto(auto: Boolean) { if (!ignoreWrites) this.auto = auto }
    override fun radioOn(radio: Radio) = if (radio == Radio.WIFI) wifi else bluetooth
    override fun openPanel(radio: Radio) { opened += radio.name }
}

class DeviceControllerTest {

    private val knobs = FakeKnobs()
    private val controller = DeviceController(knobs)

    private fun run(action: DeviceAction, level: Int? = null) = controller.apply(action, level, russian = true)

    /** Applies [result]'s undo, as a mode's exit does. */
    private fun undo(result: DeviceActionResult) {
        val u = result.undo ?: error("no undo")
        controller.apply(u.action, u.level, russian = true)
    }

    @Test
    fun `volume is set, read back, and its undo restores the old level`() {
        val r = run(DeviceAction.VOLUME_SET, 25)
        assertTrue(r.succeeded)
        assertEquals("Громкость 25%", r.message)
        undo(r)
        assertEquals(60, knobs.volume)
    }

    @Test
    fun `a change the phone ignored is not reported as done`() {
        knobs.ignoreWrites = true
        val r = run(DeviceAction.MUTE)
        assertFalse(r.succeeded)
        assertEquals("Громкость не изменилась", r.message)
        assertNull("nothing changed, nothing to undo", r.undo)
    }

    @Test
    fun `do not disturb without access opens the grant screen and says so`() {
        knobs.dndGranted = false
        val r = run(DeviceAction.DND_ON)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, r.mode)
        assertEquals(listOf("dnd_access"), knobs.opened)
        assertFalse(knobs.dndState)
    }

    @Test
    fun `do not disturb already on is said, and leaves nothing to undo`() {
        knobs.dndState = true
        val r = run(DeviceAction.DND_ON)
        assertTrue(r.succeeded)
        assertTrue(r.message.contains("уже"))
        assertNull(r.undo)
    }

    @Test
    fun `do not disturb undo turns it back off`() {
        val r = run(DeviceAction.DND_ON)
        assertTrue(knobs.dndState)
        undo(r)
        assertFalse(knobs.dndState)
    }

    @Test
    fun `fully silent needs do not disturb access, and asks for it instead of failing`() {
        knobs.dndGranted = false
        val r = run(DeviceAction.RINGER_SILENT)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, r.mode)
        assertEquals(Ringer.NORMAL, knobs.ringerMode)
    }

    @Test
    fun `vibrate needs no special access and undoes to the ringer it replaced`() {
        knobs.dndGranted = false
        val r = run(DeviceAction.RINGER_VIBRATE)
        assertTrue(r.succeeded)
        assertEquals("Только вибрация", r.message)
        undo(r)
        assertEquals(Ringer.NORMAL, knobs.ringerMode)
    }

    @Test
    fun `brightness turns off auto, and the undo turns auto back on`() {
        val r = run(DeviceAction.BRIGHTNESS_SET, 10)
        assertTrue(r.succeeded)
        assertFalse(knobs.auto)
        assertEquals(10, knobs.brightness)
        undo(r)
        assertTrue(knobs.auto)
    }

    @Test
    fun `manual brightness undoes to the previous level`() {
        knobs.auto = false
        val r = run(DeviceAction.BRIGHTNESS_UP)
        assertEquals(40 + DeviceController.BRIGHTNESS_STEP, knobs.brightness)
        undo(r)
        assertEquals(40, knobs.brightness)
    }

    @Test
    fun `brightness without the system-settings grant opens that screen`() {
        knobs.canWrite = false
        val r = run(DeviceAction.BRIGHTNESS_SET, 80)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, r.mode)
        assertEquals(listOf("write_settings"), knobs.opened)
    }

    @Test
    fun `wifi already in the asked state is said, no panel`() {
        val r = run(DeviceAction.WIFI_ON)
        assertTrue(r.succeeded)
        assertEquals("Wi-Fi уже включён", r.message)
        assertTrue(knobs.opened.isEmpty())
    }

    @Test
    fun `wifi off opens the panel and does not claim it is off`() {
        val r = run(DeviceAction.WIFI_OFF)
        assertEquals(DeviceActionResult.Mode.OPENED_SYSTEM_UI, r.mode)
        assertNull(r.verified)
        assertFalse(r.succeeded)
        assertEquals(listOf("WIFI"), knobs.opened)
        assertTrue(r.message.contains("осталось выключить"))
    }

    @Test
    fun `english replies for english requests`() {
        assertEquals("Volume 30%", controller.apply(DeviceAction.VOLUME_SET, 30, russian = false).message)
    }
}

class ActionSchemaTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)

    private fun commandOf(e: ActionEnvelope) =
        (AgentTools.interpret(e.tool, e.args, now) as AgentTools.Call.Command).command

    @Test
    fun `a learned command stored by 0_9 still runs after the upgrade`() {
        // Exactly what LearnedCommands wrote before envelopes had a version.
        val v1 = """{"tool":"phone_control","args":{"action":"volume_set","level":30}}"""
        val e = ActionSchema.parse(v1)!!
        assertEquals(ActionSchema.CURRENT, e.version)
        assertEquals(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 30), commandOf(e))
    }

    @Test
    fun `every v1 phone_control value has the same meaning in v2`() {
        val expected = mapOf(
            "volume_up" to DeviceAction.VOLUME_UP, "volume_down" to DeviceAction.VOLUME_DOWN,
            "mute" to DeviceAction.MUTE, "unmute" to DeviceAction.UNMUTE,
            "dnd_on" to DeviceAction.DND_ON, "dnd_off" to DeviceAction.DND_OFF,
            "wifi_panel" to DeviceAction.OPEN_WIFI_PANEL, "bluetooth_panel" to DeviceAction.OPEN_BLUETOOTH_PANEL
        )
        expected.forEach { (v1, action) ->
            val e = ActionSchema.parse("""{"tool":"phone_control","args":{"action":"$v1"}}""")!!
            assertEquals(v1, CommandResult.DeviceControl(action), commandOf(e))
        }
    }

    @Test
    fun `other tools carry over unchanged`() {
        val e = ActionSchema.parse("""{"tool":"flashlight","args":{"state":"on"}}""")!!
        assertEquals(2, e.version)
        assertEquals(CommandResult.Flashlight(on = true), commandOf(e))
    }

    @Test
    fun `what can't be migrated is dropped, not guessed`() {
        assertNull(ActionSchema.parse("""{"tool":"phone_control","args":{"action":"reboot"}}"""))
        assertNull(ActionSchema.parse("""{"tool":"phone_control","args":{"action":"volume_set"}}"""))
        assertNull(ActionSchema.parse("""{"version":99,"tool":"flashlight","args":{}}"""))
        assertNull(ActionSchema.parse("not json"))
    }

    @Test
    fun `an envelope survives its own round trip`() {
        val e = ActionEnvelope("phone_control", JsonObject(mapOf("target" to JsonPrimitive("dnd"), "state" to JsonPrimitive("on"))))
        assertEquals(e, ActionSchema.parse(e.toJson()))
    }

    @Test
    fun `v2 phone_control covers ringer, brightness and radios`() {
        fun c(json: String) = (AgentTools.interpret("phone_control", kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject, now) as AgentTools.Call.Command).command
        assertEquals(CommandResult.DeviceControl(DeviceAction.RINGER_SILENT), c("""{"target":"ringer","state":"silent"}"""))
        assertEquals(CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 20), c("""{"target":"brightness","level":20}"""))
        assertEquals(CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_AUTO), c("""{"target":"brightness","state":"auto"}"""))
        assertEquals(CommandResult.DeviceControl(DeviceAction.WIFI_OFF), c("""{"target":"wifi","state":"off"}"""))
        assertTrue(AgentTools.interpret("phone_control", kotlinx.serialization.json.Json.parseToJsonElement("""{"target":"dnd"}""") as JsonObject, now) is AgentTools.Call.Invalid)
    }
}

class DeviceRoutingTest {

    private val router = CommandRouter()

    private fun action(text: String) = (router.route(text) as? CommandResult.DeviceControl)?.action

    @Test
    fun `ringer phrases reach the ringer, not the media volume`() {
        assertEquals(DeviceAction.RINGER_SILENT, action("включи беззвучный режим"))
        assertEquals(DeviceAction.RINGER_SILENT, action("поставь телефон на беззвучный"))
        assertEquals(DeviceAction.RINGER_VIBRATE, action("поставь на вибрацию"))
        assertEquals(DeviceAction.RINGER_NORMAL, action("выключи беззвучный режим"))
        assertEquals(DeviceAction.RINGER_NORMAL, action("отключи вибрацию"))
        assertEquals(DeviceAction.MUTE, action("выключи звук"))
    }

    @Test
    fun `brightness by number, direction and auto`() {
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 30),
            router.route("яркость на 30")
        )
        assertEquals(DeviceAction.BRIGHTNESS_UP, action("сделай ярче"))
        assertEquals(DeviceAction.BRIGHTNESS_DOWN, action("убавь яркость"))
        assertEquals(DeviceAction.BRIGHTNESS_AUTO, action("включи автояркость"))
        assertEquals(CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 100), router.route("яркость на максимум"))
    }

    @Test
    fun `radios on, off, or just their settings`() {
        assertEquals(DeviceAction.WIFI_OFF, action("выключи вайфай"))
        assertEquals(DeviceAction.WIFI_ON, action("включи wi-fi"))
        assertEquals(DeviceAction.BLUETOOTH_OFF, action("отключи блютуз"))
        assertEquals(DeviceAction.OPEN_BLUETOOTH_PANEL, action("bluetooth"))
    }
}
