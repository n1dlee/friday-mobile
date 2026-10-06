package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.friday.ai.core.people.Channel
import java.time.ZoneId
import java.util.Locale

/**
 * What Friday knows about where the user is and what the phone can do.
 *
 * Decisions that used to be fixed in code — which maps, which messenger,
 * which country "local" means — are made from this instead. It is also told
 * to the model, so "напиши маме" or "включи что-нибудь" are reasoned about
 * with the same facts.
 *
 * Installed apps are grouped by the category each app declares about itself
 * (audio, video, maps, social), so a player Friday has never heard of still
 * counts as a player.
 */
class DeviceContext(private val context: Context) {

    /** An installed app the user can open. */
    data class App(val packageName: String, val label: String)

    data class Apps(
        val music: List<App>,
        val video: List<App>,
        val maps: List<App>,
        val messengers: Set<Channel>
    )

    private companion object {
        /** Apps are installed rarely; reading them on every request is wasted work. */
        const val APPS_TTL_MS = 5 * 60 * 1000L
        const val YOUTUBE = "com.google.android.youtube"
    }

    private var cachedApps: Apps? = null
    private var cachedAt = 0L

    private val telephony: TelephonyManager?
        get() = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /** Country of the user's own number: what "local" means for calls and SMS. */
    fun homeRegion(): String? =
        telephony?.simCountryIso?.takeIf { it.length == 2 }?.uppercase()
            ?: currentRegion()
            ?: Locale.getDefault().country.takeIf { it.length == 2 }

    /** Country the phone is in right now, from the mobile network. Differs from home when roaming. */
    fun currentRegion(): String? = telephony?.networkCountryIso?.takeIf { it.length == 2 }?.uppercase()

    fun apps(): Apps {
        val now = SystemClock.elapsedRealtime()
        cachedApps?.takeIf { now - cachedAt < APPS_TTL_MS }?.let { return it }
        return readApps().also {
            cachedApps = it
            cachedAt = now
        }
    }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun readApps(): Apps {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }

        fun of(category: Int) = launchable
            .filter { it.category == category }
            .map { App(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label }

        // Not every app declares a category, so the well-known ones are added
        // by name when installed.
        fun known(packages: List<Pair<String, String>>) =
            packages.filter { (pkg, _) -> isInstalled(pkg) }.map { (pkg, label) -> App(pkg, label) }

        return Apps(
            music = (of(ApplicationInfo.CATEGORY_AUDIO) + known(MusicApps.KNOWN.map { it.packageName to it.label }))
                .distinctBy { it.packageName },
            video = (of(ApplicationInfo.CATEGORY_VIDEO) + known(listOf(YOUTUBE to "YouTube")))
                .distinctBy { it.packageName },
            maps = of(ApplicationInfo.CATEGORY_MAPS),
            messengers = Channel.entries
                .filter { ch -> ch.packages.any { isInstalled(it) } }
                .toSet()
        )
    }

    /** The facts, in a few lines for the model's system prompt. */
    fun describe(): String {
        val home = homeRegion()
        val here = currentRegion()
        val apps = apps()
        fun country(code: String?) = code?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) } ?: "unknown"
        fun names(list: List<App>) = list.joinToString { it.label }.ifEmpty { "none" }
        return buildString {
            append("Phone context: the user's own number is from ${country(home)}")
            if (here != null && here != home) append("; they are currently in ${country(here)} (roaming)")
            append("; time zone ${ZoneId.systemDefault().id}.")
            val messengers = apps.messengers.filter { it != Channel.SMS }.joinToString { it.label }.ifEmpty { "none" }
            append(" Installed — messengers: $messengers")
            append("; music: ${names(apps.music)}; video: ${names(apps.video)}; maps: ${names(apps.maps)}.")
        }
    }
}
