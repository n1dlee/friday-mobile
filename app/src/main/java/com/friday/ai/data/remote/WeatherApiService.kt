package com.friday.ai.data.remote

import android.util.Log
import com.friday.ai.core.WeatherCodes
import com.friday.ai.core.WeatherReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Live weather from Open-Meteo — no API key, no signup, no quota to babysit.
 *
 * This exists because the LLM confidently invents weather when asked: with no
 * live data it answered "25 degrees" for Tashkent while it was actually 38.
 * Anything the model can't know must come from a real source.
 */
class WeatherApiService private constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    companion object {
        private const val TAG = "WeatherApiService"
        private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val MAX_DAYS = 7

        fun create(): WeatherApiService = WeatherApiService(
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build(),
            Json { ignoreUnknownKeys = true; isLenient = true }
        )
    }

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    data class CurrentWeather(
        val place: String,
        val temperature: Double,
        val feelsLike: Double,
        val weatherCode: Int,
        val windSpeed: Double,
        val humidity: Int
    ) {
        fun toSpokenSummary(russian: Boolean): String =
            WeatherCodes.summarize(place, temperature, feelsLike, weatherCode, russian)
    }

    /** Resolves a place name to coordinates. Null when the place is unknown. */
    suspend fun geocode(name: String, russian: Boolean): Place? = withContext(Dispatchers.IO) {
        try {
            val lang = if (russian) "ru" else "en"
            val url = "$GEOCODE_URL?name=${java.net.URLEncoder.encode(name, "UTF-8")}" +
                "&count=1&language=$lang&format=json"
            val body = get(url) ?: return@withContext null
            val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
            val first = results?.firstOrNull()?.jsonObject ?: return@withContext null
            Place(
                name = first["name"]?.jsonPrimitive?.content ?: name,
                latitude = first["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return@withContext null,
                longitude = first["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return@withContext null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed for '$name': ${e.message}")
            null
        }
    }

    suspend fun currentWeather(place: Place): CurrentWeather? = withContext(Dispatchers.IO) {
        try {
            val url = "$FORECAST_URL?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,apparent_temperature,weather_code," +
                "wind_speed_10m,relative_humidity_2m&timezone=auto"
            val body = get(url) ?: return@withContext null
            val current = json.parseToJsonElement(body).jsonObject["current"]?.jsonObject
                ?: return@withContext null

            fun num(key: String): Double? =
                current[key]?.jsonPrimitive?.content?.toDoubleOrNull()

            CurrentWeather(
                place = place.name,
                temperature = num("temperature_2m") ?: return@withContext null,
                feelsLike = num("apparent_temperature") ?: num("temperature_2m")!!,
                weatherCode = num("weather_code")?.toInt() ?: -1,
                windSpeed = num("wind_speed_10m") ?: 0.0,
                humidity = num("relative_humidity_2m")?.toInt() ?: 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "Forecast failed: ${e.message}")
            null
        }
    }

    data class DayForecast(
        val place: String,
        val dayOffset: Int,
        val minTemp: Double,
        val maxTemp: Double,
        val weatherCode: Int
    ) {
        fun toSpokenSummary(russian: Boolean): String {
            val condition = WeatherCodes.describe(weatherCode, russian)
            val lo = Math.round(minTemp).toInt()
            val hi = Math.round(maxTemp).toInt()
            val day = when (dayOffset) {
                1 -> if (russian) "Завтра" else "Tomorrow"
                2 -> if (russian) "Послезавтра" else "The day after tomorrow"
                else -> if (russian) "Сегодня" else "Today"
            }
            return if (russian) "$day в $place $condition, от $lo° до $hi°."
            else "$day in $place: $condition, $lo° to $hi°."
        }
    }

    /** Geocode + fetch in one step. */
    suspend fun weatherFor(placeName: String, russian: Boolean): CurrentWeather? {
        val place = geocode(placeName, russian) ?: return null
        return currentWeather(place)
    }

    /** Forecast for a day ahead. [dayOffset] 1 = tomorrow. */
    suspend fun forecastFor(
        placeName: String,
        dayOffset: Int,
        russian: Boolean
    ): DayForecast? {
        val place = geocode(placeName, russian) ?: return null
        return forecastAt(place, dayOffset)
    }

    /** Forecast for a day ahead at a known place. [dayOffset] 1 = tomorrow. */
    suspend fun forecastAt(place: Place, dayOffset: Int): DayForecast? = withContext(Dispatchers.IO) {
        try {
            val days = (dayOffset + 1).coerceIn(1, 7)
            val url = "$FORECAST_URL?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
                "&forecast_days=$days&timezone=auto"
            val body = get(url) ?: return@withContext null
            val daily = json.parseToJsonElement(body).jsonObject["daily"]?.jsonObject
                ?: return@withContext null

            fun seriesAt(key: String): Double? = daily[key]?.jsonArray
                ?.getOrNull(dayOffset)?.jsonPrimitive?.content?.toDoubleOrNull()

            DayForecast(
                place = place.name,
                dayOffset = dayOffset,
                minTemp = seriesAt("temperature_2m_min") ?: return@withContext null,
                maxTemp = seriesAt("temperature_2m_max") ?: return@withContext null,
                weatherCode = seriesAt("weather_code")?.toInt() ?: -1
            )
        } catch (e: Exception) {
            Log.w(TAG, "Forecast failed: ${e.message}")
            null
        }
    }

    /**
     * Now, every hour and every day up to [days] ahead, in one request — all
     * a full answer needs ([com.friday.ai.core.WeatherReport]). Times are the
     * place's own (timezone=auto).
     */
    suspend fun forecast(place: Place, days: Int): WeatherReport.Forecast? = withContext(Dispatchers.IO) {
        try {
            val url = "$FORECAST_URL?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation," +
                "weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum," +
                "precipitation_probability_max,wind_speed_10m_max" +
                "&forecast_days=${days.coerceIn(1, MAX_DAYS)}&timezone=auto"
            val body = get(url) ?: return@withContext null
            ForecastJson.parse(place.name, json.parseToJsonElement(body).jsonObject)
        } catch (e: Exception) {
            Log.w(TAG, "Forecast failed: ${e.message}")
            null
        }
    }

    suspend fun weatherAt(latitude: Double, longitude: Double, label: String): CurrentWeather? =
        currentWeather(Place(label, latitude, longitude))

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "FridayAI/1.0 (personal assistant)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                return null
            }
            return response.body?.string()
        }
    }
}
