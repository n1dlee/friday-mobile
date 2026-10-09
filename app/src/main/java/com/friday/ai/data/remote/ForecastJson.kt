package com.friday.ai.data.remote

import com.friday.ai.core.WeatherReport
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Open-Meteo's forecast answer read into a [WeatherReport.Forecast]: now, every hour, every day. */
internal object ForecastJson {

    fun parse(place: String, root: JsonObject): WeatherReport.Forecast? {
        val current = root["current"]?.jsonObject ?: return null
        val hourly = root["hourly"]?.jsonObject ?: return null
        val now = nowOf(current) ?: return null
        val hours = hourly["time"]?.jsonArray.orEmpty().indices.mapNotNull { hourOf(hourly, it) }
        val daily = root["daily"]?.jsonObject
        val days = daily?.get("time")?.jsonArray.orEmpty().indices.mapNotNull { dayOf(daily!!, it) }
        return WeatherReport.Forecast(place, now, hours, days)
    }

    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

    private fun JsonObject.at(key: String, i: Int) =
        this[key]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull()

    private fun JsonObject.text(key: String, i: Int) = this[key]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.content

    private fun nowOf(c: JsonObject): WeatherReport.Hour? {
        val temperature = c.num("temperature_2m") ?: return null
        return WeatherReport.Hour(
            time = LocalDateTime.parse(c["time"]?.jsonPrimitive?.content ?: return null),
            temperature = temperature,
            feelsLike = c.num("apparent_temperature") ?: temperature,
            precipitationChance = null,
            precipitationMm = 0.0,
            code = c.num("weather_code")?.toInt() ?: -1,
            windKmh = c.num("wind_speed_10m") ?: 0.0
        )
    }

    private fun hourOf(h: JsonObject, i: Int): WeatherReport.Hour? {
        val temperature = h.at("temperature_2m", i) ?: return null
        return WeatherReport.Hour(
            time = LocalDateTime.parse(h.text("time", i) ?: return null),
            temperature = temperature,
            feelsLike = h.at("apparent_temperature", i) ?: temperature,
            precipitationChance = h.at("precipitation_probability", i)?.toInt(),
            precipitationMm = h.at("precipitation", i) ?: 0.0,
            code = h.at("weather_code", i)?.toInt() ?: -1,
            windKmh = h.at("wind_speed_10m", i) ?: 0.0
        )
    }

    private fun dayOf(d: JsonObject, i: Int): WeatherReport.Day? {
        val date = d.text("time", i) ?: return null
        return WeatherReport.Day(
            date = LocalDate.parse(date),
            min = d.at("temperature_2m_min", i) ?: return null,
            max = d.at("temperature_2m_max", i) ?: return null,
            code = d.at("weather_code", i)?.toInt() ?: -1,
            precipitationMm = d.at("precipitation_sum", i) ?: 0.0,
            precipitationChance = d.at("precipitation_probability_max", i)?.toInt(),
            windMaxKmh = d.at("wind_speed_10m_max", i) ?: 0.0
        )
    }
}
