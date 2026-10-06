package com.friday.ai.service.mail

import android.util.Log
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import kotlinx.coroutines.flow.catch

/** Retells a long mail in a few spoken sentences. */
class MailRetelling(
    private val groq: GroqApiService,
    private val prefDao: UserPreferenceDao,
    private val catalog: com.friday.ai.service.ModelCatalog
) {

    private companion object {
        const val TAG = "MailRetelling"
        const val SENT_TO_MODEL_MAX = 4000
        const val SUMMARY_TOKENS = 160
    }

    suspend fun summarise(text: String, russian: Boolean): String? {
        val apiKey = prefDao.get("groq_api_key")?.takeIf { it.isNotBlank() } ?: return null
        val model = catalog.model(com.friday.ai.core.GroqModels.Role.CHAT)
        val instruction = if (russian) {
            "Перескажи письмо в двух-трёх коротких предложениях, чтобы его можно было прочитать вслух. " +
                "Только суть: что сообщают или просят, сроки, цифры. Без вступлений. По-русски."
        } else {
            "Retell this email in two or three short sentences suitable for reading aloud. " +
                "Only the substance: what is said or asked, deadlines, numbers. No preamble."
        }
        val out = StringBuilder()
        groq.streamCompletion(
            apiKey,
            listOf(
                ApiMessage(role = "system", content = instruction),
                ApiMessage(role = "user", content = text.take(SENT_TO_MODEL_MAX))
            ),
            model,
            maxTokens = SUMMARY_TOKENS
        )
            .catch { e ->
                Log.w(TAG, "Summary failed: ${e.message}")
                if (e is com.friday.ai.data.remote.GroqApiException && e.modelMissing) catalog.refresh()
            }
            .collect { out.append(it) }
        return out.toString().trim().ifBlank { null }
    }
}
