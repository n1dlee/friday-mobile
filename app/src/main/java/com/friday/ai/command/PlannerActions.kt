package com.friday.ai.command

import com.friday.ai.core.AppLauncher
import com.friday.ai.core.DateTimeParser
import com.friday.ai.data.local.dao.ErrandDao
import com.friday.ai.data.local.entity.ErrandEntity
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.FridayMemory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Commands that write something down for later: calendar, errands, notes. */
class PlannerActions(
    private val apps: AppLauncher,
    private val errandDao: ErrandDao,
    private val memory: FridayMemory,
    /** Starts the location watcher that raises errands; must be safe to call repeatedly. */
    private val watchErrands: () -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {

    private companion object {
        /** An event gets a one-hour slot unless the user says otherwise. */
        const val DEFAULT_EVENT_MS = 60 * 60 * 1000L
    }

    suspend fun run(c: CommandResult.Planner, russian: Boolean): String = when (c) {
        is CommandResult.CreateEvent -> createEvent(c)
        is CommandResult.RescheduleEvent -> reschedule(c)
        is CommandResult.CreateErrand -> {
            errandDao.insert(ErrandEntity(what = c.what))
            watchErrands()
            if (russian) "Напомню, когда будете рядом" else "I'll remind you when you're nearby"
        }
        // Notes go to Lazuri so they show up on the PC too.
        is CommandResult.CreateNote ->
            if (memory.saveNote(c.text)) "Noted" else "Saved locally — Lazuri isn't connected"
    }

    private fun createEvent(c: CommandResult.CreateEvent): String {
        val local = parseWhen(c.whenText) ?: return "I couldn't work out when that should be"
        val start = millis(local)
        return apps.createCalendarEvent(
            title = c.title,
            startMillis = start,
            endMillis = start + DEFAULT_EVENT_MS,
            reminderMinutes = DateTimeParser.parseReminderLeadMinutes(c.whenText)
                ?: DateTimeParser.DEFAULT_REMINDER_MINUTES
        )
    }

    private fun reschedule(c: CommandResult.RescheduleEvent): String {
        val local = parseWhen(c.whenText) ?: return "I couldn't work out the new time"
        return apps.rescheduleEvent(c.titleHint, millis(local))
    }

    /** The agent sends "2026-10-07T15:00"; a spoken phrase goes through the parser. */
    private fun parseWhen(text: String): LocalDateTime? =
        try {
            LocalDateTime.parse(text)
        } catch (_: DateTimeParseException) {
            DateTimeParser.parse(text, zone())?.instant
        }

    private fun millis(local: LocalDateTime): Long = local.atZone(zone()).toInstant().toEpochMilli()
}
