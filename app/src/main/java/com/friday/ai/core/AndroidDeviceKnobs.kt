package com.friday.ai.core

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/** [DeviceKnobs] on a real phone: AudioManager, NotificationManager, Settings.System, the radios. */
@Suppress("TooManyFunctions") // implements DeviceKnobs, a read and a write per setting
class AndroidDeviceKnobs(private val context: Context) : DeviceKnobs {

    private companion object {
        /** Settings.System.SCREEN_BRIGHTNESS is 0..255 on stock Android and One UI. */
        const val BRIGHTNESS_MAX = 255
        const val PERCENT = 100

        /** Added before dividing by 100, so integer division rounds to the nearest step. */
        const val HALF = PERCENT / 2
    }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val resolver get() = context.contentResolver

    override fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max == 0) 0 else audio.getStreamVolume(AudioManager.STREAM_MUSIC) * PERCENT / max
    }

    override fun setVolumePercent(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // Round to the nearest step, but never let a non-zero request land on silence.
        val index = ((max * percent + HALF) / PERCENT).let { if (percent > 0) it.coerceAtLeast(1) else 0 }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    override fun dndAccess(): Boolean = notifications.isNotificationPolicyAccessGranted

    override fun dndOn(): Boolean =
        notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    override fun setDnd(on: Boolean) {
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    override fun openDndAccessSettings() = open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    override fun ringer(): Ringer = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> Ringer.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> Ringer.VIBRATE
        else -> Ringer.NORMAL
    }

    override fun setRinger(mode: Ringer) {
        audio.ringerMode = when (mode) {
            Ringer.SILENT -> AudioManager.RINGER_MODE_SILENT
            Ringer.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            Ringer.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        }
    }

    override fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    override fun openWriteSettingsPermission() = open(
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
    )

    override fun brightnessPercent(): Int =
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, BRIGHTNESS_MAX / 2) * PERCENT /
            BRIGHTNESS_MAX

    override fun setBrightnessPercent(percent: Int) {
        Settings.System.putInt(
            resolver, Settings.System.SCREEN_BRIGHTNESS, (percent * BRIGHTNESS_MAX + HALF) / PERCENT
        )
    }

    override fun brightnessAuto(): Boolean = Settings.System.getInt(
        resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    override fun setBrightnessAuto(auto: Boolean) {
        Settings.System.putInt(
            resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
    }

    override fun radioOn(radio: Radio): Boolean? = runCatching {
        when (radio) {
            Radio.WIFI ->
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
            Radio.BLUETOOTH ->
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
        }
    }.getOrNull()

    override fun openPanel(radio: Radio) = open(
        Intent(
            when {
                radio == Radio.WIFI && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Settings.Panel.ACTION_WIFI
                radio == Radio.WIFI -> Settings.ACTION_WIFI_SETTINGS
                else -> Settings.ACTION_BLUETOOTH_SETTINGS
            }
        )
    )

    private fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
