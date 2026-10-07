package com.friday.ai.core

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.friday.ai.domain.model.MediaAction
import com.friday.ai.service.FridayNotificationListener
import kotlinx.coroutines.delay

/**
 * Play, pause and skip in whatever music app is running.
 *
 * Goes through [MediaSessionManager] rather than broadcasting media key
 * events, because a session gives two things a key event cannot: it says which
 * app is actually playing, and it can be addressed individually — so "поставь
 * Spotify" reaches Spotify even when three apps hold a session. Key events are
 * kept as the fallback for players that publish no session.
 *
 * Reading sessions requires notification-listener access, which this app
 * already asks for; without it the user is sent to the screen that grants it
 * rather than being told the command failed.
 */
class MediaControls(private val context: Context) {

    private companion object {
        const val TAG = "MediaControls"

        const val SESSION_WAIT_MS = 6_000
        const val PLAYING_WAIT_MS = 3_000

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

    private val sessionManager: MediaSessionManager
        get() = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listenerComponent: ComponentName
        get() = ComponentName(context, FridayNotificationListener::class.java)

    suspend fun perform(action: MediaAction, appHint: String?, russian: Boolean = true): String {
        fun say(ru: String, en: String) = if (russian) ru else en
        val wanted = appHint?.let { MusicApps.match(it) }

        val sessions = activeSessions()
            ?: return grantNotificationAccess(russian)

        // Named app first; otherwise whatever is playing, otherwise anything.
        val target = when {
            wanted != null -> sessions.firstOrNull { it.packageName == wanted.packageName }
            else -> sessions.firstOrNull { isPlaying(it) } ?: sessions.firstOrNull()
        }

        if (target == null) {
            return when {
                // Asked for a specific app that isn't running: start it, since
                // "включи Spotify" plainly means "get Spotify going".
                wanted != null && action.startsPlayback -> start(wanted, russian)
                action.startsPlayback -> fallbackKey(action, russian)
                else -> say("Сейчас ничего не играет", "Nothing is playing")
            }
        }

        return try {
            transport(target, action, russian)
        } catch (e: Exception) {
            Log.e(TAG, "Transport control failed: ${e.message}")
            fallbackKey(action, russian)
        }
    }


    private fun transport(target: MediaController, action: MediaAction, russian: Boolean): String {
        fun say(ru: String, en: String) = if (russian) ru else en
        val controls = target.transportControls
        return when (action) {
            MediaAction.PLAY -> { pausedByUser = null; controls.play(); say("Играет", "Playing") }
            MediaAction.PAUSE -> { pause(target); say("Пауза", "Paused") }
            // Pause, not stop: Spotify and others ignore stop(), and a stopped
            // player cannot be resumed with "продолжи".
            MediaAction.STOP -> { pause(target); say("Остановила", "Stopped") }
            MediaAction.NEXT -> { controls.skipToNext(); say("Следующий трек", "Next track") }
            MediaAction.PREVIOUS -> { controls.skipToPrevious(); say("Предыдущий трек", "Previous track") }
            MediaAction.TOGGLE ->
                if (isPlaying(target)) { pause(target); say("Пауза", "Paused") }
                else { pausedByUser = null; controls.play(); say("Играет", "Playing") }
        }
    }

    private fun pause(target: MediaController) {
        target.transportControls.pause()
        pausedByUser = target.packageName to System.currentTimeMillis()
    }

    /** "Сейчас играет «Believer» — Imagine Dragons, Spotify." */
    fun nowPlaying(russian: Boolean): String {
        val sessions = activeSessions() ?: return grantNotificationAccess(russian)
        // Paused while Friday listens, so "the playing one" may be paused right now:
        // the first session is the one most recently in use.
        val session = sessions.firstOrNull { isPlaying(it) } ?: sessions.firstOrNull()
        val nothing = if (russian) "Сейчас ничего не играет" else "Nothing is playing"
        val meta = session?.metadata ?: return nothing
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return nothing
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val app = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(session.packageName, 0)
            ).toString()
        }.getOrNull()
        val by = artist?.let { " — $it" }.orEmpty()
        val where = app?.let { ", $it" }.orEmpty()
        return if (russian) "Сейчас играет «$title»$by$where" else "Now playing \"$title\"$by$where"
    }

    /** Called when Friday gives the audio back: re-pauses a player the user paused, if it started again. */
    fun keepPaused() {
        val (pkg, at) = pausedByUser ?: return
        pausedByUser = null
        if (System.currentTimeMillis() - at > KEEP_PAUSED_MS) return
        activeSessions()?.firstOrNull { it.packageName == pkg && isPlaying(it) }?.let {
            Log.i(TAG, "$pkg resumed on its own after being paused; pausing again")
            it.transportControls.pause()
        }
    }

    /** Null when notification-listener access has not been granted. */
    private fun activeSessions(): List<MediaController>? = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        Log.w(TAG, "No notification listener access: ${e.message}")
        null
    } catch (e: Exception) {
        Log.e(TAG, "Could not read media sessions: ${e.message}")
        emptyList()
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING

    /**
     * Starts a player that isn't running, and makes it play.
     *
     * Opening it isn't enough: a closed player has no session yet, so there
     * was nothing to press play on and the music never started. It is now
     * started with a play request (`MEDIA_PLAY_FROM_SEARCH` with an empty
     * query, which Android defines as "play something"); once its session
     * appears, play is pressed if it is still paused, and the reply reports
     * what the session says, not what was hoped.
     */
    private suspend fun start(player: MusicApps.Player, russian: Boolean): String {
        val pm = context.packageManager
        val playRequest = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(player.packageName)
            .putExtra(SearchManager.QUERY, "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = playRequest.takeIf { it.resolveActivity(pm) != null }
            ?: pm.getLaunchIntentForPackage(player.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return PlayerStart.reply(PlayerStart.Outcome.NOT_INSTALLED, player.label, russian)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Start ${player.packageName} failed: ${e.message}")
            return PlayerStart.reply(PlayerStart.Outcome.NOT_INSTALLED, player.label, russian)
        }
        pausedByUser = null
        // A cold-started player needs a few seconds to publish its session.
        val session = pollFor(SESSION_WAIT_MS) {
            activeSessions()?.firstOrNull { it.packageName == player.packageName }
        }
        val outcome = when {
            session == null -> PlayerStart.Outcome.OPENED_UNVERIFIED
            else -> {
                if (!isPlaying(session)) session.transportControls.play()
                val playing = pollFor(PLAYING_WAIT_MS) { session.takeIf(::isPlaying) } != null
                if (playing) PlayerStart.Outcome.PLAYING else PlayerStart.Outcome.OPENED_NOT_PLAYING
            }
        }
        return PlayerStart.reply(outcome, player.label, russian)
    }

    /**
     * For players that publish no media session. Reaches whichever app the
     * system considers the media button owner, so it cannot be aimed.
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
            if (russian) "Готово" else "Done"
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

private const val POLL_MS = 250

/** Asks [probe] every quarter second until it answers or [totalMs] pass. */
private suspend fun <T : Any> pollFor(totalMs: Int, probe: () -> T?): T? {
    repeat(totalMs / POLL_MS) {
        probe()?.let { return it }
        delay(POLL_MS.toLong())
    }
    return probe()
}
