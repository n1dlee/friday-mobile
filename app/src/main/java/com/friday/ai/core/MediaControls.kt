package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.friday.ai.domain.model.MediaAction

/**
 * Play, pause and skip in whatever music app is running.
 *
 * Goes through [MediaSessionManager] rather than broadcasting media key
 * events, because a session gives two things a key event cannot: it says which
 * app is actually playing, and it can be addressed individually — so "поставь
 * Spotify" reaches Spotify even when three apps hold a session.
 *
 * When nothing is loaded, "включи музыку" starts a player — the one named,
 * the one the user usually picks, or the best installed — through
 * [PlaybackStarter], which checks that music actually plays. A bare media
 * key, which can't be aimed or checked, is the last resort.
 *
 * Reading sessions requires notification-listener access, which this app
 * already asks for; without it the user is sent to the screen that grants it
 * rather than being told the command failed.
 */
class MediaControls(
    private val context: Context,
    private val sessions: MediaSessions,
    private val starter: PlaybackStarter
) {

    private companion object {
        const val TAG = "MediaControls"

        /** After "play" on a loaded session, how long to wait to hear it. */
        const val PLAYING_WAIT_MS = 3_000L

        /** A pause older than this is not this conversation's. */
        const val KEEP_PAUSED_MS = 2 * 60 * 1000L
    }

    /**
     * Which player the user paused, and when. Friday pauses other audio
     * while she listens; some players treat getting it back as a cue to
     * resume, even after being told to pause in between.
     */
    @Volatile
    private var pausedByUser: Pair<String, Long>? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    suspend fun perform(action: MediaAction, appHint: String?, russian: Boolean = true): String {
        val active = sessions.active() ?: return grantNotificationAccess(russian)
        // Any installed player counts as named, not only the well-known ones.
        val named = appHint?.let(starter::named)

        // Named app first; otherwise whatever is playing, otherwise anything.
        val target = when {
            named != null -> active.firstOrNull { it.packageName == named.packageName }
            else -> active.firstOrNull(sessions::isPlaying) ?: active.firstOrNull()
        }
        return when {
            target != null -> transport(target, action, russian)
            !action.startsPlayback -> if (russian) "Сейчас ничего не играет" else "Nothing is playing"
            else -> startPlayer(named, action, russian)
        }
    }

    /** Nothing loaded: start the named, usual or best player, and check that it plays. */
    private suspend fun startPlayer(named: DeviceContext.App?, action: MediaAction, russian: Boolean): String {
        named?.let { starter.remember(it) }
        val player = named ?: starter.default() ?: return fallbackKey(action, russian)
        pausedByUser = null
        return starter.start(player, query = null, russian).orEmpty()
    }

    private suspend fun transport(target: MediaController, action: MediaAction, russian: Boolean): String {
        fun say(ru: String, en: String) = if (russian) ru else en
        val controls = target.transportControls
        return try {
            when (action) {
                MediaAction.PLAY -> play(target, russian)
                MediaAction.PAUSE -> { pause(target); say("Пауза", "Paused") }
                // Pause, not stop: Spotify and others ignore stop(), and a stopped
                // player cannot be resumed with "продолжи".
                MediaAction.STOP -> { pause(target); say("Остановила", "Stopped") }
                MediaAction.NEXT -> { controls.skipToNext(); say("Следующий трек", "Next track") }
                MediaAction.PREVIOUS -> { controls.skipToPrevious(); say("Предыдущий трек", "Previous track") }
                MediaAction.TOGGLE ->
                    if (sessions.isPlaying(target)) { pause(target); say("Пауза", "Paused") }
                    else play(target, russian)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transport control failed: ${e.message}")
            fallbackKey(action, russian)
        }
    }

    /** Presses play and says "playing" only if the session then says so. */
    private suspend fun play(target: MediaController, russian: Boolean): String {
        pausedByUser = null
        target.transportControls.play()
        val playing = HandOff.poll(PLAYING_WAIT_MS) { sessions.isPlaying(target) }
        val app = label(target.packageName)
        return PlayerStart.reply(
            if (playing) PlayerStart.Outcome.PLAYING else PlayerStart.Outcome.OPENED_NOT_PLAYING, app, russian
        )
    }

    private fun pause(target: MediaController) {
        target.transportControls.pause()
        pausedByUser = target.packageName to System.currentTimeMillis()
    }

    /** "Сейчас играет «Believer» — Imagine Dragons, Spotify." */
    fun nowPlaying(russian: Boolean): String {
        val active = sessions.active() ?: return grantNotificationAccess(russian)
        // Paused while Friday listens, so "the playing one" may be paused right now:
        // the first session is the one most recently in use.
        val session = active.firstOrNull(sessions::isPlaying) ?: active.firstOrNull()
        val nothing = if (russian) "Сейчас ничего не играет" else "Nothing is playing"
        val meta = session?.metadata ?: return nothing
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return nothing
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val by = artist?.let { " — $it" }.orEmpty()
        val where = ", " + label(session.packageName)
        return if (russian) "Сейчас играет «$title»$by$where" else "Now playing \"$title\"$by$where"
    }

    /** Called when Friday gives the audio back: re-pauses a player the user paused, if it started again. */
    fun keepPaused() {
        val (pkg, at) = pausedByUser ?: return
        pausedByUser = null
        if (System.currentTimeMillis() - at > KEEP_PAUSED_MS) return
        sessions.of(pkg)?.takeIf(sessions::isPlaying)?.let {
            Log.i(TAG, "$pkg resumed on its own after being paused; pausing again")
            it.transportControls.pause()
        }
    }

    private fun label(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * Last resort when no player can be found or addressed: a media key the
     * system hands to whichever app owns the media button. It can't be
     * checked, so the reply doesn't claim music is playing.
     */
    private fun fallbackKey(action: MediaAction, russian: Boolean): String {
        val code = when (action) {
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.PAUSE, MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            if (russian) "Отправила команду плееру — проверить, сработало ли, не могу"
            else "Sent the command to the player — I can't check whether it worked"
        } catch (e: Exception) {
            Log.e(TAG, "Media key failed: ${e.message}")
            if (russian) "Не нашла, чем управлять" else "Found nothing to control"
        }
    }

    private fun grantNotificationAccess(russian: Boolean): String = try {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        if (russian) "Чтобы управлять плеером, включите доступ Friday к уведомлениям — открыла настройки"
        else "To control the player, allow Friday notification access — I've opened the settings"
    } catch (_: Exception) {
        if (russian) "Нужен доступ к уведомлениям" else "I need notification access"
    }
}
