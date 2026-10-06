package com.friday.ai.service.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/** The persistent notification a foreground service must show, and its status line. */
class ServiceNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "friday_wake_word"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "ServiceNotifier"
    }

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Friday Assistant", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Friday wake word listener"
                    setShowBadge(false)
                    setSound(null, null)
                }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Friday AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Status text only; a failure here must never disturb the voice loop. */
    fun update(text: String) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
        } catch (e: Exception) {
            Log.w(TAG, "Status not updated: ${e.message}")
        }
    }
}
