// Long lines on purpose: tool descriptions are prompt text sent to the model.
@file:Suppress("MaxLineLength")

package com.friday.ai.agent

import com.friday.ai.core.AlarmRequest
import com.friday.ai.core.capabilities.FridayCapabilities
import com.friday.ai.core.capabilities.ToolRequirements
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.data.remote.dto.FunctionSpec
import com.friday.ai.data.remote.dto.ToolDefinition
import com.friday.ai.domain.model.CameraMode
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import com.friday.ai.domain.model.MediaAction
import com.friday.ai.domain.model.MediaKind
import com.friday.ai.core.people.Channel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the model may do on the phone, and how its requests become commands.
 *
 * Every tool lands on a [CommandResult] the regex router could also have
 * produced, so a tool call is carried out by exactly the same code as a
 * recognised phrase — same checks, same honest replies. The only thing done
 * here directly is arithmetic.
 *
 * Descriptions are kept short on purpose: all of them go with every request,
 * and Groq counts them against the per-minute token limit.
 */
object AgentTools {

    /** What a tool call turned into. */
    sealed interface Call {
        data class Command(val command: CommandResult) : Call

        /** Answered on the spot, no command needed. */
        data class Answer(val text: String) : Call

        /** The call could not be used; [reason] goes back to the model so it can correct itself. */
        data class Invalid(val reason: String) : Call

        /** The model asked for the phone's tools: the light kit was the wrong guess. */
        data object Escalate : Call
    }

    private const val ISO_LOCAL = "local time as YYYY-MM-DDTHH:MM"

    // Declared before [definitions], which reads them while the object is initialised.
    /** phone_control v2: what to change and how, never how it is done. */
    private val deviceTargets = setOf("volume", "dnd", "ringer", "brightness", "wifi", "bluetooth")
    private val deviceStates = setOf("on", "off", "up", "down", "silent", "vibrate", "normal", "auto")

    private val mediaActions = mapOf(
        "play" to MediaAction.PLAY, "pause" to MediaAction.PAUSE, "stop" to MediaAction.STOP,
        "next" to MediaAction.NEXT, "previous" to MediaAction.PREVIOUS, "toggle" to MediaAction.TOGGLE
    )

    private val channels = mapOf("sms" to Channel.SMS, "whatsapp" to Channel.WHATSAPP, "telegram" to Channel.TELEGRAM)

    /** For calls, the ordinary phone line is [Channel.SMS]'s place. */
    private val callRoutes = mapOf("phone" to Channel.SMS, "whatsapp" to Channel.WHATSAPP, "telegram" to Channel.TELEGRAM)

    private val mediaKinds = mapOf("music" to MediaKind.MUSIC, "video" to MediaKind.VIDEO)

    private val cameraModes = mapOf(
        "photo" to CameraMode.PHOTO, "video" to CameraMode.VIDEO,
        "selfie" to CameraMode.SELFIE, "open" to CameraMode.JUST_OPEN
    )

    /** What a plain conversation needs: looking things up, arithmetic, and a way to ask for more. */
    private val LIGHT_TOOLS = setOf("web_search", "calculate")

    private const val MAX_DAY_OFFSET = 2
    private const val HOURS_PER_DAY = 24
    private const val MINUTES_PER_HOUR = 60
    private const val MAX_TIMER_SECONDS = HOURS_PER_DAY * MINUTES_PER_HOUR * 60
    private const val MAX_PERCENT = 100

