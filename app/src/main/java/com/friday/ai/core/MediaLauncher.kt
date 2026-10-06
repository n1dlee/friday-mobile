package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.friday.ai.domain.model.CameraMode

/**
 * Camera, voice recorder, and system settings screens.
 *
 * Everything here launches something and says honestly what happened. Where
 * the platform blocks the thing the user actually asked for — a photo taken
 * without touching the phone, an NFC toggle — the reply says so instead of
 * reporting success for an action that did not occur.
 */
class MediaLauncher(private val context: Context) {

    private companion object {
        const val TAG = "MediaLauncher"

        /** Toggles Android no longer lets any third-party app flip. */
        val UNTOGGLEABLE = setOf(
            SettingsScreens.Screen.NFC,
            SettingsScreens.Screen.WIFI,
            SettingsScreens.Screen.BLUETOOTH,
            SettingsScreens.Screen.MOBILE_DATA,
            SettingsScreens.Screen.AIRPLANE,
            SettingsScreens.Screen.HOTSPOT,
            SettingsScreens.Screen.LOCATION
        )

        /**
         * Undocumented but honoured by every major camera app, Samsung's
         * included: which lens to open on. There is no public API for it.
         */
        const val EXTRA_CAMERA_FACING = "android.intent.extras.CAMERA_FACING"
        const val CAMERA_FACING_FRONT = 1

        /** Samsung/Google use this alongside the above; harmless elsewhere. */
        const val EXTRA_USE_FRONT_CAMERA = "android.intent.extra.USE_FRONT_CAMERA"
    }

    fun openCamera(mode: CameraMode): String = try {
        val intent = when (mode) {
            CameraMode.VIDEO -> Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            CameraMode.PHOTO -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            CameraMode.SELFIE -> Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(EXTRA_CAMERA_FACING, CAMERA_FACING_FRONT)
                putExtra(EXTRA_USE_FRONT_CAMERA, true)
            }
            CameraMode.JUST_OPEN -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        }
        launch(intent)
        when (mode) {
            // Deliberately not "готово": the shutter is still the user's to press.
            CameraMode.PHOTO -> "Камера готова — нажмите спуск"
            CameraMode.SELFIE -> "Фронтальная камера готова"
            CameraMode.VIDEO -> "Камера в режиме видео — нажмите запись"
            CameraMode.JUST_OPEN -> "Открываю камеру"
        }
    } catch (e: Exception) {
        Log.e(TAG, "Camera launch failed: ${e.message}")
        "Не смог открыть камеру"
    }

    fun recordAudio(): String = try {
        launch(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
        "Открываю диктофон"
    } catch (e: Exception) {
        Log.e(TAG, "Recorder launch failed: ${e.message}")
        // Not every phone ships an app that answers this intent.
        "На телефоне нет приложения для записи звука"
    }

    /**
     * Opens the settings screen [phrase] names.
     *
     * The reply distinguishes the two cases that matter to the user: screens
     * that are merely a shortcut, and toggles Android no longer lets any app
     * flip. Saying "включил NFC" would be a lie — the switch is one tap away
     * and that tap is the user's.
     */
    fun openSettings(phrase: String): String {
        val screen = SettingsScreens.match(phrase) ?: SettingsScreens.Screen.ROOT
        return try {
            launch(Intent(screen.action))
            if (screen in UNTOGGLEABLE) {
                "Android не даёт приложениям переключать ${screen.label} — открыл настройки"
            } else {
                "Открываю ${screen.label}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Settings screen ${screen.name} failed: ${e.message}")
            try {
                launch(Intent(Settings.ACTION_SETTINGS))
                "Этого экрана здесь нет — открыл общие настройки"
            } catch (_: Exception) {
                "Не смог открыть настройки"
            }
        }
    }

    private fun launch(intent: Intent) {
        // Started from a service, so there is no task to attach to.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
