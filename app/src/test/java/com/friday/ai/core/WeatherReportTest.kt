package com.friday.ai.core

import com.friday.ai.data.remote.ForecastJson
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.WeatherHere
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val today: LocalDate = LocalDate.of(2026, 10, 8)

/** A day of hours: 12°→20°, 15 km/h wind, rain at [rainy] hours (3 mm each, 80%). */
private fun hours(
    date: LocalDate,
    rainy: Set<Int> = emptySet(),
    code: Int = 61,
    chance: Int = 10
): List<WeatherReport.Hour> = (0..23).map { h ->
    val wet = h in rainy
    WeatherReport.Hour(
        time = date.atTime(h, 0),
        temperature = 12.0 + h / 3.0,
        feelsLike = 10.0 + h / 3.0,
        precipitationChance = if (wet) 80 else chance,
        precipitationMm = if (wet) 1.0 else 0.0,
        code = if (wet) code else 3,
        windKmh = 15.0
    )
}

private fun forecast(
    now: LocalDateTime,
    todayRain: Set<Int> = emptySet(),
    tomorrowRain: Set<Int> = emptySet(),
    code: Int = 61
) = WeatherReport.Forecast(
    place = "Геттисберг",
    now = WeatherReport.Hour(now, 14.2, 11.8, null, 0.0, 3, 14.4),
    hours = hours(today, todayRain, code) + hours(today.plusDays(1), tomorrowRain, code),
    days = listOf(
        WeatherReport.Day(today, 9.0, 20.0, 3, todayRain.size.toDouble(), 80, 20.0),
        WeatherReport.Day(today.plusDays(1), 9.0, 23.0, 3, tomorrowRain.size.toDouble(), 70, 21.6)
    )
)

class WeatherReportTest {

    @Test
    fun `now - temperature, feel, wind, the rain to come and the next hours`() {
        val said = WeatherReport.spoken(forecast(today.atTime(11, 20), todayRain = setOf(14, 15, 16)), 0, true)
        assertEquals(
            "Сейчас в Геттисберг +14°, ощущается как +12°, пасмурно, ветер 4 м/с. " +
                "Дождь с 14:00 до 17:00, около 3 мм, вероятность 80%. В ближайшие часы от +16° до +18°.",
            said
        )
    }

    @Test
    fun `tomorrow - range, sky, when it rains, how much, wind`() {
        val said = WeatherReport.spoken(forecast(today.atTime(20, 10), tomorrowRain = setOf(14, 15, 16, 17)), 1, true)
        assertEquals(
            "Завтра в Геттисберг от +9° до +23°, пасмурно. Дождь с 14:00 до 18:00, около 4 мм, вероятность 80%. " +
                "Ветер до 6 м/с.",
            said
        )
    }

    @Test
    fun `a dry day says so, and a real chance is mentioned`() {
        assertTrue(WeatherReport.spoken(forecast(today.atTime(9, 0)), 0, true).contains("Без осадков до конца дня."))
        val base = forecast(today.atTime(9, 0))
        val chancy = base.copy(hours = base.hours.map { it.copy(precipitationChance = 30) })
        assertTrue(WeatherReport.spoken(chancy, 1, true).contains("Осадки маловероятны, вероятность 30%."))
    }

    @Test
    fun `rain that stops and starts, snow, and the evening that runs to midnight`() {
        val broken = WeatherReport.spoken(forecast(today.atTime(8, 0), tomorrowRain = setOf(9, 10, 18, 19)), 1, true)
        assertTrue(broken, broken.contains("Дождь с 9:00 до 20:00 с перерывами"))
        val night = forecast(today.atTime(8, 0), tomorrowRain = setOf(21, 22, 23), code = 73)
        val snow = WeatherReport.spoken(night, 1, true)
        assertTrue(snow, snow.contains("Снег с 21:00 до полуночи"))
    }

