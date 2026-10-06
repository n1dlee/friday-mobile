package com.friday.ai.data.remote

import com.friday.ai.core.mail.GmailMessages
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The four Gmail REST calls Friday needs, over plain OkHttp.
 *
 * The official client library would add megabytes for list, get, send and
 * modify; these are small enough to write directly, and the parsing lives in
 * [GmailMessages] where it is tested.
 */
class GmailApi(private val http: OkHttpClient) {

    /** A non-2xx reply. 401 means the token went stale and is worth one retry. */
    class HttpError(val code: Int, message: String) : IOException(message)

    companion object {
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_S = 20L

        /** Enough of an error body to log what went wrong. */
        private const val ERROR_EXCERPT = 200

        fun create() = GmailApi(
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        )
    }

    suspend fun accountEmail(token: String): String =
        JSONObject(get(token, "$BASE/profile")).getString("emailAddress")

    suspend fun list(token: String, query: String, max: Int): GmailMessages.Listing {
        val url = "$BASE/messages".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", max.toString())
            .build().toString()
        return GmailMessages.parseListing(get(token, url))
    }

    /** Headers and snippet only — cheap enough to fetch a screenful at once. */
    suspend fun metadata(token: String, id: String): GmailMessages.Mail {
        val url = "$BASE/messages/$id".toHttpUrl().newBuilder()
            .addQueryParameter("format", "metadata")
            .apply {
                listOf("From", "To", "Subject", "Message-ID", "References")
                    .forEach { addQueryParameter("metadataHeaders", it) }
            }
            .build().toString()
        return GmailMessages.parseMessage(get(token, url))
    }

    suspend fun full(token: String, id: String): GmailMessages.Mail =
        GmailMessages.parseMessage(get(token, "$BASE/messages/$id?format=full"))

    suspend fun send(token: String, raw: String, threadId: String?) {
        val body = JSONObject().put("raw", raw).apply { threadId?.let { put("threadId", it) } }
        post(token, "$BASE/messages/send", body)
    }

    suspend fun markRead(token: String, id: String) {
        post(token, "$BASE/messages/$id/modify", JSONObject().put("removeLabelIds", JSONArray().put("UNREAD")))
    }

    private suspend fun get(token: String, url: String): String =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").build())

    private suspend fun post(token: String, url: String, json: JSONObject): String =
        execute(
            Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .post(json.toString().toRequestBody(JSON))
                .build()
        )

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpError(r.code, "Gmail ${r.code}: ${text.take(ERROR_EXCERPT)}")
            text
        }
    }
}
