package com.friday.ai.core.modes

import com.friday.ai.data.local.dao.ModeScheduleDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.ModeRunEntity
import com.friday.ai.data.local.entity.ModeScheduleEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Mode schedules and run history. [onChanged] re-arms the system alarms
 * whenever the set of schedules changes.
 */
@Suppress("TooManyFunctions") // schedules, run history and the once-only habit flag of one table
class ModeSchedules(
    private val dao: ModeScheduleDao,
    private val prefs: UserPreferenceDao,
    /** Called with the schedules now, and the ids there were before (so removed ones can be cancelled). */
    private val onChanged: suspend (now: List<Schedule>, before: Set<String>) -> Unit = { _, _ -> },
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {

    private companion object {
        const val SUGGESTED = "mode_habit_suggested:"
        const val HISTORY_DAYS = 30L
    }

    suspend fun all(): List<Schedule> = dao.all().map(::schedule)

    fun observe(): Flow<List<Schedule>> = dao.observeAll().map { rows -> rows.map(::schedule) }

    suspend fun byId(id: String): Schedule? = dao.byId(id)?.let(::schedule)

    suspend fun forMode(modeId: String): List<Schedule> = all().filter { it.modeId == modeId }

    /** One schedule per mode and direction: saying a new time replaces the old one. */
    suspend fun set(modeId: String, exit: Boolean, time: LocalTime, days: Set<DayOfWeek>): Schedule {
        val before = ids()
        val existing = forMode(modeId).firstOrNull { it.exit == exit }
        val s = Schedule(existing?.id ?: UUID.randomUUID().toString(), modeId, exit, time, days)
        dao.upsert(entity(s))
        onChanged(all(), before)
        return s
    }

    suspend fun clear(modeId: String) {
        val before = ids()
        dao.deleteForMode(modeId)
        onChanged(all(), before)
    }

    suspend fun delete(id: String) {
        val before = ids()
        dao.delete(id)
        onChanged(all(), before)
    }

    private suspend fun ids() = dao.all().map { it.id }.toSet()

    suspend fun fired(s: Schedule, at: Long) = dao.upsert(entity(s.copy(lastFiredAt = at)))

    suspend fun logRun(modeId: String, at: Long, automatic: Boolean) {
        dao.logRun(ModeRunEntity(modeId = modeId, at = at, automatic = automatic))
        dao.pruneRuns(at - HISTORY_DAYS * MILLIS_PER_DAY)
    }

    /** The times the owner turned [modeId] on by hand recently. */
    suspend fun manualRuns(modeId: String, now: Long): List<LocalDateTime> =
        dao.runsSince(modeId, now - HISTORY_DAYS * MILLIS_PER_DAY)
            .filterNot { it.automatic }
            .map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.at), zone()) }

    /** A habit is offered once per mode, whatever the answer. */
    suspend fun wasSuggested(modeId: String): Boolean = prefs.get(SUGGESTED + modeId) == "1"

    suspend fun markSuggested(modeId: String) = prefs.set(UserPreferenceEntity(SUGGESTED + modeId, "1"))

    private fun schedule(e: ModeScheduleEntity) =
        Schedule(e.id, e.modeId, e.exit, LocalTime.of(e.hour, e.minute), Days.of(e.days), e.lastFiredAt)

    private fun entity(s: Schedule) =
        ModeScheduleEntity(s.id, s.modeId, s.exit, s.time.hour, s.time.minute, Days.mask(s.days), s.lastFiredAt)
}

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
