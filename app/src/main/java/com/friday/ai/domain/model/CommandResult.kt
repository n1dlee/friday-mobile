package com.friday.ai.domain.model

sealed interface CommandResult {

    /*
     * Three groups, so the code that carries commands out can be split by
     * what it touches and still have the compiler check every command is
     * handled: phone controls, the user's plans, and things to look up.
     * Analysis and conversation stay ungrouped — the chat and the voice loop
     * each handle those their own way.
     */

    /** Acts on the phone itself: apps, calls, settings, camera, playback. */
    sealed interface Phone : CommandResult

    /** Writes something down for later: calendar, errands, notes. */
    sealed interface Planner : CommandResult

    /** Fetches something to tell the user. */
    sealed interface Info : CommandResult

    /**
     * The owner's modes: created, run and undone by voice
     * ("создай режим грусти: …", "режим грусти", "выключи режим грусти").
     * [name] is as spoken; matching it to a saved mode is the engine's job.
     */
    sealed interface Mode : CommandResult {
        data class Create(val name: String, val description: String?) : Mode
        data class Run(val name: String) : Mode
        data class Exit(val name: String) : Mode
        data class Describe(val name: String) : Mode
        data class Delete(val name: String) : Mode
        data object ListAll : Mode
        /** "Отмена" right after creating one: the mode just made is removed. */
        data class CancelCreated(val id: String) : Mode
        /**
         * "Нет, включи lo-fi" right after a mode ran: [instead] is carried out,
         * then Friday offers to keep it in the mode.
         */
        data class Correct(val id: String, val instead: String) : Mode
        /** "Добавь в режим грусти тёплый свет": [rest] is the name followed by what to add. */
        data class AddTo(val rest: String) : Mode
        /** "Убери будильник из режима отдыха". */
        data class RemoveFrom(val what: String, val name: String) : Mode
        /** "Включай режим отдыха каждый день в 23:00"; no time yet means ask for one. [days]: Monday = bit 0. */
        data class Schedule(val id: String, val exit: Boolean, val hour: Int?, val minute: Int?, val days: Int) : Mode
        /** "Убери расписание режима отдыха". */
        data class Unschedule(val id: String) : Mode
        /**
         * "Включай режим вождения, когда подключаюсь к машине". [trigger] is
         * a [com.friday.ai.core.modes.Trigger] key; [target] is how the owner
         * named the device or network.
         */
        /** "Привяжи режим отдыха к метке": the next NFC tag held to the phone toggles the mode. */
        data class Tag(val id: String) : Mode

        data class OnEvent(
            val id: String,
            val trigger: String,
            val target: String,
            val onConnect: Boolean,
            val exit: Boolean
        ) : Mode
    }

