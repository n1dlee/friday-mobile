package com.friday.ai.command

import com.friday.ai.core.AlarmRequest
import com.friday.ai.core.AlarmSetter
import com.friday.ai.core.AppLauncher
import com.friday.ai.core.DeviceController
import com.friday.ai.core.MediaControls
import com.friday.ai.core.MediaLauncher
import com.friday.ai.core.MediaSearch
import com.friday.ai.core.people.Caller
import com.friday.ai.core.people.Messenger
import com.friday.ai.service.messages.MessageAssistant
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.domain.model.CommandResult
import java.time.LocalDateTime
import java.time.LocalTime

/** Commands that operate the phone: apps, calls, settings, camera, playback. */
// One collaborator per kind of thing the phone does; grouping them would only hide that.
@Suppress("LongParameterList")
class PhoneActions(
    private val apps: AppLauncher,
    private val device: DeviceController,
    private val media: MediaLauncher,
    private val playback: MediaControls,
    private val prefDao: UserPreferenceDao,
    private val alarms: AlarmSetter,
    private val messenger: Messenger,
    private val mediaSearch: MediaSearch,
    private val caller: Caller,
    private val messages: MessageAssistant
) {

    suspend fun run(c: CommandResult.Phone, russian: Boolean): String = when (c) {
        is CommandResult.OpenApp -> apps.openApp(c.packageHint, c.appName)
        is CommandResult.CloseApp -> apps.closeApp(c.packageHint, c.appName)
        is CommandResult.PhoneCall -> caller.call(c.target, c.via, russian)
        is CommandResult.ReplyMessage -> messages.reply(c.to, c.body, russian)
        is CommandResult.SendMessage -> messenger.message(c.target, c.body, c.via, russian)
        is CommandResult.PlayMedia -> mediaSearch.play(c.query, c.kind, c.appHint, russian)
        is CommandResult.SetAlarm -> setAlarm(c, russian)
        is CommandResult.SetTimer -> apps.setTimer(c.seconds, c.label)
        is CommandResult.ToggleFlashlight -> apps.setFlashlight(null)
        is CommandResult.Flashlight -> apps.setFlashlight(c.on)
        is CommandResult.WebSearch -> apps.webSearch(c.query)
        is CommandResult.FindNearby -> apps.findNearby(c.query, prefDao.get("maps_provider") ?: "auto")
        is CommandResult.DeviceControl -> device.perform(c.action, c.level)
        is CommandResult.OpenSettings -> media.openSettings(c.phrase)
        is CommandResult.OpenCamera -> media.openCamera(c.mode)
        is CommandResult.RecordAudio -> media.recordAudio()
        is CommandResult.MediaControl -> playback.perform(c.action, c.appHint, russian)
        is CommandResult.NowPlaying -> playback.nowPlaying(russian)
    }

    private suspend fun setAlarm(c: CommandResult.SetAlarm, russian: Boolean): String {
        val time = LocalTime.of(c.hour, c.minute)
        // A day the Clock cannot ring on is refused, not quietly moved.
        c.date?.let { return AlarmRequest.wrongDay(AlarmRequest.Alarm(time, it), LocalDateTime.now(), russian) }
        return alarms.set(time, c.label, russian)
    }
}