    val definitions: List<ToolDefinition> = listOf(
        tool("set_alarm", "Set an alarm on the phone's Clock app.") {
            int("hour", "0-23", required = true)
            int("minute", "0-59", required = true)
            string("date", "YYYY-MM-DD, only if the user named a day")
            string("label", "optional label")
        },
        tool("set_timer", "Start a countdown timer.") {
            int("seconds", "duration in seconds", required = true)
            string("label", "optional label")
        },
        tool(
            "call",
            "Call someone. Friday picks an ordinary call or a WhatsApp/Telegram call from their number's country " +
                "and the apps they are on. The result says which and why."
        ) {
            string("contact", "name as the user said it, or a number", required = true)
            enum("via", setOf("phone", "whatsapp", "telegram"), description = "ONLY if the user named it")
        },
        tool(
            "send_message",
            "Message someone. Friday picks SMS, WhatsApp or Telegram from their number's country, the user's country and " +
                "the apps they use, and fills the text in; the user taps Send. The result says which and why."
        ) {
            string("contact", "name as the user said it (any form: 'маме', 'mom'), or a number", required = true)
            string("text", "the message; omit to just open the chat")
            enum("via", setOf("sms", "whatsapp", "telegram"), description = "ONLY if the user named it")
        },
        tool("read_messages", "Read out new messages from WhatsApp, Telegram, SMS and other chats.") {
            string("from", "whose messages, as the user said it; omit for all")
        },
        tool(
            "reply_message",
            "Reply in a chat that recently got a message (WhatsApp, Telegram, SMS). Friday reads it back and sends on " +
                "the user's yes. Omit 'to' to answer whoever wrote last."
        ) {
            string("to", "who, as the user said it")
            string("text", "the reply", required = true)
        },
        tool("create_event", "Add a calendar event.") {
            string("title", "what it is", required = true)
            string("start", ISO_LOCAL, required = true)
        },
        tool("move_event", "Move an existing calendar event to a new time.") {
            string("title", "words from the event's title, if known")
            string("start", "new $ISO_LOCAL", required = true)
        },
        tool("create_note", "Save a note.") {
            string("text", "the note", required = true)
        },
        tool("remind_near_place", "Remind the user of an errand when they are near a fitting place (shop, pharmacy).") {
            string("what", "the errand, e.g. 'купить молоко'", required = true)
        },
        tool(
            "web_search",
            "Look something up on the web. Use it for anything that changes or that you are not certain of: news, " +
                "prices and rates, scores, who holds a post, the newest version of anything, facts about real people, " +
                "titles and dates. Your own knowledge is out of date."
        ) {
            string("question", "the question, self-contained, e.g. 'latest iPhone model'", required = true)
        },
        tool("weather", "Real weather. Never guess weather without this.") {
            string("place", "city; omit for the user's home city")
            int("day_offset", "0 today, 1 tomorrow, 2 day after")
        },
        tool("open_app", "Open an app by name. For the camera use camera, for recording voice_recorder.") {
            string("name", "app name", required = true)
        },
        tool("flashlight", "Turn the flashlight on or off.") {
            enum("state", setOf("on", "off", "toggle"), required = true)
        },
        tool(
            "phone_control",
            "Change a phone setting. volume: level, up/down, off=mute, on=unmute. dnd: on/off. ringer: normal/vibrate/silent. " +
                "brightness: level, up/down, auto. wifi/bluetooth: on/off (Android may only open their panel; the result says). " +
                "The result says what really happened."
        ) {
            enum("target", deviceTargets, required = true)
            enum("state", deviceStates)
            int("level", "0-100, for volume or brightness")
        },
        tool(
            "play",
            "Find and play a song, artist, album, playlist or video. Friday picks the app the user uses unless one is named."
        ) {
            string("query", "what to play, e.g. 'Imagine Dragons Believer'", required = true)
            enum("kind", setOf("music", "video"), required = true)
            string("app", "app named by the user, e.g. Spotify, YouTube")
        },
        tool("media", "Pause, resume, skip or stop what is already playing.") {
            enum("action", mediaActions.keys, required = true)
            string("app", "player named by the user, e.g. Spotify")
        },
        tool("open_settings", "Open a system settings screen.") {
            string("screen", "what the user called it, e.g. 'NFC', 'экран'", required = true)
        },
        tool("camera", "Open the camera ready for a photo, video or selfie; the user presses the shutter.") {
            enum("mode", cameraModes.keys, required = true)
        },
        tool("voice_recorder", "Open the voice recorder.") {},
        tool("find_nearby", "Show places nearby on the map.") {
            string("query", "what to find, e.g. 'аптека'", required = true)
        },
        tool("mail", "Gmail. 'send' and 'reply' only prepare the letter; the user confirms before it goes.") {
            enum("action", setOf("check", "read", "send", "reply"), required = true)
            string("person", "sender for read; recipient for send/reply")
            string("text", "letter text for send/reply")
        },
        tool("briefing", "The morning briefing, or what the user missed (notifications).") {
            enum("kind", setOf("morning", "missed"), required = true)
        },
        tool("calculate", "Exact arithmetic. Use it for any calculation instead of working it out yourself. Write percentages as multiplication (15% of 200 = 0.15*200).") {
            string("expression", "e.g. (17*23+4)/2, 2^10, sqrt(2)", required = true)
        }
    )

