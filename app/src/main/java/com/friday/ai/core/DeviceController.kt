package com.friday.ai.core

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.friday.ai.domain.model.DeviceAction

/**
 * Quick device settings.
 *
 * Android deliberately limits what an app may change. Volume and Do Not
 * Disturb are allowed (DND needs a one-off grant); Wi-Fi and Bluetooth
 * toggling was removed for third-party apps in Android 10, so those open the
 * relevant panel instead. Saying "done" without doing anything would be worse
 * than admitting the platform won't allow it.
 */
class DeviceController(private val context: Context) {

    private companion object {
        const val TAG = "DeviceController"
        const val VOLUME_STEP_PERCENT = 15
    }

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun perform(action: DeviceAction, level: Int?): String = try {
        when (action) {
            DeviceAction.VOLUME_UP -> nudgeVolume(+VOLUME_STEP_PERCENT)
            DeviceAction.VOLUME_DOWN -> nudgeVolume(-VOLUME_STEP_PERCENT)
            DeviceAction.VOLUME_SET -> setVolumePercent(level ?: 50)
            DeviceAction.MUTE -> setVolumePercent(0)
            DeviceAction.UNMUTE -> setVolumePercent(40)
            DeviceAction.DND_ON -> setDoNotDisturb(true)
            DeviceAction.DND_OFF -> setDoNotDisturb(false)
            DeviceAction.OPEN_WIFI_PANEL -> openPanel(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
                else Settings.ACTION_WIFI_SETTINGS,
                "Wi-Fi"
            )
            DeviceAction.OPEN_BLUETOOTH_PANEL -> openPanel(
                Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth"
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Action $action failed: ${e.message}")
        "Couldn't do that: ${e.message}"
    }

    private fun nudgeVolume(deltaPercent: Int): String {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentPercent = if (max == 0) 0 else current * 100 / max
        return setVolumePercent((currentPercent + deltaPercent).coerceIn(0, 100))
    }

    private fun setVolumePercent(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * clamped) / 100
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return when (clamped) {
            0 -> "Muted"
            else -> "Volume $clamped%"
        }
    }

    /**
     * DND needs the user to grant "Do Not Disturb access" once, in a system
     * screen we can only open — not grant ourselves.
     */
    private fun setDoNotDisturb(enable: Boolean): String {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            openPanel(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb access")
            return "I need Do Not Disturb access — I've opened the setting"
        }
        notificationManager.setInterruptionFilter(
            if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (enable) "Do Not Disturb on" else "Do Not Disturb off"
    }

    private fun openPanel(action: String, label: String): String {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Being explicit that this is a limitation, not a half-finished feature.
        return "Android doesn't let apps switch $label directly — opened the settings for you"
    }
}
