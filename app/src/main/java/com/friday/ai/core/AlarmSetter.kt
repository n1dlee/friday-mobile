package com.friday.ai.core

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Sets an alarm in the phone's Clock app and checks that it is really there.
 *
 * It used to open the Clock's edit screen and report "Setting alarm" at once.
 * With the phone face-down nobody pressed Save, so there was no alarm, while
 * Friday had already said there was. Now the alarm is created without a
 * screen, and the answer depends on what Android then reports.
 */
class AlarmSetter(
    private val context: Context,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {

    private companion object {
        const val TAG = "AlarmSetter"

        /** The Clock app works asynchronously; give it this long to register the alarm. */
        const val CHECK_EVERY_MS = 300L
        const val CHECK_FOR_MS = 3_000L
    }

    suspend fun set(time: LocalTime, label: String?, russian: Boolean): String {
        val expected = AlarmRequest.nextOccurrence(time, now()).atZone(zone()).toInstant().toEpochMilli()
        when (send(time, label, skipUi = true)) {
            Sent.NO_APP -> return if (russian) "На телефоне нет приложения Часы, которое принимает будильники."
            else "There's no Clock app that accepts alarms on this phone."
            Sent.REFUSED -> return if (russian) "Часы не разрешили Пятнице поставить будильник."
            else "The Clock app didn't allow Friday to set the alarm."
            Sent.OK -> Unit
        }

        var check = AlarmRequest.Check.MISSING
        var waited = 0L
        while (waited < CHECK_FOR_MS) {
            delay(CHECK_EVERY_MS)
            waited += CHECK_EVERY_MS
            check = AlarmRequest.check(expected, nextAlarm())
            if (check == AlarmRequest.Check.CONFIRMED) break
        }
        Log.i(TAG, "Alarm %02d:%02d → %s".format(time.hour, time.minute, check))

        // The Clock ignored the silent request: show its screen so it can be
        // saved by hand, rather than leave the user with nothing.
        if (check == AlarmRequest.Check.MISSING) send(time, label, skipUi = false)
        return AlarmRequest.reply(time, check, russian)
    }

    private fun nextAlarm(): Long? =
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime

    /** Not having a Clock app and being refused by it are different answers. */
    private enum class Sent { OK, NO_APP, REFUSED }

    private fun send(time: LocalTime, label: String?, skipUi: Boolean): Sent = try {
        context.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Sent.OK
    } catch (e: android.content.ActivityNotFoundException) {
        Log.e(TAG, "No Clock app for alarms: ${e.message}")
        Sent.NO_APP
    } catch (e: SecurityException) {
        Log.e(TAG, "Clock refused the alarm: ${e.message}")
        Sent.REFUSED
    }
}