    /** Asks for the full set; offered only with the light kit. */
    private val escalate = tool(
        ToolKit.ESCALATE,
        "Call this FIRST if the user wants anything done on the phone — alarms, timers, calls, messages, music, " +
            "volume, flashlight, camera, apps, settings, calendar, notes, reminders, weather, mail. " +
            "The phone's tools are then given to you. Never say you cannot do a phone action without calling this."
    ) {}

    /**
     * The tools for [kit]; the light one is a few hundred tokens instead of
     * two thousand. With [capabilities], tools that can't work right now
     * (Gmail not connected, no notification access…) are left out entirely.
     */
    fun definitions(kit: ToolKit.Kit, capabilities: FridayCapabilities? = null): List<ToolDefinition> {
        val kitTools = when (kit) {
            ToolKit.Kit.FULL -> definitions
            ToolKit.Kit.LIGHT -> definitions.filter { it.function.name in LIGHT_TOOLS } + escalate
        }
        return kitTools.filter { ToolRequirements.met(it.function.name, capabilities) }
    }

    /**
     * Turns the model's call into something to do. Arguments are checked here
     * rather than trusted: a wrong one goes back as [Call.Invalid] so the
     * model can fix it, instead of setting an alarm for hour 31.
     */
    fun interpret(name: String, args: JsonObject, now: LocalDateTime): Call = try {
        val a = Args(args)
        when (name) {
            "set_alarm" -> alarm(a, now)
            "set_timer" -> {
                val seconds = a.int("seconds")
                require(seconds in 1..MAX_TIMER_SECONDS) { "seconds must be 1..$MAX_TIMER_SECONDS" }
                command(CommandResult.SetTimer(seconds, a.optString("label")))
            }
            "call" -> command(CommandResult.PhoneCall(a.string("contact"), a.optChoice("via", callRoutes)))
            "send_message" -> command(
                CommandResult.SendMessage(a.string("contact"), a.optString("text"), a.optChoice("via", channels))
            )
            "play" -> command(
                CommandResult.PlayMedia(a.string("query"), a.choice("kind", mediaKinds), a.optString("app"))
            )
            "create_event" -> command(CommandResult.CreateEvent(a.string("title"), a.dateTime("start").toString()))
            "move_event" -> command(CommandResult.RescheduleEvent(a.optString("title"), a.dateTime("start").toString()))
            "create_note" -> command(CommandResult.CreateNote(a.string("text")))
            "read_messages" -> command(CommandResult.ReadMessages(a.optString("from")))
            "reply_message" -> command(CommandResult.ReplyMessage(a.optString("to"), a.string("text")))
            "web_search" -> command(CommandResult.LookUp(a.string("question")))
            "remind_near_place" -> command(CommandResult.CreateErrand(a.string("what")))
            "weather" -> command(
                CommandResult.Weather(a.optString("place"), (a.optInt("day_offset") ?: 0).coerceIn(0, MAX_DAY_OFFSET))
            )
            "open_app" -> command(CommandResult.OpenApp(a.string("name"), packageHint = null))
            "flashlight" -> command(
                when (a.optString("state")?.lowercase()) {
                    "on" -> CommandResult.Flashlight(on = true)
                    "off" -> CommandResult.Flashlight(on = false)
                    else -> CommandResult.ToggleFlashlight
                }
            )
            "phone_control" -> command(device(a.string("target"), a.optString("state"), a.optInt("level")))
            "media" -> command(CommandResult.MediaControl(a.choice("action", mediaActions), a.optString("app")))
            "open_settings" -> command(CommandResult.OpenSettings(a.string("screen")))
            "camera" -> command(CommandResult.OpenCamera(a.choice("mode", cameraModes)))
            "voice_recorder" -> command(CommandResult.RecordAudio)
            "find_nearby" -> command(CommandResult.FindNearby(a.string("query")))
            "mail" -> command(CommandResult.Mail(mail(a)))
            "briefing" -> command(
                if (a.string("kind") == "missed") CommandResult.WhatDidIMiss else CommandResult.MorningBrief
            )
            "calculate" -> {
                val expression = a.string("expression")
                Call.Answer("$expression = ${Calculator.format(Calculator.evaluate(expression))}")
            }
            ToolKit.ESCALATE -> Call.Escalate
            else -> Call.Invalid("there is no tool called '$name'")
        }
    } catch (e: IllegalArgumentException) {
        Call.Invalid(e.message ?: "bad arguments")
    }

