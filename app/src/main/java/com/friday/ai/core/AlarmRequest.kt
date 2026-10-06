package com.friday.ai.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Reads "поставь будильник в 8 утра на завтра" and its many relatives.
 *
 * The old pattern only knew "на 7:30" — minutes required, "на" required. So
 * "будильник в 8 утра" was not an alarm at all: it went to the model as
 * conversation, and the model answered "поставила" without anything having
 * been set. Times now go through [DateTimeParser], the same reading the
 * calendar uses, plus the two things only alarms say: "на 8" for the time,
 * and numbers spoken as words.
 */
object AlarmRequest {

    /**
     * @param date set only when the user's day differs from the next time the
     *   clock would ring anyway — the Clock app takes a time, not a date, and
     *   the reply has to say so rather than pretend.
     */
    data class Alarm(val time: LocalTime, val date: LocalDate?)

    /**
     * A request, not a mention: it has to start with an action or with the
     * alarm itself. "Почему будильник в 7 не прозвенел?" names a time too, and
     * must not set one. One leading word ("пожалуйста", "пятница") is allowed.
     */
    private val trigger = Regex(
        // Either a verb that later names the alarm ("поставь … будильник"),
        // or a phrase that is itself the request ("разбуди", "будильник на 6").
        // A bare noun counts only at the very start ("будильник на 6"), so
        // "почему будильник в 7 не прозвенел?" stays a question.
        """^(?:будильник|alarm)|^(?:\p{L}+[,\s]+)?""" +
            """(?:(?:поставь|установи|заведи|создай|сделай|включи|set)[\s\S]*?(?:будильник|alarm)""" +
            """|разбуди|wake\s+me)""",
        RegexOption.IGNORE_CASE
    )

    /** In an alarm request "на 8" is the time, not a duration. */
    private val naBeforeNumber = Regex("""(^|\s)(?:на|for)\s+(?=\d)""")

    private val numberWords = mapOf(
        "одиннадцать" to 11, "двенадцать" to 12, "десять" to 10, "девять" to 9, "восемь" to 8,
        "семь" to 7, "шесть" to 6, "пять" to 5, "четыре" to 4, "три" to 3, "два" to 2, "две" to 2,
        "один" to 1, "одну" to 1, "час" to 1
    )
    private val spokenHour = Regex("""(^|\s)(в|на)\s+(${numberWords.keys.joinToString("|")})(?=\s|$)""")

    fun parse(text: String, now: LocalDateTime): Alarm? {
        if (!trigger.containsMatchIn(text.trim())) return null
        val normalised = digits(text.lowercase().replace('ё', 'е'))
            .replace(naBeforeNumber) { "${it.groupValues[1]}в " }
        val parsed = DateTimeParser.parse(normalised, now) ?: return null
        // "разбуди завтра" without a time is a question, not 09:00.
        if (!parsed.hadExplicitTime) return null

        val time = parsed.instant.toLocalTime().withSecond(0).withNano(0)
        val clockWouldRing = nextOccurrence(time, now)
        val date = parsed.instant.toLocalDate().takeIf { it != clockWouldRing.toLocalDate() }
        return Alarm(time, date)
    }

    /** When an alarm at [time] actually goes off, set at [now]. */
    fun nextOccurrence(time: LocalTime, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun digits(text: String): String =
        text.replace(spokenHour) { m ->
            val (lead, preposition, word) = m.destructured
            "$lead$preposition ${numberWords.getValue(word)}"
        }

    // --- after the request is sent ------------------------------------------

    enum class Check {
        /** The phone's next alarm is the one just asked for. */
        CONFIRMED,

        /** An earlier alarm is next, so this one cannot be seen — not proof of failure. */
        UNVERIFIABLE,

        /** Nothing at that time: the Clock app did not create it. */
        MISSING
    }

    private const val SAME_MINUTE_MS = 60_000L

    /**
     * Android reports only the *next* alarm across all apps. If that is ours,
     * it exists. If nothing is set, or the next one is later than ours, ours
     * was not created. If an earlier one is next, ours is hidden behind it.
     */
    fun check(expectedMillis: Long, nextAlarmMillis: Long?): Check = when {
        nextAlarmMillis == null -> Check.MISSING
        kotlin.math.abs(nextAlarmMillis - expectedMillis) < SAME_MINUTE_MS -> Check.CONFIRMED
        nextAlarmMillis < expectedMillis -> Check.UNVERIFIABLE
        else -> Check.MISSING
    }

    fun reply(time: LocalTime, check: Check, russian: Boolean): String {
        val t = "%02d:%02d".format(time.hour, time.minute)
        return when (check) {
            Check.CONFIRMED -> if (russian) "Будильник на $t поставлен." else "Alarm set for $t."
            Check.UNVERIFIABLE ->
                if (russian) "Будильник на $t отправлен в Часы. Проверить не могу: раньше стоит другой."
                else "Alarm for $t sent to Clock. I can't confirm it: an earlier alarm is next."
            Check.MISSING ->
                if (russian) "Часы не создали будильник на $t сами — открыла их, сохраните, пожалуйста."
                else "Clock didn't create the $t alarm on its own — I've opened it for you to save."
        }
    }

    /**
     * The Clock app takes a time, not a date: it rings at the next such time.
     * When that is not the day the user asked for, nothing is set — an alarm
     * that rings on the wrong morning is worse than none.
     */
    fun wrongDay(alarm: Alarm, now: LocalDateTime, russian: Boolean): String {
        val t = "%02d:%02d".format(alarm.time.hour, alarm.time.minute)
        val requested = alarm.date ?: return ""
        return when {
            requested.atTime(alarm.time).isBefore(now) ->
                if (russian) "$t уже прошло." else "$t has already passed."
            russian ->
                "Часы ставят будильник только на ближайшие $t, а это не тот день. " +
                    "Скажите «поставь будильник на $t», если ближайшие подходят."
            else ->
                "Clock can only set the next $t, which isn't the day you asked for. " +
                    "Say “set an alarm for $t” if the next one is fine."
        }
    }
}
