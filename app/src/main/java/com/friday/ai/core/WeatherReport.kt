package com.friday.ai.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * One weather answer that doesn't need a follow-up.
 *
 * The old one said "Завтра пасмурно, от 9 до 23" and stopped; the owner then
 * had to ask whether it would rain, when, and how much — three questions for
 * one forecast, and the last answer came from a web page in inches. Now the
 * first answer carries what those questions were after: the temperature and
 * how it feels, the wind, and when rain or snow comes, how much, and how
 * likely — in two or three short sentences.
 */
object WeatherReport {

    data class Hour(
        val time: LocalDateTime,
        val temperature: Double,
        val feelsLike: Double,
        /** Percent; null when the forecast has none for this hour. */
        val precipitationChance: Int?,
        val precipitationMm: Double,
        val code: Int,
        val windKmh: Double
    )

    data class Day(
        val date: LocalDate,
        val min: Double,
        val max: Double,
        val code: Int,
        val precipitationMm: Double,
        val precipitationChance: Int?,
        val windMaxKmh: Double
    )

    data class Forecast(val place: String, val now: Hour, val hours: List<Hour>, val days: List<Day>)

    /** An hour counts as wet when rain is likely or forecast in measurable amounts. */
    private const val WET_CHANCE = 50
    private const val WET_MM = 0.2

    /** Below this chance, dry hours are just dry; above it, the chance is worth saying. */
    private const val SOME_CHANCE = 25

    /** How far ahead "the next hours" look from now. */
    private const val NEXT_HOURS = 6


    fun spoken(f: Forecast, dayOffset: Int, russian: Boolean): String =
        if (dayOffset <= 0) today(f, russian) else ahead(f, dayOffset, russian)

    private fun today(f: Forecast, russian: Boolean): String {
        val n = f.now
        val rest = f.hours.filter { it.time >= n.time.withMinute(0) && it.time.toLocalDate() == n.time.toLocalDate() }
        val next = f.hours.filter { it.time > n.time && it.time <= n.time.plusHours(NEXT_HOURS.toLong()) }
        val condition = WeatherCodes.describe(n.code, russian)
        val feels = n.feelsLike.roundToInt().let { if (it != n.temperature.roundToInt()) it else null }
        return buildString {
            if (russian) {
                append("Сейчас в ${f.place} ${Say.deg(n.temperature)}")
                feels?.let { append(", ощущается как ${Say.deg(it.toDouble())}") }
                append(", $condition, ветер ${Say.wind(n.windKmh, true)}.")
            } else {
                append("It's ${Say.deg(n.temperature)} in ${f.place}")
                feels?.let { append(", feels like ${Say.deg(it.toDouble())}") }
                append(", $condition, wind ${Say.wind(n.windKmh, false)}.")
            }
            append(' ').append(precipitation(rest, russian, later = true))
            if (next.isNotEmpty()) {
                val lo = next.minOf { it.temperature }
                val hi = next.maxOf { it.temperature }
                val span = Say.range(lo, hi, russian)
                append(' ').append(if (russian) "В ближайшие часы $span." else "Over the next few hours $span.")
            }
        }
    }

    private fun ahead(f: Forecast, dayOffset: Int, russian: Boolean): String {
        val date = f.now.time.toLocalDate().plusDays(dayOffset.toLong())
        val day = f.days.firstOrNull { it.date == date }
        val hours = f.hours.filter { it.time.toLocalDate() == date }
        val name = when (dayOffset) {
            1 -> if (russian) "Завтра" else "Tomorrow"
            else -> if (russian) "Послезавтра" else "The day after tomorrow"
        }
        val lo = day?.min ?: hours.minOfOrNull { it.temperature } ?: return noData(f.place, russian)
        val hi = day?.max ?: hours.maxOf { it.temperature }
        val code = day?.code ?: hours.groupingBy { it.code }.eachCount().maxBy { it.value }.key
        val windMax = day?.windMaxKmh ?: hours.maxOfOrNull { it.windKmh } ?: 0.0
        val condition = WeatherCodes.describe(code, russian)
        return buildString {
            append(
                if (russian) "$name в ${f.place} ${Say.range(lo, hi, true)}, $condition."
                else "$name in ${f.place}: ${Say.range(lo, hi, false)}, $condition."
            )
            append(' ').append(precipitation(hours, russian, later = false, dayTotal = day))
            val gusts = Say.wind(windMax, russian)
            append(' ').append(if (russian) "Ветер до $gusts." else "Wind up to $gusts.")
        }
    }