    @Test
    fun `in English, km per hour`() {
        val said = WeatherReport.spoken(forecast(today.atTime(20, 10), tomorrowRain = setOf(14, 15)), 1, false)
        assertEquals(
            "Tomorrow in Геттисберг: +9° to +23°, overcast. Rain from 14:00 to 16:00, about 2 mm, 80% chance. " +
                "Wind up to 22 km/h.",
            said
        )
    }

    @Test
    fun `an Open-Meteo answer is read into the forecast`() {
        val body = """
            {"current":{"time":"2026-10-08T20:00","temperature_2m":14.2,"apparent_temperature":11.8,
              "weather_code":3,"wind_speed_10m":14.4},
             "hourly":{"time":["2026-10-08T20:00","2026-10-08T21:00"],"temperature_2m":[14.0,13.1],
              "apparent_temperature":[12.0,11.0],"precipitation_probability":[10,85],"precipitation":[0.0,1.4],
              "weather_code":[3,63],"wind_speed_10m":[14.0,18.0]},
             "daily":{"time":["2026-10-08"],"temperature_2m_max":[20.0],"temperature_2m_min":[9.0],
              "weather_code":[63],"precipitation_sum":[1.4],"precipitation_probability_max":[85],
              "wind_speed_10m_max":[18.0]}}
        """.trimIndent()
        val f = ForecastJson.parse("Геттисберг", Json.parseToJsonElement(body).jsonObject)!!
        assertEquals(LocalDateTime.of(2026, 10, 8, 20, 0), f.now.time)
        assertEquals(2, f.hours.size)
        assertEquals(85, f.hours[1].precipitationChance)
        assertEquals(1.4, f.days.single().precipitationMm, 0.0)
    }
}

class WeatherRoutingTest {

    private val router = CommandRouter()

    @Test
    fun `follow-up weather questions get the forecast, not the model`() {
        listOf(
            "Будет ли дождь?", "Хорошо, но во сколько ждать дождь?", "А какой уровень осадков и когда он закончится?",
            "Нужен ли зонт?", "will it rain?"
        ).forEach { assertTrue(it, router.route(it) is CommandResult.Weather) }
        assertEquals(CommandResult.Weather(null, 1), router.route("будет ли завтра дождь?"))
        assertEquals(CommandResult.Weather("Москве", 0), router.route("Будет ли дождь в Москве?"))
    }

    @Test
    fun `the owner names their city`() {
        assertEquals(CommandResult.SetHomeCity("Геттисберг"), router.route("Мой город — Геттисберг."))
        assertEquals(CommandResult.SetHomeCity("Геттисберге"), router.route("Я живу в Геттисберге"))
        assertEquals(
            CommandResult.Sequence(listOf(CommandResult.SetHomeCity("Геттисберг"), CommandResult.Weather(null, 0))),
            router.route("Но мой город это Геттисберг. Какая погода тогда получается в Геттисберге?")
        )
    }

    @Test
    fun `talk that only mentions weather words stays talk`() {
        assertTrue(router.route("включи звуки дождя") !is CommandResult.Weather)
        assertTrue(router.route("мой город красивый") !is CommandResult.SetHomeCity)
    }

    @Test
    fun `a city said in another case is looked up as it is written`() {
        assertEquals(listOf("Геттисберге", "Геттисберг"), WeatherHere.lookupForms("в Геттисберге"))
        assertEquals("Москва", WeatherHere.lookupForms("Москва").first())
        assertNull(WeatherHere.lookupForms("Ташкент").getOrNull(1))
    }
}

class WeatherWithOtherRequestsTest {

    @Test
    fun `weather and a command in one breath are both done`() {
        val r = CommandRouter().route("какая погода и включи фонарик")
        assertTrue(r.toString(), r is CommandResult.Sequence && r.steps.size == 2)
    }

    @Test
    fun `a lead-in is not a request of its own`() {
        assertEquals(CommandResult.Weather(null, 0), CommandRouter().route("Хорошо, но во сколько ждать дождь?"))
        assertEquals(
            CommandResult.ChatMessage("Спасибо, это было полезно"),
            CommandRouter().route("Спасибо, это было полезно")
        )
    }
}
