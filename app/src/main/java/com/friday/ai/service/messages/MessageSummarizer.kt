package com.friday.ai.service.messages

import android.util.Log
import com.friday.ai.core.GroqModels
import com.friday.ai.core.messages.Conversation
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.service.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A long chat told in a sentence or two instead of message by message:
 * "Просит перезвонить и спрашивает, будете ли вы к семи".
 *
 * Only the chat being read goes to Groq, only when the owner asked for it,
 * and nothing is kept. Null when there is no key, no answer in time, or an
 * error — the messages are then read as they are.
 */
class MessageSummarizer(
    private val groq: GroqApiService,
    private val catalog: ModelCatalog,
    private val prefs: UserPreferenceDao
) {

    private companion object {
        const val TAG = "MessageSummarizer"
        const val TIMEOUT_MS = 8_000L
        const val MAX_TOKENS = 160
        const val MESSAGE_CHARS = 600

        const val PROMPT_RU = "Ты пересказываешь владельцу телефона непрочитанные сообщения. Перескажи их по-русски " +
            "одним-двумя короткими предложениями, от третьего лица («Просит…», «Пишет, что…»). Сохрани важное: " +
            "время, место, числа, вопросы и просьбы. Ничего не добавляй от себя. Без кавычек, списков и markdown."
        const val PROMPT_EN = "You tell the phone's owner what their unread messages say. Summarise them in one or " +
            "two short sentences, third person (\"Asks you to…\", \"Says that…\"). Keep times, places, numbers, " +
            "questions and requests. Add nothing. No quotes, lists or markdown."

        val thinking = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    }

    suspend fun summarize(chat: Conversation, russian: Boolean): String? = withContext(Dispatchers.IO) {
        val key = prefs.get("groq_api_key")?.takeIf { it.isNotBlank() } ?: return@withContext null
        val transcript = chat.messages.joinToString("\n") { "${it.sender}: ${it.text.take(MESSAGE_CHARS)}" }
        val request = listOf(
            ApiMessage(role = "system", content = if (russian) PROMPT_RU else PROMPT_EN),
            ApiMessage(role = "user", content = "${chat.title} (${chat.app}):\n$transcript")
        )
        val text = StringBuilder()
        val done = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                groq.streamCompletion(key, request, catalog.model(GroqModels.Role.FAST), MAX_TOKENS)
                    .collect { text.append(it) }
            }.onFailure { Log.w(TAG, "Summary failed: ${it.message}") }.isSuccess
        }
        if (done != true) return@withContext null
        text.toString().replace(thinking, "").trim().trim('"', '«', '»').ifBlank { null }
    }
}
