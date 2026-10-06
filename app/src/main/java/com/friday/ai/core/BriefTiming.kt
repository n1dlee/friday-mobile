package com.friday.ai.core

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * When the morning brief goes out, and how Friday greets you when it does.
 *
 * Everything here works on local wall-clock time in the phone's current zone.
 * The brief is about *your* morning, so a fixed interval measured in hours
 * is the wrong model: a daily periodic job is anchored to whenever it first
 * happened to run, and once the system deferred that run to 16:00 every
 * later brief arrived at 16:00 too.
 */
object BriefTiming {

    /**
     * How late a brief may still be delivered. Past this the day is well under
     * way, the agenda is half spent, and "here is your morning" is noise.
     */
    const val LATE_LIMIT_HOURS = 4L

    /** Batching may run the job a little before its time; that is fine. */
    const val EARLY_LIMIT_MINUTES = 60L

    private const val LAST_HOUR = 23
    private const val MINUTES_PER_HOUR = 60L
    private const val DAY_MINUTES = 24 * MINUTES_PER_HOUR
    private const val HALF_DAY_MINUTES = DAY_MINUTES / 2

    private const val MORNING_FROM = 5
    private const val AFTERNOON_FROM = 12
    private const val EVENING_FROM = 18

    fun greeting(time: LocalTime, russian: Boolean): String {
        val h = time.hour
        return when {
            h < MORNING_FROM -> if (russian) "Доброй ночи." else "Good evening."
            h < AFTERNOON_FROM -> if (russian) "Доброе утро." else "Good morning."
            h < EVENING_FROM -> if (russian) "Добрый день." else "Good afternoon."
            else -> if (russian) "Добрый вечер." else "Good evening."
        }
    }

    /** True when [now] is close enough to [targetHour] for a morning brief to make sense. */
    fun shouldDeliver(now: LocalTime, targetHour: Int): Boolean {
        val target = LocalTime.of(targetHour.coerceIn(0, LAST_HOUR), 0)
        // Signed distance on a 24 h clock, so a 23:00 brief run at 01:00
        // counts as two hours late rather than twenty-two hours early.
        var minutes = Duration.between(target, now).toMinutes()
        if (minutes < -HALF_DAY_MINUTES) minutes += DAY_MINUTES
        if (minutes >= HALF_DAY_MINUTES) minutes -= DAY_MINUTES
        return minutes in -EARLY_LIMIT_MINUTES until LATE_LIMIT_HOURS * MINUTES_PER_HOUR
    }

    /**
     * Time from [now] until the next [targetHour]:00 in [now]'s zone.
     *
     * Built on zoned date-times rather than a fixed 24 h so that a daylight-
     * saving change, or a flight across zones before the next run is
     * scheduled, still lands on the local hour.
     */
    fun delayUntilNext(now: ZonedDateTime, targetHour: Int): Duration {
        val hour = targetHour.coerceIn(0, LAST_HOUR)
        var next = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
        if (!next.isAfter(now)) {
            next = now.toLocalDate().plusDays(1).atTime(hour, 0).atZone(now.zone)
        }
        return Duration.between(now, next)
    }
}
