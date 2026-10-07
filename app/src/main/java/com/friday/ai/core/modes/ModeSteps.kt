// Long lines on purpose: each reply is a Russian/English pair kept side by side.
@file:Suppress("MaxLineLength")

package com.friday.ai.core.modes

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.domain.model.DeviceAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A mode's steps in words, and what undoes the ones that aren't device
 * settings.
 *
 * Reading a mode back ("включу в Spotify грустные песни, «Не беспокоить»,
 * яркость 10 %") is how the owner checks that it was understood; the same
 * text is on the Modes screen.
 */
object ModeSteps {

    /** One step, in the owner's language. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // a phrase per tool, nothing more
    fun describe(e: ActionEnvelope, russian: Boolean): String {
        fun s(key: String) = (e.args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun say(ru: String, en: String) = if (russian) ru else en
        return when (e.tool) {
            "phone_control" -> device(s("target"), s("state"), s("level"), russian)
            "play" -> {
                val query = s("query").orEmpty()
                val app = s("app")?.let { say(" в $it", " in $it") }.orEmpty()
                say("включу «$query»$app", "play \"$query\"$app")
            }
            "media" -> when (s("action")) {
                "pause", "stop" -> say("поставлю музыку на паузу", "pause the music")
                "next" -> say("следующий трек", "next track")
                "previous" -> say("предыдущий трек", "previous track")
                else -> say("продолжу музыку", "resume the music")
            }
            "flashlight" -> when (s("state")) {
                "on" -> say("включу фонарик", "turn the flashlight on")
                "off" -> say("выключу фонарик", "turn the flashlight off")
                else -> say("переключу фонарик", "toggle the flashlight")
            }
            "set_alarm" -> {
                val time = "%s:%02d".format(s("hour") ?: "?", s("minute")?.toIntOrNull() ?: 0)
                say("будильник на $time", "alarm at $time")
            }
            "set_timer" -> {
                val minutes = (s("seconds")?.toIntOrNull() ?: 0) / SECONDS_PER_MINUTE
                say("таймер на $minutes мин", "a $minutes-minute timer")
            }
            "open_app" -> say("открою ${s("name")}", "open ${s("name")}")
            "weather" -> say("скажу погоду", "tell the weather")
            "briefing" -> say("утренняя сводка", "the morning briefing")
            "read_messages" -> say("прочитаю новые сообщения", "read new messages")
            "send_message" -> say("напишу ${s("contact")}: «${s("text").orEmpty()}»", "message ${s("contact")}: \"${s("text").orEmpty()}\"")
            "call" -> say("позвоню ${s("contact")}", "call ${s("contact")}")
            "open_settings" -> say("открою настройки «${s("screen")}»", "open ${s("screen")} settings")
            "find_nearby" -> say("найду рядом: ${s("query")}", "find nearby: ${s("query")}")
            "web_search" -> say("найду в интернете: ${s("question")}", "look up: ${s("question")}")
            "create_note" -> say("заметка «${s("text")}»", "a note \"${s("text")}\"")
            "camera" -> say("открою камеру", "open the camera")
            "voice_recorder" -> say("включу диктофон", "start the voice recorder")
            "run_mode" -> say("режим ${s("name")}", "${s("name")} mode")
            else -> e.tool
        }
    }

    private fun device(target: String?, state: String?, level: String?, russian: Boolean): String {
        fun say(ru: String, en: String) = if (russian) ru else en
        val on = state == "on"
        return when (target) {
            "volume" -> when {
                level != null -> say("громкость $level %", "volume $level%")
                state == "off" -> say("выключу звук", "mute")
                state == "up" -> say("громче", "louder")
                state == "down" -> say("тише", "quieter")
                else -> say("включу звук", "unmute")
            }
            "dnd" -> if (on) say("«Не беспокоить»", "Do Not Disturb") else say("выключу «Не беспокоить»", "Do Not Disturb off")
            "ringer" -> when (state) {
                "silent", "off" -> say("полностью беззвучно", "fully silent")
                "vibrate" -> say("только вибрация", "vibrate only")
                else -> say("звонок со звуком", "ringer on")
            }
            "brightness" -> when {
                level != null -> say("яркость $level %", "brightness $level%")
                state == "auto" -> say("автояркость", "auto-brightness")
                state == "up" -> say("ярче", "brighter")
                else -> say("темнее", "dimmer")
            }
            "wifi" -> if (on) say("включить Wi-Fi", "Wi-Fi on") else say("выключить Wi-Fi", "Wi-Fi off")
            "bluetooth" -> if (on) say("включить Bluetooth", "Bluetooth on") else say("выключить Bluetooth", "Bluetooth off")
            else -> say("настройка телефона", "a phone setting")
        }
    }

    /** All steps as one sentence: "включу «…» в Spotify, «Не беспокоить», яркость 10 %". */
    fun summary(steps: List<ActionEnvelope>, russian: Boolean): String =
        steps.joinToString(", ") { describe(it, russian) }

    /**
     * What undoes [step], when it isn't a device setting (those report their
     * own undo): music started by a mode is paused when it ends, a flashlight
     * it turned on is turned off. Null when there is nothing sensible to undo
     * (a message sent, an alarm set).
     */
    fun undoOf(step: ActionEnvelope): ActionEnvelope? {
        fun s(key: String) = (step.args[key] as? JsonPrimitive)?.contentOrNull
        return when {
            step.tool == "play" && s("kind") != "video" -> envelope("media") { put("action", "pause") }
            step.tool == "media" && s("action") == "play" -> envelope("media") { put("action", "pause") }
            step.tool == "flashlight" && s("state") == "on" -> envelope("flashlight") { put("state", "off") }
            else -> null
        }
    }

    /** The phone_control v2 envelope for a device action, as stored in a mode's undo list. */
    @Suppress("CyclomaticComplexMethod") // a mapping table
    fun deviceEnvelope(action: DeviceAction, level: Int?): ActionEnvelope {
        val (target, state) = when (action) {
            DeviceAction.VOLUME_UP -> "volume" to "up"
            DeviceAction.VOLUME_DOWN -> "volume" to "down"
            DeviceAction.VOLUME_SET -> "volume" to null
            DeviceAction.MUTE -> "volume" to "off"
            DeviceAction.UNMUTE -> "volume" to "on"
            DeviceAction.DND_ON -> "dnd" to "on"
            DeviceAction.DND_OFF -> "dnd" to "off"
            DeviceAction.RINGER_NORMAL -> "ringer" to "normal"
            DeviceAction.RINGER_VIBRATE -> "ringer" to "vibrate"
            DeviceAction.RINGER_SILENT -> "ringer" to "silent"
            DeviceAction.BRIGHTNESS_SET -> "brightness" to null
            DeviceAction.BRIGHTNESS_UP -> "brightness" to "up"
            DeviceAction.BRIGHTNESS_DOWN -> "brightness" to "down"
            DeviceAction.BRIGHTNESS_AUTO -> "brightness" to "auto"
            DeviceAction.WIFI_ON -> "wifi" to "on"
            DeviceAction.WIFI_OFF -> "wifi" to "off"
            DeviceAction.BLUETOOTH_ON -> "bluetooth" to "on"
            DeviceAction.BLUETOOTH_OFF -> "bluetooth" to "off"
            DeviceAction.OPEN_WIFI_PANEL -> "wifi" to null
            DeviceAction.OPEN_BLUETOOTH_PANEL -> "bluetooth" to null
        }
        return envelope("phone_control") {
            put("target", target)
            state?.let { put("state", it) }
            level?.let { put("level", it) }
        }
    }

    private fun envelope(tool: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ActionEnvelope(tool, buildJsonObject(args))

    private const val SECONDS_PER_MINUTE = 60
}