    private fun command(c: CommandResult) = Call.Command(c)

    /** phone_control v2 → the action; a combination that means nothing is refused with the reason. */
    private fun device(target: String, state: String?, rawLevel: Int?): CommandResult.DeviceControl {
        val level = rawLevel?.coerceIn(0, MAX_PERCENT)
        fun c(action: DeviceAction, l: Int? = null) = CommandResult.DeviceControl(action, l)
        val s = state?.lowercase()
        fun invalid(reason: String): Nothing = throw IllegalArgumentException(reason)
        return when (target.lowercase()) {
            "volume" -> when {
                level != null -> c(DeviceAction.VOLUME_SET, level)
                s == "up" -> c(DeviceAction.VOLUME_UP)
                s == "down" -> c(DeviceAction.VOLUME_DOWN)
                s == "off" -> c(DeviceAction.MUTE)
                s == "on" -> c(DeviceAction.UNMUTE)
                else -> invalid("volume needs a level or state up/down/on/off")
            }
            "dnd" -> when (s) {
                "on" -> c(DeviceAction.DND_ON)
                "off" -> c(DeviceAction.DND_OFF)
                else -> invalid("dnd needs state on or off")
            }
            "ringer" -> when (s) {
                "normal", "on" -> c(DeviceAction.RINGER_NORMAL)
                "vibrate" -> c(DeviceAction.RINGER_VIBRATE)
                "silent", "off" -> c(DeviceAction.RINGER_SILENT)
                else -> invalid("ringer needs state normal, vibrate or silent")
            }
            "brightness" -> when {
                level != null -> c(DeviceAction.BRIGHTNESS_SET, level)
                s == "up" -> c(DeviceAction.BRIGHTNESS_UP)
                s == "down" -> c(DeviceAction.BRIGHTNESS_DOWN)
                s == "auto" -> c(DeviceAction.BRIGHTNESS_AUTO)
                else -> invalid("brightness needs a level or state up/down/auto")
            }
            "wifi" -> c(
                when (s) {
                    "on" -> DeviceAction.WIFI_ON
                    "off" -> DeviceAction.WIFI_OFF
                    else -> DeviceAction.OPEN_WIFI_PANEL
                }
            )
            "bluetooth" -> c(
                when (s) {
                    "on" -> DeviceAction.BLUETOOTH_ON
                    "off" -> DeviceAction.BLUETOOTH_OFF
                    else -> DeviceAction.OPEN_BLUETOOTH_PANEL
                }
            )
            else -> invalid("target must be one of ${deviceTargets.joinToString()}")
        }
    }

