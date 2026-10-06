package com.friday.ai.core

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.domain.model.MediaKind

/**
 * "Включи Imagine Dragons", "найди на ютубе обзор айфона": something to play,
 * found and started in the right app.
 *
 * Which app: the one named; otherwise the one the user named last time for
 * this kind of thing; otherwise the best installed one — a streaming service
 * that can search a catalogue before a player of local files.
 */
class MediaSearch(
    private val context: Context,
    private val device: DeviceContext,
    private val prefDao: UserPreferenceDao
) {

    /** Picks the app; pure, so the order of preference can be tested. */
    object Chooser {
        /** Catalogue apps first: they can find anything, a local player only what is on the phone. */
        val MUSIC_ORDER = listOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", "ru.yandex.music",
            "com.apple.android.music", "deezer.android.app", "com.soundcloud.android", "com.sec.android.app.music"
        )
        const val YOUTUBE = "com.google.android.youtube"
        val VIDEO_ORDER = listOf(YOUTUBE)

        fun choose(
            kind: MediaKind,
            installed: List<DeviceContext.App>,
            named: String?,
            usual: String?
        ): DeviceContext.App? {
            named?.let { hint ->
                val h = SpokenText.normalise(hint)
                val byName = MusicApps.match(h)?.packageName
                    ?: YOUTUBE.takeIf { kind == MediaKind.VIDEO && Regex("ютуб|ютюб|youtube").containsMatchIn(h) }
                installed.firstOrNull { it.packageName == byName || SpokenText.normalise(it.label) in h }
                    ?.let { return it }
            }
            installed.firstOrNull { it.packageName == usual }?.let { return it }
            val order = if (kind == MediaKind.MUSIC) MUSIC_ORDER else VIDEO_ORDER
            return installed.sortedBy { order.indexOf(it.packageName).let { i -> if (i < 0) order.size else i } }
                .firstOrNull()
        }
    }

    private fun usualKey(kind: MediaKind) = "player_" + kind.name.lowercase()

    suspend fun play(query: String, kind: MediaKind, appHint: String?, russian: Boolean): String {
        val apps = device.apps().let { if (kind == MediaKind.MUSIC) it.music else it.video }
        val app = Chooser.choose(kind, apps, appHint, prefDao.get(usualKey(kind)))
            ?: return if (russian) "Не нашла, чем это воспроизвести" else "No app to play that"
        // Named by the user: their choice for this kind of thing from now on.
        if (appHint != null) prefDao.set(UserPreferenceEntity(usualKey(kind), app.packageName))

        val pm = context.packageManager
        val playFromSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.packageName)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        val search = Intent(Intent.ACTION_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, query)

        return when {
            kind == MediaKind.MUSIC && playFromSearch.resolveActivity(pm) != null -> {
                context.startActivity(playFromSearch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (russian) "Включаю «$query» в ${app.label}" else "Playing \"$query\" in ${app.label}"
            }
            search.resolveActivity(pm) != null -> {
                context.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (russian) "Открыла поиск «$query» в ${app.label} — выберите, что включить"
                else "Opened a search for \"$query\" in ${app.label} — pick what to play"
            }
            else -> {
                pm.getLaunchIntentForPackage(app.packageName)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                if (russian) "${app.label} не принимает поиск извне — открыла его, найдите «$query» сами"
                else "${app.label} doesn't take searches from other apps — opened it for you to find \"$query\""
            }
        }
    }
}