    data class OpenApp(val appName: String, val packageHint: String?) : Phone
    data class CloseApp(val appName: String, val packageHint: String?) : Phone
    /** [via] is an app the user named ("по ватсапу"), or SMS for "по телефону" — the ordinary line. */
    data class PhoneCall(val target: String, val via: com.friday.ai.core.people.Channel? = null) : Phone
    /**
     * A message to someone. [via] is what the user named — an app, or "смс" —
     * and is a wish, not an order: the channel is decided from the person's
     * numbers, the user's country and what is installed.
     */
    data class SendMessage(
        val target: String,
        val body: String?,
        val via: com.friday.ai.core.people.Channel? = null
    ) : Phone
    /**
     * [date] is set only when the user named a day other than the one the
     * Clock app would ring on; it cannot take a date, so that is refused.
     */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String?,
        val date: java.time.LocalDate? = null
    ) : Phone
    data class SetTimer(val seconds: Int, val label: String?) : Phone
    /**
     * "Ответь маме, что еду": through the chat's notification, read back first.
     * [to] null or a pronoun means whoever wrote last; someone with no recent
     * chat gets an e-mail reply instead.
     */
    data class ReplyMessage(val to: String?, val body: String) : Phone

    /** "Что сейчас играет": the track and artist from the player. */
    data object NowPlaying : Phone

    /** "Фонарик" on its own: flip it. */
    data object ToggleFlashlight : Phone

    /** "Включи/выключи фонарик": the state asked for, whatever it is now. */
    data class Flashlight(val on: Boolean) : Phone

    /** Something to find and play: "включи Imagine Dragons", "найди на ютубе …". */
    data class PlayMedia(val query: String, val kind: MediaKind, val appHint: String?) : Phone
    data class WebSearch(val query: String) : Phone
    data class FindNearby(val query: String) : Phone
    data class DeviceControl(val action: DeviceAction, val level: Int? = null) : Phone
    /** A system settings screen, named by what the user said. */
    data class OpenSettings(val phrase: String) : Phone
    data class OpenCamera(val mode: CameraMode) : Phone
    data object RecordAudio : Phone
    /** [appHint] is the raw phrase; the player is resolved from it. */
    data class MediaControl(val action: MediaAction, val appHint: String?) : Phone

    data class CreateEvent(val title: String, val whenText: String) : Planner
    data class RescheduleEvent(val titleHint: String?, val whenText: String) : Planner
    /** Something to do when near the right kind of place. */
    data class CreateErrand(val what: String) : Planner
    data class CreateNote(val text: String) : Planner

    /** [dayOffset] 0 = now, 1 = tomorrow, 2 = day after. */
    data class Weather(val place: String?, val dayOffset: Int = 0) : Info
    data object WhatDidIMiss : Info

    /** "Который час": answered from the clock, not the model. */
    data object TellTime : Info

    /** "Прочитай сообщения", "что пишет мама": chats from messenger notifications. [from] null = all. */
    data class ReadMessages(val from: String?) : Info

    /** A question answered from the web: news, prices, who holds a post now, the latest of anything. */
    data class LookUp(val question: String) : Info
    data object MorningBrief : Info
    data class Mail(val request: com.friday.ai.core.mail.MailCommands.Request) : Info

    /** Several commands said in one sentence, carried out in order. */
    data class Sequence(val steps: List<CommandResult>) : CommandResult

    data object AnalyzeScreen : CommandResult
    data class AnalyzeFile(val fileHint: String?) : CommandResult
    data class ChatMessage(val text: String) : CommandResult
}

/**
 * Device settings Friday can touch.
 *
 * Wi-Fi and Bluetooth are deliberately "open the panel" rather than
 * "toggle": Android 10+ forbids apps from switching them programmatically,
 * so the honest behaviour is to put the user one tap away instead of
 * pretending the command worked.
 */
/** What is being looked for when the user asks to play something. */
enum class MediaKind { MUSIC, VIDEO }

/**
 * How the camera should open.
 *
 * All four open the camera app rather than capturing silently. Taking a photo
 * with no UI needs a foreground service declared with `camera` type, and from
 * Android 14 the system refuses to start one while the app is in the
 * background — which is exactly where Friday lives. Opening the camera already
 * framed on the right mode is what can actually be delivered.
 */
enum class CameraMode { PHOTO, VIDEO, SELFIE, JUST_OPEN }

/**
 * Transport controls for whatever is playing.
 *
 * [startsPlayback] marks the ones where launching a stopped app is a sensible
 * reading of the command — "включи Spotify" means start it, "останови" does
 * not.
 */
enum class MediaAction(val startsPlayback: Boolean) {
    PLAY(true),
    TOGGLE(true),
    PAUSE(false),
    STOP(false),
    NEXT(false),
    PREVIOUS(false)
}

/**
 * A change to the phone's own settings, named by what it achieves rather
 * than how: whether Wi-Fi can actually be switched or only its panel opened
 * is [com.friday.ai.core.DeviceController]'s business, and it says which.
 * Levels (volume, brightness) travel next to the action, in percent.
 */
enum class DeviceAction {
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_SET,
    MUTE,
    UNMUTE,
    DND_ON,
    DND_OFF,
    /** Ringer: sound, vibration only, or fully silent. */
    RINGER_NORMAL,
    RINGER_VIBRATE,
    RINGER_SILENT,
    BRIGHTNESS_SET,
    BRIGHTNESS_UP,
    BRIGHTNESS_DOWN,
    BRIGHTNESS_AUTO,
    WIFI_ON,
    WIFI_OFF,
    BLUETOOTH_ON,
    BLUETOOTH_OFF,
    /** "Wi-Fi" / "Bluetooth" without on or off: the settings, to look or choose. */
    OPEN_WIFI_PANEL,
    OPEN_BLUETOOTH_PANEL
}
