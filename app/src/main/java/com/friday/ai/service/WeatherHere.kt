package com.friday.ai.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.WeatherApiService
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Weather for where the user is.
 *
 * "Какая погода?" without a place used to mean Tashkent, written into the
 * code — wrong the moment the user is anywhere else. The place is now, in
 * order: the one named; where the phone last was; the home city the user
 * set; the city of the phone's time zone.
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
    }

    /** Spoken summary, or null if the weather could not be fetched. [dayOffset] 0 = now. */
    suspend fun summary(place: String?, dayOffset: Int, russian: Boolean): String? {
        // A named place that can't be found is not swapped for another one.
        val resolved = if (place != null) {
            api.geocode(place, russian) ?: return null
        } else {
            here(russian) ?: fallbackCity()?.let { api.geocode(it, russian) } ?: return null
        }
        return if (dayOffset > 0) {
            api.forecastAt(resolved, dayOffset)?.toSpokenSummary(russian)
        } else {
            api.currentWeather(resolved)?.toSpokenSummary(russian)
        }
    }

    /** The user's own setting first; otherwise the city the time zone is named after. */
    private suspend fun fallbackCity(): String? =
        prefDao.get("home_city") ?: cityOfZone(ZoneId.systemDefault())

    private suspend fun here(russian: Boolean): WeatherApiService.Place? {
        val fix = lastFix() ?: return null
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

    /** The newest recent fix any provider has; no GPS is woken for it. */
    @SuppressLint("MissingPermission")
    private fun lastFix(): Location? {
        val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!allowed) return null
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
