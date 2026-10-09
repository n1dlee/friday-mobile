package com.friday.ai.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.ai.core.WeatherReport
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.WeatherApiService
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Weather for where the user is.
 *
 * The place is, in order: the one named; the city the owner set (in
 * Settings or by saying "мой город — Геттисберг"); where the phone is, if
 * location is allowed; the city of the phone's time zone. The owner's own
 * choice comes before the phone's guess: the time zone said "New York" for
 * an owner in Gettysburg, and a stale fix can be a city left days ago.
 */
class WeatherHere(
    private val context: Context,
    private val api: WeatherApiService,
    private val prefDao: UserPreferenceDao
) {

    companion object {
        private const val TAG = "WeatherHere"

        /** "America/New_York" → "New York"; null for zones that name no city ("UTC"). */
        fun cityOfZone(zone: ZoneId): String? =
            zone.id.takeIf { '/' in it }?.substringAfterLast('/')?.replace('_', ' ')

        /** A fix older than this may be another city. */
        private const val MAX_FIX_AGE_MS = 3 * 60 * 60 * 1000L

        /** How long to wait for a fresh fix before falling back. */
        private const val FIX_TIMEOUT_MS = 4_000L

        /** Days fetched: today, tomorrow and the day after. */
        private const val FORECAST_DAYS = 3

        const val PREF_HOME_CITY = "home_city"

        /** A name is never cut below this many letters looking for its case ending. */
        private const val MIN_STEM = 3

        /**
         * Spellings to look a spoken city up by: "в Геттисберге" is said, the
         * map knows "Геттисберг". The name as said is tried first.
         */
        fun lookupForms(said: String): List<String> {
            val name = said.trim().trim('.', ',', '!', '?', '«', '»', '"').removePrefix("в ").removePrefix("во ").trim()
            val forms = mutableListOf(name)
            listOf("е", "у", "а", "и", "ом", "ой").forEach { ending ->
                if (name.length > ending.length + MIN_STEM && name.endsWith(ending)) {
                    forms += name.dropLast(ending.length)
                }
            }
            if (name.endsWith("ии")) forms += name.dropLast(1) + "я"
            return forms.distinct()
        }
    }

    /**
     * The whole answer — temperature and feel, wind, when it rains and how
     * much — or null if the weather could not be fetched. [dayOffset] 0 = now.
     */
    suspend fun summary(place: String?, dayOffset: Int, russian: Boolean): String? {
        // A named place that can't be found is not swapped for another one.
        val resolved = if (place != null) find(place, russian) else where(russian)
        resolved ?: return null
        return api.forecast(resolved, FORECAST_DAYS)?.let { WeatherReport.spoken(it, dayOffset, russian) }
    }

    /** The city the owner set, or null. */
    suspend fun homeCity(): String? = prefDao.get(PREF_HOME_CITY)?.takeIf { it.isNotBlank() }

    /**
     * Makes [said] the owner's city, as the map spells it ("в Геттисберге"
     * → "Геттисберг"); null, and nothing saved, if no such city is found.
     * Blank clears it.
     */
    suspend fun setHomeCity(said: String, russian: Boolean): String? {
        if (said.isBlank()) {
            prefDao.set(UserPreferenceEntity(PREF_HOME_CITY, ""))
            return ""
        }
        val place = find(said, russian) ?: return null
        prefDao.set(UserPreferenceEntity(PREF_HOME_CITY, place.name))
        return place.name
    }

    private suspend fun find(said: String, russian: Boolean): WeatherApiService.Place? =
        lookupForms(said).firstNotNullOfOrNull { api.geocode(it, russian) }

    /** No place named: the owner's city, the phone's location, the time zone's city — in that order. */
    private suspend fun where(russian: Boolean): WeatherApiService.Place? =
        homeCity()?.let { find(it, russian) }
            ?: here(russian)
            ?: cityOfZone(ZoneId.systemDefault())?.let { api.geocode(it, russian) }

    private suspend fun here(russian: Boolean): WeatherApiService.Place? {
        val fix = lastFix() ?: freshFix() ?: return null
        val name = withContext(Dispatchers.IO) { cityAt(fix, russian) }
            ?: if (russian) "вашем районе" else "your area"
        return WeatherApiService.Place(name, fix.latitude, fix.longitude)
    }

    @Suppress("DEPRECATION")
    private fun cityAt(fix: Location, russian: Boolean): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(context, if (russian) Locale("ru") else Locale.ENGLISH)
            .getFromLocation(fix.latitude, fix.longitude, 1)
            ?.firstOrNull()?.locality
    } catch (e: Exception) {
        Log.w(TAG, "No city name for the fix: ${e.message}")
        null
    }

    private fun locationAllowed(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * A new network fix when no recent one exists (Android 11+). Coarse is
     * enough for a city, and the network provider answers in a second or two
     * without waking GPS.
     */
    @SuppressLint("MissingPermission")
    private suspend fun freshFix(): Location? {
        if (!locationAllowed() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        }
    }

    /** The newest recent fix any provider has; no GPS is woken for it. */
    @SuppressLint("MissingPermission")
    private fun lastFix(): Location? {
        if (!locationAllowed()) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val now = System.currentTimeMillis()
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time < MAX_FIX_AGE_MS }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read location: ${e.message}")
            null
        }
    }
}
