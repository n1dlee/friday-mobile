package com.friday.ai.service

import android.util.Log
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.dao.SessionSummaryDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.SessionSummaryEntity
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes a one-line summary of each conversation.
 *
 * The dashboard previously showed "N messages", which tells you nothing about
 * what was actually discussed — looking back at a day should tell you what
 * happened, not how much was typed.
 *
 * Summaries are generated with the cheap model and only when a conversation
 * has moved on since the last one, so browsing the map doesn't quietly burn
 * an API call per session every time it opens.
 */
class SessionSummarizer(
    private val chatDao: ChatMessageDao,
    private val summaryDao: SessionSummaryDao,
    private val groqApi: GroqApiService,
    private val prefDao: UserPreferenceDao,
    private val catalog: ModelCatalog
) {

    private companion object {
        const val TAG = "SessionSummarizer"

        /** Below this a conversation is too short to be worth summarising. */
        const val MIN_MESSAGES = 3

        /** How many messages get sent for summarising. */
        const val MAX_MESSAGES_SENT = 30

        const val PROMPT = """Summarise this conversation in ONE short sentence (max 12 words).
Write what the user wanted or what was decided — not "the user asked about X".
Reply in the same language the conversation is in. Output only the sentence."""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget: never let summarising delay anything the user sees. */
    fun summarizeInBackground(sessionId: String) {
        scope.launch {
            runCatching { summarize(sessionId) }
                .onFailure { Log.w(TAG, "Summary failed for $sessionId: ${it.message}") }
        }
    }

    /**
     * Generates and stores the summary if it's missing or stale.
     * @return the summary, or null if there wasn't enough to summarise.
     */
    suspend fun summarize(sessionId: String): String? = withContext(Dispatchers.IO) {
        val messages = runCatching { chatDao.observeSession(sessionId).first() }
            .getOrDefault(emptyList())

        if (messages.size < MIN_MESSAGES) return@withContext null

        val existing = summaryDao.forSession(sessionId)
        // Nothing new has been said since the last summary.
        if (existing != null && existing.messageCount >= messages.size) {
            return@withContext existing.summary
        }

        val apiKey = prefDao.get("groq_api_key")?.takeIf { it.isNotBlank() }
            ?: return@withContext null

        val transcript = messages
            .takeLast(MAX_MESSAGES_SENT)
            .joinToString("\n") { "${it.role.lowercase()}: ${it.content.take(300)}" }

        val builder = StringBuilder()
        groqApi.streamCompletion(
            apiKey,
            listOf(
                ApiMessage(role = "system", content = PROMPT),
                ApiMessage(role = "user", content = transcript)
            ),
            catalog.model(com.friday.ai.core.GroqModels.Role.FAST),
            maxTokens = 60
        )
            .catch { e ->
                Log.w(TAG, "Groq refused: ${e.message}")
                if (e is com.friday.ai.data.remote.GroqApiException && e.modelMissing) catalog.refresh()
            }
            .collect { builder.append(it) }

        val summary = builder.toString().trim().trim('"', '.', ' ')
        if (summary.isBlank()) return@withContext null

        summaryDao.upsert(
            SessionSummaryEntity(
                sessionId = sessionId,
                summary = summary,
                messageCount = messages.size
            )
        )
        Log.i(TAG, "Summarised $sessionId: $summary")
        summary
    }

    /**
     * Fills in any missing summaries — used when the dashboard opens so older
     * conversations get labelled without the user asking.
     */
    suspend fun backfill(sessionIds: List<String>, limit: Int = 5) {
        val existing = summaryDao.all().associateBy { it.sessionId }
        sessionIds
            .filter { existing[it] == null }
            .take(limit)
            .forEach { runCatching { summarize(it) } }
    }
}
