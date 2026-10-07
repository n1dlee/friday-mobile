package com.friday.ai.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.modes.ModeEngine
import com.friday.ai.core.modes.ModeSchedules
import com.friday.ai.core.modes.Schedule
import com.friday.ai.core.modes.ScheduleMath
import com.friday.ai.domain.model.CommandResult
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns mode schedules into system alarms.
 *
 * Each schedule gets one alarm for its next time; when it goes off,
 * [ModeAlarmReceiver] runs the mode and books the next one. Exact alarms
 * are used when Android allows them, otherwise a window of a few minutes,
 * which needs no special permission and is fine for "режим отдыха в 23:00".
 * Alarms don't survive a reboot or a clock change, so those re-book all.
 */
class ModeAlarms(private val context: Context) {

    companion object {
        private const val TAG = "ModeAlarms"
        const val ACTION_FIRE = "com.friday.ai.MODE_SCHEDULE"
        const val EXTRA_SCHEDULE = "schedule"
        private const val WINDOW_MS = 5 * 60 * 1000L
    }

    private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Cancels what was booked before and books every schedule's next time. */
    fun rebook(schedules: List<Schedule>, previous: Set<String>) {
        (previous - schedules.map { it.id }.toSet()).forEach { alarms.cancel(intent(it)) }
        schedules.forEach(::book)
    }

    fun book(s: Schedule) {
        val at = ScheduleMath.next(s, LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = intent(s.id)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
        }
        Log.i(TAG, "Booked ${s.id} (exit=${s.exit}) for $at, exact=$exact")
    }

    private fun intent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ModeAlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_SCHEDULE, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * A schedule's time came: run (or end) the mode, say quietly what happened,
 * book the next time. Also re-books everything after a reboot or a clock change.
 */
class ModeAlarmReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "ModeAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schedules: ModeSchedules = org.koin.java.KoinJavaComponent.get(ModeSchedules::class.java)
                when (intent.action) {
                    ModeAlarms.ACTION_FIRE ->
                        intent.getStringExtra(ModeAlarms.EXTRA_SCHEDULE)?.let { fire(context, it, schedules) }
                    // Boot, clock or time zone change: alarms are gone or wrong.
                    else -> rebookAll(context, schedules)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Schedule handling failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, id: String, schedules: ModeSchedules) {
        val engine: ModeEngine = org.koin.java.KoinJavaComponent.get(ModeEngine::class.java)
        val commands: CommandExecutor = org.koin.java.KoinJavaComponent.get(CommandExecutor::class.java)
        val said = engine.fire(id, russian = true) { step: CommandResult ->
            (commands.execute(step, russian = true) as? CommandExecutor.Outcome.Reply)?.text.orEmpty()
        }
        schedules.byId(id)?.let { ModeAlarms(context).book(it) }
        said?.let { notifyModeResult(context, it) }
    }

    private suspend fun rebookAll(context: Context, schedules: ModeSchedules) {
        val all = schedules.all()
        ModeAlarms(context).rebook(all, emptySet())
        Log.i(TAG, "Re-booked ${all.size} mode schedules")
    }
}

/** A mode that started or ended by itself says so quietly: a silent notification. */
fun notifyModeResult(context: Context, text: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Режимы", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Что сделал режим, включившийся сам: по расписанию, Bluetooth, зарядке, Wi-Fi" }
        )
    }
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val tap = PendingIntent.getActivity(
        context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Пятница")
        .setContentText(text.take(PREVIEW_CHARS))
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentIntent(tap)
        .setAutoCancel(true)
        .setSilent(true)
        .build()
    runCatching { manager.notify(NOTIFICATION_ID, notification) }
}

private const val CHANNEL_ID = "friday_modes"
private const val NOTIFICATION_ID = 4_201
private const val PREVIEW_CHARS = 80
