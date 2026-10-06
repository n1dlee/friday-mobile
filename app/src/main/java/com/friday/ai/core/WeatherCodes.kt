package com.friday.ai.core

/**
 * Maps WMO weather codes (what Open-Meteo returns) to something speakable.
 *
 * Kept separate and pure so the wording can be tested and tweaked without
 * touching networking.
 */
object WeatherCodes {

    /** Short description in Russian or English, chosen by [russian]. */
    fun describe(code: Int, russian: Boolean): String = when (code) {
        0 -> if (russian) "ясно" else "clear"
        1 -> if (russian) "преимущественно ясно" else "mostly clear"
        2 -> if (russian) "переменная облачность" else "partly cloudy"
        3 -> if (russian) "пасмурно" else "overcast"
        45, 48 -> if (russian) "туман" else "fog"
        51, 53, 55 -> if (russian) "морось" else "drizzle"
        56, 57 -> if (russian) "ледяная морось" else "freezing drizzle"
        61 -> if (russian) "небольшой дождь" else "light rain"
        63 -> if (russian) "дождь" else "rain"
        65 -> if (russian) "сильный дождь" else "heavy rain"
        66, 67 -> if (russian) "ледяной дождь" else "freezing rain"
        71 -> if (russian) "небольшой снег" else "light snow"
        73 -> if (russian) "снег" else "snow"
        75 -> if (russian) "сильный снег" else "heavy snow"
        77 -> if (russian) "снежная крупа" else "snow grains"
        80, 81 -> if (russian) "ливень" else "rain showers"
        82 -> if (russian) "сильный ливень" else "violent rain showers"
        85, 86 -> if (russian) "снегопад" else "snow showers"
        95 -> if (russian) "гроза" else "thunderstorm"
        96, 99 -> if (russian) "гроза с градом" else "thunderstorm with hail"
        else -> if (russian) "неопределённая погода" else "unclear conditions"
    }

    /**
     * Builds the one-sentence answer Friday speaks. Deliberately short —
     * spoken replies are capped at a sentence.
     */
    fun summarize(
        place: String,
        temperature: Double,
        feelsLike: Double,
        code: Int,
        russian: Boolean
    ): String {
        val t = Math.round(temperature).toInt()
        val feels = Math.round(feelsLike).toInt()
        val condition = describe(code, russian)
        val mentionFeels = Math.abs(feels - t) >= 3

        return if (russian) {
            buildString {
                append("В $place сейчас $t°, $condition")
                if (mentionFeels) append(", ощущается как $feels°")
                append(".")
            }
        } else {
            buildString {
                append("It's $t° in $place, $condition")
                if (mentionFeels) append(", feels like $feels°")
                append(".")
            }
        }
    }
}
