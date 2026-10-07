package com.friday.ai.core

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class AppLauncher(
    private val context: Context,
    private val calendarWriter: CalendarWriter
) {

    private companion object {
        /** How long a placed call may take to show up as one. */
        const val CALL_WAIT_MS = 5_000L
    }

    /**
     * The torch as the system reports it. A flag of our own went stale the
     * moment the user used the quick-settings tile, and "выключи фонарик"
     * then switched it on.
     */
    @Volatile private var torchOn = false

    private val cameraManager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    init {
        try {
            cameraManager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchOn = enabled
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.w("AppLauncher", "No torch callback: ${e.message}")
        }
    }

    fun openApp(packageHint: String?, appName: String): String {
        // "Браузер", "галерея", "сообщения" name a role, not an app: open
        // whatever the user has chosen for it, Samsung's or Google's alike.
        defaultAppFor(appName)?.let { intent ->
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Opening $appName"
            }
        }
        val packageName = packageHint?.takeIf { isInstalled(it) } ?: resolvePackage(appName)

        if (packageName != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opening $appName"
            }
        }

        val searchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val resolveInfos = context.packageManager.queryIntentActivities(searchIntent, 0)
        val match = resolveInfos.firstOrNull { info ->
            info.loadLabel(context.packageManager).toString().lowercase().contains(appName.lowercase())
        }

        return if (match != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(
                match.activityInfo.packageName
            )
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Opening ${match.loadLabel(context.packageManager)}"
            } else {
                "Can't open $appName — launch intent not found"
            }
        } else {
            "App \"$appName\" not found on this device"
        }
    }

    /**
     * Android lets no app close another. What can be done is to leave it —
     * go to the home screen — and let the system reclaim it in the
     * background; the reply says exactly that instead of "closed".
     */
    fun closeApp(packageHint: String?, appName: String): String {
        val packageName = packageHint?.takeIf { isInstalled(it) } ?: resolvePackage(appName)
            ?: return "App \"$appName\" not found on this device"
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(packageName)
        return "Went to the home screen; Android doesn't let an assistant close $appName itself, " +
            "the system will stop it in the background"
    }

    private fun isInstalled(pkg: String): Boolean = context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** The user's default app for a role named in [appName], if it names one. */
    private fun defaultAppFor(appName: String): Intent? {
        val name = appName.lowercase()
        return roles.firstOrNull { (words, _) -> words.any { name.startsWith(it) } }?.second?.invoke()
    }

    private fun selector(category: String): () -> Intent =
        { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category) }

    private val roles: List<Pair<List<String>, () -> Intent>> = listOf(
        listOf("браузер", "browser", "интернет") to selector(Intent.CATEGORY_APP_BROWSER),
        listOf("сообщени", "смс", "messages", "sms") to selector(Intent.CATEGORY_APP_MESSAGING),
        listOf("галере", "фотографи", "gallery", "photos") to selector(Intent.CATEGORY_APP_GALLERY),
        listOf("контакт", "contacts") to selector(Intent.CATEGORY_APP_CONTACTS),
        listOf("календар", "calendar") to selector(Intent.CATEGORY_APP_CALENDAR),
        listOf("карт", "maps", "навигатор") to selector(Intent.CATEGORY_APP_MAPS),
        listOf("почт", "mail", "email") to selector(Intent.CATEGORY_APP_EMAIL),
        listOf("калькулятор", "calculator") to selector(Intent.CATEGORY_APP_CALCULATOR),
        listOf("плеер", "player") to selector(Intent.CATEGORY_APP_MUSIC),
        listOf("телефон", "звонки", "phone", "dialer") to { Intent(Intent.ACTION_DIAL) },
        listOf("часы", "будильник", "clock") to { Intent(AlarmClock.ACTION_SHOW_ALARMS) },
        listOf("настройки", "settings") to { Intent(android.provider.Settings.ACTION_SETTINGS) }
    )

    /**
     * Rings [number] over the ordinary phone line. Who to call, and whether
     * a messenger call suits better, is decided by
     * [com.friday.ai.core.people.Caller].
     */
    suspend fun dial(number: String, label: String): String {
        return try {
            // ACTION_CALL places the call outright; ACTION_DIAL only fills the
            // dialer and waits for a tap, which isn't much use hands-free.
            val canCallDirectly = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = Intent(
                if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
            ).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!canCallDirectly) {
                context.startActivity(intent)
                return "Ready to call $label — the dialer is open"
            }
            // A placed call puts the audio system in call mode within seconds.
            // Never sent twice: a second ACTION_CALL would be a second call.
            val placed = HandOff.run(
                send = { context.startActivity(intent) },
                happened = { CallState.inCall(context) },
                waitMs = CALL_WAIT_MS,
                retry = false
            )
            if (placed == HandOff.Result.NOT_SEEN) "Asked the phone to call $label, but no call started"
            else "Calling $label"
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call.
            Log.w("AppLauncher", "Direct call refused: ${e.message}")
            "Can't place the call — permission denied"
        } catch (e: Exception) {
            "Can't make call: ${e.message}"
        }
    }

    /**
     * Saves a calendar event. Writes it straight to the calendar when allowed
     * — a voice assistant that makes you tap "save" afterwards isn't
     * hands-free. Falls back to the calendar app's editor only when the
     * permission is missing.
     */
    fun createCalendarEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        reminderMinutes: Int = DateTimeParser.DEFAULT_REMINDER_MINUTES
    ): String {
        calendarWriter.insert(title, startMillis, endMillis, reminderMinutes)?.let {
            return "Saved \"$title\" for ${formatWhen(startMillis)}, " +
                "reminder ${describeLead(reminderMinutes)} before"
        }

        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                putExtra(CalendarContract.Reminders.HAS_ALARM, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val whenText = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT
            ).format(Date(startMillis))
            "Added \"$title\" for $whenText"
        } catch (e: Exception) {
            "Can't create the event: ${e.message}"
        }
    }

    /**
     * Moves an existing event to a new time, keeping how long it lasts.
     * Finding the event is best-effort: if nothing credible matches, say so
     * rather than moving the wrong thing.
     */
    fun rescheduleEvent(titleHint: String?, newStartMillis: Long): String {
        if (!calendarWriter.hasPermission()) {
            return "I need calendar access to move events"
        }

        val upcoming = calendarWriter.upcomingEvents()
        if (upcoming.isEmpty()) return "There's nothing upcoming to move"

        val event = EventMatcher.findBest(titleHint, upcoming)
            ?: return if (titleHint != null) {
                "I couldn't find \"$titleHint\" in your calendar"
            } else {
                "There's nothing upcoming to move"
            }

        val duration = event.durationMillis.takeIf { it > 0 } ?: (60 * 60 * 1000L)
        val moved = calendarWriter.reschedule(event.id, newStartMillis, newStartMillis + duration)

        val name = event.title.ifBlank { "the event" }
        return if (moved) {
            "Moved \"$name\" to ${formatWhen(newStartMillis)}"
        } else {
            "Couldn't move \"$name\""
        }
    }


    private fun formatWhen(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    private fun describeLead(minutes: Int): String = when {
        minutes >= 120 && minutes % 60 == 0 -> "${minutes / 60} hours"
        minutes >= 60 && minutes < 120 -> "an hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes minutes"
    }

    fun setTimer(seconds: Int, label: String?): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                // Started without the Clock screen, like an alarm: with the UI
                // shown, a timer could sit waiting for a tap nobody makes.
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val mins = seconds / 60
            val secs = seconds % 60
            if (mins > 0) "Setting timer for ${mins}m ${secs}s"
            else "Setting timer for ${secs}s"
        } catch (e: Exception) {
            "Can't set timer: ${e.message}"
        }
    }

    /** [on] null flips it. */
    fun setFlashlight(on: Boolean?): String {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "No camera found"
            val target = on ?: !torchOn
            cameraManager.setTorchMode(cameraId, target)
            torchOn = target
            if (target) "Flashlight ON" else "Flashlight OFF"
        } catch (e: Exception) {
            Log.e("AppLauncher", "Flashlight error: ${e.message}")
            "Can't toggle flashlight: ${e.message}"
        }
    }

    /**
     * [mapsProvider] is "auto" (default), "google" or "yandex", from Settings.
     *
     * "auto" sends a plain `geo:` link, which every maps app understands, so
     * the phone's own default answers — Google, Yandex, or anything else the
     * user chose in Android. A named provider gets its own deep link. Either
     * way the web version is the fallback when no app can take it.
     */
    fun findNearby(query: String, mapsProvider: String): String {
        return try {
            val encoded = Uri.encode(query)
            val intent = when (mapsProvider) {
                "yandex" -> Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?text=$encoded"))
                "google" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
                    .setPackage("com.google.android.apps.maps")
                else -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                val fallbackUrl = if (mapsProvider == "yandex") {
                    "https://yandex.ru/maps/?text=$encoded"
                } else {
                    "https://www.google.com/maps/search/?api=1&query=$encoded"
                }
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            "Looking for $query nearby"
        } catch (e: Exception) {
            "Can't search maps: ${e.message}"
        }
    }

    fun webSearch(query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Searching for \"$query\""
        } catch (e: Exception) {
            "Can't search: ${e.message}"
        }
    }

    private fun resolvePackage(appName: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .firstOrNull { it.loadLabel(context.packageManager).toString().lowercase().contains(appName.lowercase()) }
            ?.activityInfo?.packageName
    }
}
