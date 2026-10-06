package com.friday.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.friday.ai.core.BriefTiming
import com.friday.ai.data.local.dao.UserPreferenceDao
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Delivers the morning briefing without being asked.
 *
 * Each run books the next one for the following local morning, so the brief
 * stays pinned to the clock on the wall rather than to whenever the system
 * last let it run.
 */
class MorningBriefWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MorningBrief"

        /**
         * The old daily periodic job. Kept only so it can be cancelled: it is
         * the one that drifted to 16:00 and stayed there.
         */
        private const val LEGACY_PERIODIC_WORK = "friday_morning_brief"
        private const val WORK_NAME = "friday_morning_brief_next"

        const val CHANNEL_ID = "friday_brief"
        private const val NOTIFICATION_ID = 2001

        const val PREF_ENABLED = "brief_enabled"
        const val PREF_HOUR = "brief_hour"
        const val DEFAULT_HOUR = 8

        /**
         * Makes sure the next brief is booked for the coming [hourOfDay]:00
         * local time.
         *
         * @param replace false on app start — a brief already booked, and
         *   possibly just about to run, must not be pushed to tomorrow. True
         *   when the hour or the time zone has changed, because then the
         *   booked time itself is wrong.
         */
        fun schedule(context: Context, hourOfDay: Int = DEFAULT_HOUR, replace: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(LEGACY_PERIODIC_WORK)
            wm.enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                nextRequest(hourOfDay)
            )
            Log.i(TAG, "Briefing booked for $hourOfDay:00 local (replace=$replace)")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(LEGACY_PERIODIC_WORK)
            wm.cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Briefing cancelled")
        }

        private fun nextRequest(hourOfDay: Int) =
            OneTimeWorkRequestBuilder<MorningBriefWorker>()
                .setInitialDelay(
                    BriefTiming.delayUntilNext(ZonedDateTime.now(), hourOfDay).toMillis(),
                    TimeUnit.MILLISECONDS
                )
                .build()
    }

    override suspend fun doWork(): Result {
        val prefDao: UserPreferenceDao =
            org.koin.java.KoinJavaComponent.get(UserPreferenceDao::class.java)
        val hour = prefDao.get(PREF_HOUR)?.toIntOrNull() ?: DEFAULT_HOUR

        try {
            deliverIfDue(prefDao, hour)
        } catch (e: Exception) {
            // No retry loop: a brief that failed at 08:00 is not worth
            // hammering the network for until lunchtime.
            Log.e(TAG, "Briefing failed: ${e.message}")
        } finally {
            // Book tomorrow from the actual local clock, every time. Appended
            // rather than replacing so this run is not cancelled under itself.
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, nextRequest(hour))
        }
        return Result.success()
    }

    private suspend fun deliverIfDue(prefDao: UserPreferenceDao, hour: Int) {
        // The user can switch this off without uninstalling the schedule.
        if (prefDao.get(PREF_ENABLED) == "false") {
            Log.i(TAG, "Briefing disabled; skipping")
            return
        }
        val now = LocalTime.now()
        if (!BriefTiming.shouldDeliver(now, hour)) {
            // The phone was off or asleep through the morning. A "good
            // morning" at four in the afternoon is the bug, not the feature.
            Log.i(TAG, "Woke at $now, too far from $hour:00; skipping today")
            return
        }
        val proactive: ProactiveBriefService =
            org.koin.java.KoinJavaComponent.get(ProactiveBriefService::class.java)
        val russian = (prefDao.get("prefer_russian") ?: "true") == "true"
        notify(proactive.morningBrief(russian))
    }

    private fun notify(text: String) {
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Morning briefing",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Your daily summary from Friday" }
            )
        }

        val open = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(
            applicationContext, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Friday")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Briefing delivered")
    }
}
