package com.friday.ai.core

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Writes calendar events straight to the provider, without bouncing the user
 * through the calendar app's "save?" screen.
 *
 * Opening the editor for confirmation is the safe default, but it defeats the
 * point of a voice assistant — you say "напомни завтра в 15:00" and then still
 * have to pick up the phone and tap save. Bixby just saves it. So does this,
 * when the permission is there; otherwise it falls back to the editor.
 */
class CalendarWriter(private val context: Context) {

    private companion object {
        const val TAG = "CalendarWriter"
    }

    /** An event already in the calendar, reduced to what rescheduling needs. */
    data class CalendarEvent(
        val id: Long,
        val title: String,
        val startMillis: Long,
        val endMillis: Long
    ) {
        val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Inserts the event silently.
     *
     * @return the new event id, or null if it couldn't be written (no
     *         permission, no writable calendar, provider refused).
     */
    fun insert(
        title: String,
        startMillis: Long,
        endMillis: Long,
        reminderMinutes: Int = DateTimeParser.DEFAULT_REMINDER_MINUTES
    ): Long? {
        if (!hasPermission()) {
            Log.w(TAG, "Calendar permission not granted")
            return null
        }

        val calendarId = findWritableCalendarId() ?: run {
            Log.w(TAG, "No writable calendar on this device")
            return null
        }

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return null
            val eventId = ContentUris.parseId(uri)
            setReminder(eventId, reminderMinutes)
            Log.i(TAG, "Saved event $eventId to calendar $calendarId")
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Insert failed: ${e.message}")
            null
        }
    }

    /**
     * Moves an existing event, keeping how long it lasts. Returns false if the
     * event is gone or the provider refused.
     */
    fun reschedule(eventId: Long, newStartMillis: Long, newEndMillis: Long): Boolean {
        if (!hasPermission()) return false
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, newStartMillis)
                put(CalendarContract.Events.DTEND, newEndMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = context.contentResolver.update(uri, values, null, null)
            Log.i(TAG, "Rescheduled event $eventId (rows=$rows)")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Reschedule failed: ${e.message}")
            false
        }
    }

    /**
     * Replaces the event's reminders with a single one [minutesBefore] ahead.
     * Replacing rather than adding avoids stacking duplicate alerts each time
     * the user changes their mind about the lead time.
     */
    fun setReminder(eventId: Long, minutesBefore: Int): Boolean {
        if (!hasPermission()) return false
        return try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString())
            )
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutesBefore.coerceAtLeast(0))
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values) != null
        } catch (e: Exception) {
            Log.w(TAG, "Could not set the reminder: ${e.message}")
            false
        }
    }

    /** Upcoming events, soonest first — the pool a "move it" command searches. */
    fun upcomingEvents(fromMillis: Long = System.currentTimeMillis(), limit: Int = 25): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTSTART} >= ? AND " +
                    "${CalendarContract.Events.DELETED} = 0",
                arrayOf(fromMillis.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val out = mutableListOf<CalendarEvent>()
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)

                while (cursor.moveToNext() && out.size < limit) {
                    val start = cursor.getLong(startIdx)
                    if (start <= 0L) continue
                    val end = if (endIdx >= 0) cursor.getLong(endIdx) else 0L
                    out += CalendarEvent(
                        id = cursor.getLong(idIdx),
                        title = cursor.getString(titleIdx)?.trim().orEmpty(),
                        startMillis = start,
                        endMillis = if (end > start) end else start + 60 * 60 * 1000L
                    )
                }
                out
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Could not list events: ${e.message}")
            emptyList()
        }
    }

    /**
     * Picks a calendar the app is actually allowed to write to, preferring the
     * primary one so events land where the user expects to see them.
     */
    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                var fallback: Long? = null
                val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryIdx = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val accessIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)

                while (cursor.moveToNext()) {
                    val access = if (accessIdx >= 0) cursor.getInt(accessIdx) else 0
                    if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue

                    val id = cursor.getLong(idIdx)
                    val isPrimary = primaryIdx >= 0 && cursor.getInt(primaryIdx) == 1
                    if (isPrimary) return@use id
                    if (fallback == null) fallback = id
                }
                fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not list calendars: ${e.message}")
            null
        }
    }
}
