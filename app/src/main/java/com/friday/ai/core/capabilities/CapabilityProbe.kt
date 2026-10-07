package com.friday.ai.core.capabilities

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.InputDevice
import androidx.core.content.ContextCompat
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.repository.AssistantRepositoryImpl
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.FridayNotificationListener
import com.friday.ai.service.FridayWakeWordService
import com.friday.ai.service.VoskModelManager
import com.friday.ai.service.mail.MailAssistant

/**
 * Reads [FridayCapabilities] from the system and from Friday's own settings.
 *
 * Every check is a cheap local call — a permission, a package lookup, a
 * preference — so a fresh snapshot can be taken before each answer. Each
 * fact is detected from what the phone reports, never inferred from the
 * model name: "Samsung" does not mean "has an S Pen".
 */
class CapabilityProbe(private val context: Context, private val prefs: UserPreferenceDao) {

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val UWB_FEATURE = "android.hardware.uwb"

        /** Pre-Android-10 record of the selected assistant: "package/service". */
        const val SECURE_ASSISTANT = "assistant"
    }

    suspend fun probe(): FridayCapabilities = FridayCapabilities(
        device = device(),
        permissions = permissions(),
        integrations = FridayCapabilities.Integrations(
            groqKey = !prefs.get(AssistantRepositoryImpl.PREF_API_KEY).isNullOrBlank(),
            gmail = !prefs.get(MailAssistant.PREF_ACCOUNT).isNullOrBlank(),
            lazuri = prefs.get(FridayMemory.PREF_LAZURI_ENABLED) == "true" &&
                prefs.get(FridayMemory.PREF_LAZURI_DEVICE_ID) != null
        ),
        voice = FridayCapabilities.Voice(
            wakeWordEnabled = prefs.get("wake_word_enabled") == "true",
            wakeModelReady = VoskModelManager(context).isModelReady(),
            voiceProfile = !prefs.get(FridayWakeWordService.PREF_VOICE_PROFILE).isNullOrBlank(),
            listening = FridayWakeWordService.running
        ),
        assistant = FridayCapabilities.Assistant(active = isDefaultAssistant()),
        privileged = FridayCapabilities.Privileged(shizukuInstalled = installed(SHIZUKU_PACKAGE))
    )

    private fun device(): FridayCapabilities.Device {
        val pm = context.packageManager
        return FridayCapabilities.Device(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            nfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC),
            uwb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pm.hasSystemFeature(UWB_FEATURE),
            stylus = hasStylus(),
            externalDisplay = hasExternalDisplay()
        )
    }

    private fun permissions(): FridayCapabilities.Permissions {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return FridayCapabilities.Permissions(
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            overlay = Settings.canDrawOverlays(context),
            notificationListener = FridayNotificationListener.isEnabled(context),
            postNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            contacts = granted(Manifest.permission.READ_CONTACTS),
            phone = granted(Manifest.permission.CALL_PHONE),
            calendar = granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR),
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            dndAccess = (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationPolicyAccessGranted,
            writeSettings = Settings.System.canWrite(context)
        )
    }

    /** A pen digitiser reports itself as a stylus input source; a manufacturer name proves nothing. */
    private fun hasStylus(): Boolean {
        val input = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        return input.inputDeviceIds.any { id ->
            input.getInputDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
        }
    }

    /** Presentation displays are real external screens (HDMI, DeX, Miracast), not private virtual ones. */
    private fun hasExternalDisplay(): Boolean {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .any { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /** API 29+: the assistant role; before that, the setting the system kept it in. */
    private fun isDefaultAssistant(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            Settings.Secure.getString(context.contentResolver, SECURE_ASSISTANT)
                ?.startsWith(context.packageName + "/") == true
        }

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
