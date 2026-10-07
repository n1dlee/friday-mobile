package com.friday.ai.core

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.domain.model.MediaKind

/**
 * Gets music playing — in the player the user named, the one they usually
 * use, or the best one installed — and only says "playing" once a player's
 * session says so.
 *
 * Used both for "включи музыку (в Spotify)" and for "включи Believer": the
 * same hand-off ([HandOff]) either way. A player is started with
 * `MEDIA_PLAY_FROM_SEARCH` (an empty query means "play something"); once
 * its session appears, play is pressed if it is still paused; if a cold
 * start swallowed the first request, it is sent once more.
 */
class PlaybackStarter(
    private val context: Context,
    private val device: DeviceContext,
    private val prefs: UserPreferenceDao,
    private val sessions: MediaSessions
) {

    private companion object {
        const val TAG = "PlaybackStarter"
        const val PREF_USUAL = "player_music"

        /** A cold-started player takes a few seconds to publish its session. */
        const val WAIT_MS = 6_000L
        const val RETRY_WAIT_MS = 4_000L
    }

    /** The installed player named in [phrase], if it names one (any installed audio app, not only known ones). */
    fun named(phrase: String): DeviceContext.App? = MediaSearch.Chooser.named(phrase, device.apps().music)

    /** The player to use when none is named: the usual one, else the best installed. */
    suspend fun default(): DeviceContext.App? =
        MediaSearch.Chooser.choose(MediaKind.MUSIC, device.apps().music, named = null, usual = prefs.get(PREF_USUAL))

    /** Named by the user: their choice from now on. */
    suspend fun remember(app: DeviceContext.App) {
        prefs.set(UserPreferenceEntity(PREF_USUAL, app.packageName))
    }

    /**
     * Starts [app] playing [query] (null: whatever it plays by default).
     * Returns the reply, or null if [app] can't take a play request at all —
     * the caller then falls back to opening a search.
     */
    suspend fun start(app: DeviceContext.App, query: String?, russian: Boolean): String? {
        val request = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.packageName)
            .putExtra(SearchManager.QUERY, query.orEmpty())
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        val intent = when {
            request.resolveActivity(pm) != null -> request
            // Without a query, opening the player and pressing play on its session works too.
            query == null -> pm.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else -> null
        }
        if (intent == null) {
            // With a query the caller opens the app's search instead; without one there's nothing to start.
            return if (query != null) null
            else PlayerStart.reply(PlayerStart.Outcome.NOT_INSTALLED, app.label, russian)
        }

        var pressedPlay = false
        val result = try {
            HandOff.run(
                send = { context.startActivity(intent) },
                happened = {
                    val session = sessions.of(app.packageName)
                    when {
                        session == null -> false
                        sessions.isPlaying(session) -> true
                        else -> {
                            // Up but paused: one press, then keep watching.
                            if (!pressedPlay) session.transportControls.play()
                            pressedPlay = true
                            false
                        }
                    }
                },
                waitMs = WAIT_MS,
                retryWaitMs = RETRY_WAIT_MS
            )
        } catch (e: Exception) {
            Log.e(TAG, "Starting ${app.packageName} failed: ${e.message}")
            return PlayerStart.reply(PlayerStart.Outcome.NOT_INSTALLED, app.label, russian)
        }
        if (result == HandOff.Result.DONE_ON_RETRY) {
            Log.i(TAG, "${app.packageName} needed the request twice (cold start)")
        }
        val outcome = when {
            result != HandOff.Result.NOT_SEEN -> PlayerStart.Outcome.PLAYING
            sessions.of(app.packageName) != null -> PlayerStart.Outcome.OPENED_NOT_PLAYING
            else -> PlayerStart.Outcome.OPENED_UNVERIFIED
        }
        return PlayerStart.reply(outcome, app.label, russian, query)
    }
}
