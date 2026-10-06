package com.friday.ai.service

import android.util.Log
import com.friday.ai.core.GroqModels
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.GroqApiService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Knows which Groq models this key can use, and hands out the right one per job.
 *
 * The list is fetched at start-up and again whenever Groq answers "no such
 * model", so a retired model costs one failed request instead of a silent,
 * permanent breakage.
 */
class ModelCatalog(
    private val groq: GroqApiService,
    private val prefDao: UserPreferenceDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        /** The user's pick for conversation, from settings. */
        const val PREF_CHOSEN = "groq_model"
        private const val PREF_AVAILABLE = "groq_models"
        private const val TAG = "ModelCatalog"
    }

    suspend fun model(role: GroqModels.Role): String {
        val chosen = if (role == GroqModels.Role.CHAT) prefDao.get(PREF_CHOSEN)?.ifBlank { null } else null
        return GroqModels.pick(role, available(), chosen) ?: GroqModels.fallback(role)
    }

    /** Last known list; empty until the first successful refresh. */
    suspend fun available(): Set<String> =
        prefDao.get(PREF_AVAILABLE)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /**
     * Re-reads the live list. A chosen model that has disappeared is replaced
     * by the best one available, so settings never show a model that no
     * longer answers.
     *
     * @return false when the list could not be fetched (no key, no network)
     */
    suspend fun refresh(): Boolean = withContext(io) {
        val key = prefDao.get("groq_api_key")?.takeIf { it.isNotBlank() } ?: return@withContext false
        val ids = try {
            groq.listModels(key)
        } catch (e: Exception) {
            Log.w(TAG, "Model list unavailable: ${e.message}")
            return@withContext false
        }
        if (ids.isEmpty()) return@withContext false
        prefDao.set(UserPreferenceEntity(PREF_AVAILABLE, ids.sorted().joinToString(",")))

        val chosen = prefDao.get(PREF_CHOSEN)
        if (chosen.isNullOrBlank() || chosen !in ids) {
            val replacement = GroqModels.pick(GroqModels.Role.CHAT, ids) ?: return@withContext true
            prefDao.set(UserPreferenceEntity(PREF_CHOSEN, replacement))
            Log.i(TAG, "Chat model ${chosen ?: "(none)"} unavailable; now $replacement")
        }
        true
    }
}
