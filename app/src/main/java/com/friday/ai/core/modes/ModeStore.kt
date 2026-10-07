package com.friday.ai.core.modes

import android.util.Log
import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.agent.ActionSchema
import com.friday.ai.data.local.dao.ModeDao
import com.friday.ai.data.local.entity.ModeEntity
import com.friday.ai.data.remote.groqJson
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/** A saved mode, with its steps parsed. */
data class Mode(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val description: String,
    val steps: List<ActionEnvelope>,
    /** Non-null while the mode is on: what puts the phone back, newest first. */
    val undo: List<ActionEnvelope>?,
    val createdAt: Long,
    val lastRunAt: Long,
    val runCount: Int
) {
    val active: Boolean get() = undo != null
}

/**
 * The owner's modes: in the database, and in memory so a spoken phrase can
 * be matched against them without waiting on it (the router is synchronous).
 */
@Suppress("TooManyFunctions") // storage, lookup and (de)serialisation of one small table
class ModeStore(private val dao: ModeDao, private val clock: () -> Long = System::currentTimeMillis) {

    private companion object {
        const val TAG = "ModeStore"
    }

    private val cache = CopyOnWriteArrayList<Mode>()

    /** Reads the modes; until it finishes, matching simply finds nothing. */
    suspend fun load() {
        runCatching { dao.all() }
            .onSuccess { rows -> cache.clear(); cache.addAll(rows.mapNotNull(::parse)) }
            .onFailure { Log.w(TAG, "Could not load modes: ${it.message}") }
    }

    fun all(): List<Mode> = cache.toList()

    /** The modes as they change, for the Modes screen. */
    fun observe(): kotlinx.coroutines.flow.Flow<List<Mode>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::parse) }

    /** The mode [spoken] names, by name or by an alias it was later called. */
    fun find(spoken: String, spare: Int = 1): Mode? = cache.firstOrNull { m ->
        ModeNames.same(spoken, m.name, spare) || m.aliases.any { ModeNames.same(spoken, it, spare) }
    }

    fun byId(id: String): Mode? = cache.firstOrNull { it.id == id }

    /** Saves a new mode, or replaces the steps of one with the same name. */
    suspend fun save(name: String, description: String, steps: List<ActionEnvelope>): Pair<Mode, Boolean> {
        val existing = find(name)
        val mode = Mode(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            aliases = existing?.aliases.orEmpty(),
            description = description,
            steps = steps,
            undo = existing?.undo,
            createdAt = existing?.createdAt ?: clock(),
            lastRunAt = existing?.lastRunAt ?: 0,
            runCount = existing?.runCount ?: 0
        )
        put(mode)
        return mode to (existing != null)
    }

    suspend fun put(mode: Mode) {
        cache.removeAll { it.id == mode.id }
        cache.add(mode)
        dao.upsert(entity(mode))
    }

    suspend fun delete(id: String) {
        cache.removeAll { it.id == id }
        dao.delete(id)
    }

    // --- (de)serialisation ------------------------------------------------

    private fun parse(e: ModeEntity): Mode? = runCatching {
        Mode(
            id = e.id,
            name = e.name,
            aliases = groqJson.parseToJsonElement(e.aliases).jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            description = e.description,
            steps = envelopes(e.steps),
            undo = e.undo?.let(::envelopes),
            createdAt = e.createdAt,
            lastRunAt = e.lastRunAt,
            runCount = e.runCount
        )
    }.onFailure { Log.w(TAG, "Unreadable mode ${e.name}: ${it.message}") }.getOrNull()

    /** Steps written by an older Friday are brought up to date; unreadable ones are dropped. */
    private fun envelopes(json: String): List<ActionEnvelope> =
        groqJson.parseToJsonElement(json).jsonArray.mapNotNull { ActionSchema.parse(it.toString()) }

    private fun entity(m: Mode) = ModeEntity(
        id = m.id,
        name = m.name,
        aliases = JsonArray(m.aliases.map(::JsonPrimitive)).toString(),
        description = m.description,
        steps = json(m.steps),
        undo = m.undo?.let(::json),
        createdAt = m.createdAt,
        lastRunAt = m.lastRunAt,
        runCount = m.runCount
    )

    private fun json(list: List<ActionEnvelope>) = "[" + list.joinToString(",") { it.toJson() } + "]"
}