    /**
     * "Дождь с 14:00 до 18:00, около 3 мм, вероятность 80%." — or that it
     * stays dry. [later]: the hours are what is left of today.
     */
    private fun precipitation(hours: List<Hour>, russian: Boolean, later: Boolean, dayTotal: Day? = null): String {
        val wet = hours.filter { isWet(it) }
        // The hours' own chances: the day's maximum can be high on a day whose hours all stay dry.
        val chance = hours.mapNotNull { it.precipitationChance }.maxOrNull() ?: dayTotal?.precipitationChance
        if (wet.isEmpty()) return dry(chance, russian, later)
        val mm = maxOf(hours.sumOf { it.precipitationMm }, dayTotal?.precipitationMm ?: 0.0)
        return wet(wet, mm, chance, russian)
    }

    private fun dry(chance: Int?, russian: Boolean, later: Boolean): String = when {
        chance != null && chance >= SOME_CHANCE ->
            if (russian) "Осадки маловероятны, вероятность $chance%." else "Rain is unlikely, $chance% chance."
        later -> if (russian) "Без осадков до конца дня." else "Dry for the rest of the day."
        else -> if (russian) "Без осадков." else "No rain expected."
    }

    private fun wet(wet: List<Hour>, mm: Double, chance: Int?, russian: Boolean): String {
        val from = Say.clock(wet.first().time, russian)
        val to = Say.clock(wet.last().time.plusHours(1), russian)
        val gaps = wet.zipWithNext().any { (a, b) -> b.time != a.time.plusHours(1) }
        val parts = mutableListOf(
            kind(wet, russian) + (if (russian) " с $from до $to" else " from $from to $to") +
                (if (gaps) (if (russian) " с перерывами" else " on and off") else "")
        )
        if (mm >= WET_MM) parts += if (russian) "около ${Say.amount(mm)} мм" else "about ${Say.amount(mm)} mm"
        chance?.let { parts += if (russian) "вероятность $it%" else "$it% chance" }
        return parts.joinToString(", ") + "."
    }

    private fun isWet(h: Hour): Boolean =
        (h.precipitationChance ?: 0) >= WET_CHANCE || h.precipitationMm >= WET_MM

    /** Rain, snow, or both, from the wet hours' codes. */
    private fun kind(wet: List<Hour>, russian: Boolean): String {
        val snow = wet.count { it.code in SNOW_CODES }
        return when {
            snow == wet.size -> if (russian) "Снег" else "Snow"
            snow > 0 -> if (russian) "Дождь со снегом" else "Rain and snow"
            wet.any { it.code in STORM_CODES } -> if (russian) "Дождь с грозой" else "Rain with thunderstorms"
            else -> if (russian) "Дождь" else "Rain"
        }
    }

    private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)
    private val STORM_CODES = setOf(95, 96, 99)

    private fun noData(place: String, russian: Boolean) =
        if (russian) "Прогноза для $place на этот день нет." else "There's no forecast for $place that day."
}

/** How numbers are said: degrees with a sign, wind in the units the listener thinks in, clock times. */
internal object Say {

    private const val KMH_PER_MS = 3.6


    fun deg(t: Double): String = t.roundToInt().let { if (it > 0) "+$it°" else "$it°" }

    fun range(lo: Double, hi: Double, russian: Boolean): String = when {
        lo.roundToInt() == hi.roundToInt() -> deg(lo)
        russian -> "от ${Say.deg(lo)} до ${Say.deg(hi)}"
        else -> "${Say.deg(lo)} to ${Say.deg(hi)}"
    }

    /** Russian speakers think of wind in metres a second; English ones in km/h. */
    fun wind(kmh: Double, russian: Boolean): String =
        if (russian) "${(kmh / KMH_PER_MS).roundToInt()} м/с" else "${kmh.roundToInt()} km/h"

    fun amount(mm: Double): String = if (mm < 1) "%.1f".format(mm).replace('.', ',') else "${mm.roundToInt()}"

    /** "14:00"; rain that lasts into the night ends "до полуночи", not "до 0:00". */
    fun clock(t: LocalDateTime, russian: Boolean): String = when {
        t.hour != 0 || t.minute != 0 -> "%d:%02d".format(t.hour, t.minute)
        russian -> "полуночи"
        else -> "midnight"
    }
}
