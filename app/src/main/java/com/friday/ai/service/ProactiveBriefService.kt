package com.friday.ai.service

import android.content.Context
import android.util.Log
import com.friday.ai.core.BriefComposer
import com.friday.ai.core.CalendarWriter
import com.friday.ai.data.local.dao.NotificationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Assembles the two proactive answers: "what did I miss" and the morning
 * briefing.
 *
 * Every source is optional — no notification access, no calendar, no network
 * — and a missing one is simply left out rather than turning the whole brief
 * into an error.
 */
class ProactiveBriefService(
    private val context: Context,
    private val notificationDao: NotificationDao,
    private val calendarWriter: CalendarWriter,
    private val weatherHere: WeatherHere
) {

    private companion object {
        const val TAG = "ProactiveBrief"
    }

    /** Answers "что я пропустил". */
    suspend fun whatDidIMiss(russian: Boolean): String = withContext(Dispatchers.IO) {
        if (!FridayNotificationListener.isEnabled(context)) {
            FridayNotificationListener.openSettings(context)
            return@withContext if (russian) {
                "Мне нужен доступ к уведомлениям — открыл настройки."
            } else {
                "I need notification access — I've opened the setting."
            }
        }

        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val unseen = runCatching { notificationDao.unseenSince(since) }.getOrDefault(emptyList())

        val summary = BriefComposer.missedSummary(
            unseen.map {
                BriefComposer.NotificationSummary(it.appName, it.title, it.text)
            },
            russian
        )

        // Asking clears the backlog, otherwise the same items repeat forever.
        runCatching { notificationDao.markSeen(System.currentTimeMillis()) }
        summary
    }

    /** Weather + today's agenda + how much is waiting. */
    suspend fun morningBrief(russian: Boolean): String = withContext(Dispatchers.IO) {
        val weather = runCatching {
            weatherHere.summary(place = null, dayOffset = 0, russian = russian)
        }.getOrNull()

        val agenda = runCatching { todaysAgenda() }.getOrDefault(emptyList())

        val missed = runCatching {
            if (FridayNotificationListener.isEnabled(context)) notificationDao.unseenCount() else 0
        }.getOrDefault(0)

        BriefComposer.morningBrief(weather, agenda, missed, russian, java.time.LocalTime.now())
    }

    private fun todaysAgenda(): List<BriefComposer.AgendaItem> {
        val now = System.currentTimeMillis()
        val endOfDay = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.timeInMillis

        return calendarWriter.upcomingEvents(fromMillis = now, limit = 10)
            .filter { it.startMillis <= endOfDay }
            .map { event ->
                val cal = Calendar.getInstance().apply { timeInMillis = event.startMillis }
                BriefComposer.AgendaItem(
                    title = event.title.ifBlank { "событие" },
                    timeLabel = BriefComposer.timeLabel(
                        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
                    )
                )
            }
            .also { Log.i(TAG, "Agenda has ${it.size} item(s) today") }
    }
}
