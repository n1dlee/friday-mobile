package com.friday.ai.service

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Build
import com.friday.ai.core.messages.Conversations
import com.friday.ai.core.people.Channel
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.service.messages.Announcement
import com.friday.ai.service.messages.Announcer
import com.friday.ai.service.messages.MessengerInbox
import org.koin.java.KoinJavaComponent.get
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.friday.ai.data.local.dao.NotificationDao
import com.friday.ai.data.local.entity.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records notifications so Friday can answer "what did I miss".
 *
 * This is the cheapest route to knowing about Telegram messages, bank alerts
 * and email at once — no per-service API, no accounts to link. It is also the
 * most invasive permission in the app, so the rules are deliberately narrow:
 *
 *  - only the app name, title and a short text are stored, never the full
 *    notification or its extras
 *  - Friday's own foreground notification and silent/ongoing system noise are
 *    ignored
 *  - rows older than [RETENTION_MS] are deleted on every write
 *  - nothing is sent anywhere; it stays in the local database
 *
 * It also passes chats to [MessengerInbox] (kept in memory only, so Friday
 * can read and answer them) and tells [Announcer] about incoming calls and,
 * if the user turned it on, new messages.
 */
class FridayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "FridayNotifications"

        /** A week is plenty to answer "what did I miss" without hoarding. */
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        private const val MAX_TEXT_CHARS = 300

        /** Chatter that is never useful in a summary. */
        private val IGNORED_PACKAGES = setOf(
            "com.friday.ai",
            "android",
            "com.android.systemui",
            "com.samsung.android.lool",
            "com.sec.android.app.samsungapps"
        )

        /** Settings: say who is calling (on unless turned off). */
        const val PREF_ANNOUNCE_CALLS = "announce_calls"

        /** A ringing call re-posts its notification; it is announced once. */
        private const val CALL_REPEAT_MS = 60_000L

        // Notification.EXTRA_CALL_TYPE / EXTRA_CALL_PERSON / CallStyle.CALL_TYPE_INCOMING
        // (API 31), spelled out so older phones can read them too.
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_CALL_PERSON = "android.callPerson"
        private const val CALL_TYPE_INCOMING = 1

        /** Titles that say "a call" rather than who is calling. */
        private val GENERIC_CALL_TITLES = Regex("(?i)входящ|incoming|вызов|call")

        /** Whether the user has granted notification access in system settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(context.packageName)
        }

        /** Opens the system screen where the user can grant access. */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao: NotificationDao by lazy {
        org.koin.java.KoinJavaComponent.get(NotificationDao::class.java)
    }
    private val prefs: UserPreferenceDao by lazy { get(UserPreferenceDao::class.java) }
    private val inbox: MessengerInbox by lazy { get(MessengerInbox::class.java) }
    private val announcer: Announcer by lazy { get(Announcer::class.java) }

    private var lastCall: Pair<String, Long>? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName in IGNORED_PACKAGES) return
        // Before the filter below: a ringing call is an ongoing notification.
        incomingCaller(notification)?.let { caller ->
            announceCall(notification, caller)
            return
        }
        if (!isWorthKeeping(notification)) return
        takeChat(notification)

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        val entity = NotificationEntity(
            packageName = notification.packageName,
            appName = resolveAppName(notification.packageName),
            title = title.take(MAX_TEXT_CHARS),
            text = text.take(MAX_TEXT_CHARS),
            postedAt = notification.postTime
        )

        scope.launch {
            runCatching {
                dao.insert(entity)
                dao.prune(System.currentTimeMillis() - RETENTION_MS)
            }.onFailure { Log.w(TAG, "Could not record notification: ${it.message}") }
        }
    }

    private fun isWorthKeeping(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName in IGNORED_PACKAGES) return false
        val n = sbn.notification
        // Ongoing = media players, downloads, foreground services. Not news.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }

    private fun takeChat(sbn: StatusBarNotification) {
        // Kept, never read out on arrival: three quick messages used to be
        // three announcements. The owner asks ("есть непрочитанные?") when it
        // suits them.
        inbox.record(sbn, resolveAppName(sbn.packageName))
    }

    private fun incomingCaller(sbn: StatusBarNotification): String? {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_CALL) return null
        val type = n.extras.getInt(EXTRA_CALL_TYPE, -1)
        // Older dialers mark a ringing call only by its full-screen screen.
        val ringing = type == CALL_TYPE_INCOMING || (type == -1 && n.fullScreenIntent != null)
        if (!ringing) return null
        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            (n.extras.get(EXTRA_CALL_PERSON) as? android.app.Person)?.name?.toString()
        } else {
            null
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        return person?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() && !GENERIC_CALL_TITLES.containsMatchIn(it) }
            ?: text?.takeIf { it.isNotBlank() && !GENERIC_CALL_TITLES.containsMatchIn(it) }
    }

    private fun announceCall(sbn: StatusBarNotification, caller: String) {
        val now = System.currentTimeMillis()
        lastCall?.let { (key, at) -> if (key == sbn.key && now - at < CALL_REPEAT_MS) return }
        lastCall = sbn.key to now
        // The phone's own dialer needs no name; a messenger call says which one.
        val app = Channel.entries.firstOrNull { sbn.packageName in it.packages }?.label
        scope.launch {
            if (prefs.get(PREF_ANNOUNCE_CALLS) == "false" || !quietAllowed()) return@launch
            announcer.say(Announcement(Conversations.incomingCall(caller, app, russian()), listenAfter = false))
        }
    }

    /** Not during Do Not Disturb, and not over a call already in progress. */
    private fun quietAllowed(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val dnd = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        val onCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        return !dnd && !onCall
    }

    private suspend fun russian(): Boolean = (prefs.get("prefer_russian") ?: "true") == "true"

    private fun resolveAppName(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))
}
