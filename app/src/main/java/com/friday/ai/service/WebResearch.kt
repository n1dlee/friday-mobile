package com.friday.ai.service

import android.util.Log
import com.friday.ai.core.GroqModels
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Answers from the web, through Groq's built-in browser search.
 *
 * The chat model's knowledge stops at its training date; asked who won the
 * last World Cup or what the newest iPhone is, it answered with confidence
 * and was two years out of date. This looks it up instead.
 *
 * Findings from live tests (October 2026) that shaped it:
 *  - Without today's date the search model also answered from memory, or
 *    ranked old pages first. With the date and "always search" it found the
 *    2026 results.
 *  - It runs on the small model: searching costs it 2–5k tokens of its own
 *    per-minute allowance, and the conversation model keeps all of its own.
 *  - The answer comes back in English with sources; the conversation model
 *    retells it in the user's language. Asked to answer in the user's
 *    language directly, the search model once replied in Spanish.
 */
class WebResearch(
    private val groq: GroqApiService,
    private val catalog: ModelCatalog,
    private val prefDao: UserPreferenceDao,
    private val now: () -> ZonedDateTime = ZonedDateTime::now
) {

    companion object {
        private const val TAG = "WebResearch"

        /** Groq's "try again in 2.1s" is worth waiting for; longer is not. */
        private const val WORTH_WAITING_MS = 3_000L

        private val citation = Regex("""【[^】]*】""")
        private val markdown = Regex("""\*\*|__|^#+\s*""", RegexOption.MULTILINE)

        /** What the search model is told; the date is what keeps it from answering from memory. */
        fun instructions(today: ZonedDateTime): String {
            val date = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
            return "Today is $date. You look things up on the web for a voice assistant. ALWAYS search before " +
                "answering — never answer from memory, it is out of date. Prefer the most recent results. Reply in " +
                "English with only the facts that answer the question: 1-3 short sentences, with the date of the " +
                "information and the source site names. No citation markers, no markdown."
        }

        /** Strips the 【1†L5-L13】 markers and markdown the model leaves in anyway. */
        fun clean(answer: String): String =
            answer.replace(citation, "").replace(markdown, "").replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    suspend fun answer(question: String, russian: Boolean): String {
        val apiKey = prefDao.get("groq_api_key").orEmpty()
        val model = catalog.model(GroqModels.Role.FAST)
        return try {
            clean(lookUp(apiKey, model, question))
        } catch (e: GroqApiException) {
            Log.w(TAG, "Search failed: ${e.message}")
            when {
                e.rateLimited && russian -> "Поиск сейчас упёрся в минутный лимит Groq — спросите через минуту."
                e.rateLimited -> "Search hit Groq's per-minute limit — ask again in a minute."
                russian -> "Не получилось поискать в интернете: ${e.message}"
                else -> "Couldn't search the web: ${e.message}"
            }
        }
    }

    /** One retry for the two failures that pass: a short rate-limit wait and a garbled tool call. */
    private suspend fun lookUp(apiKey: String, model: String, question: String): String {
        val system = instructions(now())
        return try {
            groq.browse(apiKey, model, system, question)
        } catch (e: GroqApiException) {
            val wait = e.retryAfterMs?.takeIf { e.rateLimited && it <= WORTH_WAITING_MS }
            when {
                wait != null -> delay(wait)
                e.outputParseFailed -> Log.w(TAG, "Garbled search output, retrying")
                else -> throw e
            }
            groq.browse(apiKey, model, system, question)
        }
    }
}