    private fun alarm(a: Args, now: LocalDateTime): Call {
        val hour = a.int("hour")
        val minute = a.int("minute")
        require(hour in 0 until HOURS_PER_DAY && minute in 0 until MINUTES_PER_HOUR) { "no such time $hour:$minute" }
        val time = LocalTime.of(hour, minute)
        // Same rule as a spoken request: a date is kept only when it is not
        // the day the Clock app would ring on anyway, and is then refused.
        val ringsOn = AlarmRequest.nextOccurrence(time, now).toLocalDate()
        val date = a.optDate("date")?.takeIf { it != ringsOn }
        return command(CommandResult.SetAlarm(hour, minute, a.optString("label"), date))
    }

    private fun mail(a: Args): MailCommands.Request = when (a.string("action")) {
        "check" -> MailCommands.Request.CheckUnread
        "read" -> MailCommands.Request.Read(a.optString("person"))
        "send" -> MailCommands.Request.Compose(a.string("person"), a.string("text"))
        "reply" -> MailCommands.Request.Reply(a.string("person"), a.string("text"))
        else -> throw IllegalArgumentException("mail action must be check, read, send or reply")
    }

    /** Typed access to the arguments; anything missing or malformed is an [IllegalArgumentException]. */
    private class Args(private val o: JsonObject) {
        private fun raw(key: String): String? =
            (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        fun optString(key: String): String? = raw(key)
        fun string(key: String): String = raw(key) ?: throw IllegalArgumentException("'$key' is required")

        fun optInt(key: String): Int? = raw(key)?.let {
            it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt()
                ?: throw IllegalArgumentException("'$key' must be a whole number")
        }
        fun int(key: String): Int = optInt(key) ?: throw IllegalArgumentException("'$key' is required")

        fun optDate(key: String): LocalDate? = raw(key)?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("'$key' must be YYYY-MM-DD")
            }
        }

        fun dateTime(key: String): LocalDateTime = string(key).let {
            try {
                LocalDateTime.parse(it.replace(' ', 'T').take(ISO_MINUTES_LENGTH))
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("'$key' must be YYYY-MM-DDTHH:MM")
            }
        }

        fun <T> optChoice(key: String, options: Map<String, T>): T? = raw(key)?.let { choice(key, options) }

        fun <T> choice(key: String, options: Map<String, T>): T =
            options[string(key).lowercase()]
                ?: throw IllegalArgumentException("'$key' must be one of ${options.keys.joinToString()}")

        private companion object {
            /** "2026-10-07T08:00" — seconds and zones the model adds are dropped. */
            const val ISO_MINUTES_LENGTH = 16
        }
    }

    // --- schema building -------------------------------------------------------

    private class Schema {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<String>()

        fun string(name: String, description: String, required: Boolean = false) =
            prop(name, required) { type("string", required); put("description", description) }

        fun int(name: String, description: String, required: Boolean = false) =
            prop(name, required) { type("integer", required); put("description", description) }

        fun enum(name: String, values: Set<String>, required: Boolean = false, description: String? = null) =
            prop(name, required) {
                type("string", required)
                putJsonArray("enum") {
                    values.forEach { add(it) }
                    if (!required) add(JsonNull)
                }
                description?.let { put("description", it) }
            }

        /**
         * An optional field also accepts null. The model writes `"from": null`
         * for "all of them" as often as it leaves the field out, and Groq
         * rejects the whole call when the schema says only "string" — "что мне
         * пишут?" failed that way.
         */
        private fun kotlinx.serialization.json.JsonObjectBuilder.type(base: String, required: Boolean) {
            if (required) put("type", base) else putJsonArray("type") { add(base); add("null") }
        }

        private fun prop(
            name: String,
            isRequired: Boolean,
            body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
        ) {
            properties[name] = buildJsonObject(body)
            if (isRequired) required += name
        }
    }

    private fun tool(name: String, description: String, params: Schema.() -> Unit): ToolDefinition {
        val schema = Schema().apply(params)
        val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { schema.properties.forEach { (k, v) -> put(k, v) } }
            if (schema.required.isNotEmpty()) putJsonArray("required") { schema.required.forEach { add(it) } }
        }
        return ToolDefinition(function = FunctionSpec(name, description, parameters))
    }
}
