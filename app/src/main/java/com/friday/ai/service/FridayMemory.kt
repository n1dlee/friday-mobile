package com.friday.ai.service

import android.util.Log
import com.friday.ai.data.local.dao.InteractionDao
import com.friday.ai.core.CorrectionDetector
import com.friday.ai.core.FactFilter
import com.friday.ai.data.local.dao.MemoryDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.InteractionEntity
import com.friday.ai.data.local.entity.MemoryEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.LazuriApiService
import com.friday.ai.data.remote.dto.ApiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The assistant's memory: structured facts extracted from conversations
 * (preferences, habits, interests, ...) plus a raw interaction log. Backed
 * locally by Room so the app fully works offline, and optionally mirrored to
 * Lazuri Core (the user's shared cross-device backend) when configured in
 * Settings, so a fact learned on the phone is visible from other devices too.
 * All Lazuri calls are best-effort — network failures never affect local
 * behavior.
 */
class FridayMemory(
    private val memoryDao: MemoryDao,
    private val interactionDao: InteractionDao,
    private val groqApi: GroqApiService,
    private val prefDao: UserPreferenceDao,
    private val lazuriApi: LazuriApiService,
    private val catalog: ModelCatalog
) {

    companion object {
        private const val TAG = "FridayMemory"

        private const val PREF_LAZURI_URL = "lazuri_base_url"
        private const val PREF_LAZURI_KEY = "lazuri_api_key"
        private const val PREF_LAZURI_DEVICE_ID = "lazuri_device_id"
        private const val PREF_LAZURI_ENABLED = "lazuri_enabled"

        private const val PREF_SESSION_ID = "voice_session_id"
        private const val PREF_SESSION_LAST_ACTIVITY = "voice_session_last_activity"

        // A voice "session" is a continuous conversational thread: as long as
        // the user keeps talking to Friday within this window of their last
        // turn, new turns stay in the same session (so recent context like
        // "what I said 5 minutes ago" carries over). After this much silence,
        // the next wake word starts a fresh session.
        private const val SESSION_TIMEOUT_MS = 60 * 60 * 1000L

        object Category {
            const val PREFERENCE = "preference"
            const val FACT = "fact"
            const val HABIT = "habit"
            const val MUSIC = "music"
            const val CONTACT = "contact"
            const val INTEREST = "interest"
        }

        private const val EXTRACTION_PROMPT = """Analyze this conversation and extract facts about the user. Return ONLY a list of facts in this exact format, one per line:
CATEGORY|KEY|VALUE

Categories: preference, fact, habit, music, contact, interest

Examples:
preference|language|Russian
fact|name|Alex
habit|morning_routine|checks news first
music|favorite_genre|hip-hop
contact|best_friend|Ivan
interest|hobby|programming

Rules:
- Only extract CLEAR facts STATED by the user, not assumptions
- A question is never a fact. "What is my name?" tells you nothing — skip it.
- Extract only from the User line, never from the Assistant line
- Skip greetings, commands (open app, set alarm), and small talk
- If nothing meaningful, respond with just: NONE
- Max 5 facts per conversation
- Use lowercase for category and key
- Value can be in any language"""
    }

    // Fire-and-forget scope for Lazuri sync calls so a slow/offline network
    // never adds latency to the user-facing chat or voice flow.
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Stores a correction the user just made, so it outranks whatever was
     * learned before. Corrections are the strongest signal there is — the
     * user is explicitly telling Friday it was wrong.
     */
    suspend fun recordCorrection(correction: CorrectionDetector.Correction) {
        withContext(Dispatchers.IO) {
            saveOrUpdate(
                category = "correction",
                key = "correction_" + (System.currentTimeMillis() / 1000),
                value = CorrectionDetector.asMemoryNote(correction),
                source = "user_correction"
            )
        }
    }

    /** Notified when a conversation ends, so it can be summarised. */
    var onSessionClosed: ((String) -> Unit)? = null

    suspend fun logInteraction(userInput: String, response: String, commandType: String?, sessionId: String? = null) {
        withContext(Dispatchers.IO) {
            interactionDao.insert(
                InteractionEntity(
                    userInput = userInput,
                    assistantResponse = response,
                    commandType = commandType,
                    sessionId = sessionId
                )
            )
        }
        pushInteractionToLazuri(userInput, response, commandType, sessionId)
    }

    /**
     * Returns the id of the currently active voice session, starting a new
     * one if the user has been silent for longer than [SESSION_TIMEOUT_MS].
     * Call this once per wake-word trigger / follow-up turn.
     */
    suspend fun getOrCreateSessionId(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastActivity = prefDao.get(PREF_SESSION_LAST_ACTIVITY)?.toLongOrNull()
        val existing = prefDao.get(PREF_SESSION_ID)

        val sessionId = if (existing != null && lastActivity != null && (now - lastActivity) < SESSION_TIMEOUT_MS) {
            existing
        } else {
            java.util.UUID.randomUUID().toString()
        }

        prefDao.set(UserPreferenceEntity(PREF_SESSION_ID, sessionId))
        prefDao.set(UserPreferenceEntity(PREF_SESSION_LAST_ACTIVITY, now.toString()))
        sessionId
    }

    /** Recent turns from the given session, oldest first — used as short-term conversation history. */
    suspend fun getSessionHistory(sessionId: String, limit: Int = 10): List<InteractionEntity> =
        withContext(Dispatchers.IO) { interactionDao.getBySession(sessionId, limit) }

    /**
     * Forces a fresh conversation, ignoring the inactivity window. Used by
     * "New chat" so the user can deliberately start over even mid-session.
     */
    suspend fun startNewSession(): String = withContext(Dispatchers.IO) {
        // The conversation being left behind is now final, so it can be
        // summarised for the dashboard.
        prefDao.get(PREF_SESSION_ID)?.let { onSessionClosed?.invoke(it) }
        val sessionId = java.util.UUID.randomUUID().toString()
        prefDao.set(UserPreferenceEntity(PREF_SESSION_ID, sessionId))
        prefDao.set(UserPreferenceEntity(PREF_SESSION_LAST_ACTIVITY, System.currentTimeMillis().toString()))
        sessionId
    }

    /** Makes [sessionId] the active one, e.g. after the user reopens an old chat. */
    suspend fun resumeSession(sessionId: String) = withContext(Dispatchers.IO) {
        prefDao.set(UserPreferenceEntity(PREF_SESSION_ID, sessionId))
        prefDao.set(UserPreferenceEntity(PREF_SESSION_LAST_ACTIVITY, System.currentTimeMillis().toString()))
    }

    /**
     * Fire-and-forget wrapper around [extractAndSaveMemories]. Fact extraction
     * costs a full extra LLM round-trip, so it must never sit between the
     * model's answer and the user hearing it — that dead air was the single
     * biggest avoidable delay in the voice loop.
     */
    fun extractMemoriesInBackground(userInput: String, assistantResponse: String) {
        syncScope.launch {
            try {
                extractAndSaveMemories(userInput, assistantResponse)
            } catch (e: Exception) {
                Log.w(TAG, "Background memory extraction failed: ${e.message}")
            }
        }
    }

    suspend fun extractAndSaveMemories(userInput: String, assistantResponse: String) {
        withContext(Dispatchers.IO) {
            try {
                val apiKey = prefDao.get("groq_api_key") ?: return@withContext

                val conversation = "User: $userInput\nAssistant: $assistantResponse"
                val messages = listOf(
                    ApiMessage(role = "system", content = EXTRACTION_PROMPT),
                    ApiMessage(role = "user", content = conversation)
                )

                val responseBuilder = StringBuilder()
                groqApi.streamCompletion(apiKey, messages, catalog.model(com.friday.ai.core.GroqModels.Role.FAST))
                    .catch { e ->
                        Log.e(TAG, "Extraction failed: ${e.message}")
                        if (e is com.friday.ai.data.remote.GroqApiException && e.modelMissing) catalog.refresh()
                    }
                    .collect { token -> responseBuilder.append(token) }

                val result = responseBuilder.toString().trim()
                if (result == "NONE" || result.isBlank()) return@withContext

                result.lines()
                    .filter { it.contains("|") }
                    .forEach { line ->
                        val parts = line.split("|", limit = 3)
                        if (parts.size == 3) {
                            val (category, key, value) = parts.map { it.trim().lowercase() }
                            if (FactFilter.isStorable(key, value)) {
                                saveOrUpdate(category, key, value, "conversation")
                            } else {
                                Log.d(TAG, "Rejected extracted fact: $key = $value")
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Memory extraction error: ${e.message}")
            }
        }
    }

    suspend fun saveOrUpdate(category: String, key: String, value: String, source: String) {
        val existing = memoryDao.findByKey(key, category)
        if (existing != null) {
            memoryDao.updateValue(existing.id, value)
        } else {
            memoryDao.insert(
                MemoryEntity(
                    category = category,
                    key = key,
                    value = value,
                    source = source
                )
            )
        }
        pushFactToLazuri(category, key, value)
    }

    suspend fun buildMemoryContext(): String = withContext(Dispatchers.IO) {
        val memories = memoryDao.getRecent(30)
        if (memories.isEmpty()) return@withContext ""

        buildString {
            appendLine("\n--- User Memory (things you know about this user) ---")
            val grouped = memories.groupBy { it.category }
            grouped.forEach { (category, items) ->
                appendLine("[$category]")
                items.forEach { m ->
                    appendLine("- ${m.key}: ${m.value}")
                }
            }
            appendLine("--- End of Memory ---")
            if (memories.any { it.category == "correction" }) {
                appendLine(
                    "Where a correction contradicts an earlier fact, the correction wins."
                )
            }
            // The old instruction here was "never mention that you have a
            // memory database". Told it knew things but forbidden to say how,
            // the model invented a source — "you told me that earlier" — for
            // facts the user had just stated for the first time. Hiding the
            // mechanism is fine; inventing a history is not.
            appendLine(
                "These are saved notes, not part of the current conversation. " +
                    "Use them to personalise your answers. Do not talk about databases " +
                    "or memory systems; if asked how you know something, say it was " +
                    "saved from an earlier conversation. Never claim the user told you " +
                    "something in this conversation unless it appears above in this chat."
            )
        }
    }

    suspend fun getMemories(): List<MemoryEntity> = memoryDao.getRecent(50)

    suspend fun search(query: String): List<MemoryEntity> = memoryDao.search(query)

    suspend fun deleteMemory(id: Long) = memoryDao.delete(id)

    suspend fun getStats(): MemoryStats {
        val memCount = memoryDao.count()
        val interactionCount = interactionDao.count()
        val commandStats = interactionDao.getCommandStats()
        return MemoryStats(memCount, interactionCount, commandStats.associate { it.commandType to it.cnt })
    }

    /** Registers this phone with Lazuri Core and stores the returned device id. Called from Settings. */
    suspend fun registerWithLazuri(baseUrl: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val device = lazuriApi.registerDevice(baseUrl, apiKey, name = "Friday Mobile", platform = "phone")
            prefDao.set(UserPreferenceEntity(PREF_LAZURI_URL, baseUrl))
            prefDao.set(UserPreferenceEntity(PREF_LAZURI_KEY, apiKey))
            prefDao.set(UserPreferenceEntity(PREF_LAZURI_DEVICE_ID, device.id))
            prefDao.set(UserPreferenceEntity(PREF_LAZURI_ENABLED, "true"))
            Result.success(device.id)
        } catch (e: Exception) {
            Log.e(TAG, "Lazuri registration failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Saves a voice note to Lazuri so it's readable from the other devices.
     * Returns false when Lazuri isn't set up — the caller says so rather than
     * pretending it was filed away somewhere.
     */
    suspend fun saveNote(text: String): Boolean = withContext(Dispatchers.IO) {
        val (url, key, _) = lazuriCreds() ?: return@withContext false
        val title = text.take(60).substringBefore('\n').trim().ifBlank { "Note" }
        runCatching { lazuriApi.createNote(url, key, title, text) }.getOrDefault(false)
    }

    suspend fun isLazuriConfigured(): Boolean =
        prefDao.get(PREF_LAZURI_ENABLED) == "true" && prefDao.get(PREF_LAZURI_DEVICE_ID) != null

    private suspend fun lazuriCreds(): Triple<String, String, String>? {
        if (prefDao.get(PREF_LAZURI_ENABLED) != "true") return null
        val url = prefDao.get(PREF_LAZURI_URL) ?: return null
        val key = prefDao.get(PREF_LAZURI_KEY) ?: return null
        val deviceId = prefDao.get(PREF_LAZURI_DEVICE_ID) ?: return null
        return Triple(url, key, deviceId)
    }

    private fun pushInteractionToLazuri(userInput: String, response: String, commandType: String?, sessionId: String?) {
        syncScope.launch {
            val (url, key, deviceId) = lazuriCreds() ?: return@launch
            try {
                val meta = commandType?.let { mapOf("commandType" to it) }
                listOf("user" to userInput, "assistant" to response).forEach { (role, content) ->
                    lazuriApi.createMemory(
                        url, key, role = role, content = content,
                        sourceDeviceId = deviceId, sessionId = sessionId, metadata = meta
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lazuri interaction push failed: ${e.message}")
            }
        }
    }

    private fun pushFactToLazuri(category: String, key: String, value: String) {
        syncScope.launch {
            val (url, apiKey, deviceId) = lazuriCreds() ?: return@launch
            try {
                lazuriApi.createMemory(
                    url, apiKey,
                    role = "fact",
                    content = "$key: $value",
                    sourceDeviceId = deviceId,
                    metadata = mapOf("category" to category, "key" to key)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Lazuri fact push failed: ${e.message}")
            }
        }
    }

    data class MemoryStats(
        val totalMemories: Int,
        val totalInteractions: Int,
        val commandUsage: Map<String, Int>
    )
}
