package com.friday.ai.data.remote

import android.util.Log
import com.friday.ai.data.remote.dto.LazuriDeviceRegisterRequest
import com.friday.ai.data.remote.dto.LazuriDeviceResponse
import com.friday.ai.data.remote.dto.LazuriMemoryCreateRequest
import com.friday.ai.data.remote.dto.LazuriNoteCreateRequest
import com.friday.ai.data.remote.dto.LazuriMemoryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for the Lazuri Core backend — the shared "second brain" (notes,
 * tasks, memory) that all of the user's devices sync through. Every call is
 * best-effort: Lazuri may be offline (server down, off the Tailscale
 * network), and Friday Mobile must keep working fully on its local Room DB
 * in that case. Callers should treat failures as non-fatal.
 */
class LazuriApiService private constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    companion object {
        private const val TAG = "LazuriApiService"

        fun create(): LazuriApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

            return LazuriApiService(client, json)
        }
    }

    class LazuriApiException(val code: Int, override val message: String) : Exception(message)

    suspend fun checkHealth(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${normalize(baseUrl)}/actuator/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    suspend fun registerDevice(
        baseUrl: String,
        apiKey: String,
        name: String,
        platform: String = "phone"
    ): LazuriDeviceResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            LazuriDeviceRegisterRequest.serializer(),
            LazuriDeviceRegisterRequest(name, platform)
        )
        val response = post("${normalize(baseUrl)}/api/v1/devices/register", apiKey, body)
        json.decodeFromString(LazuriDeviceResponse.serializer(), response)
    }

    suspend fun createMemory(
        baseUrl: String,
        apiKey: String,
        role: String,
        content: String,
        sourceDeviceId: String?,
        sessionId: String? = null,
        metadata: Map<String, String>? = null
    ): LazuriMemoryResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            LazuriMemoryCreateRequest.serializer(),
            LazuriMemoryCreateRequest(sessionId, role, content, sourceDeviceId, metadata)
        )
        val response = post("${normalize(baseUrl)}/api/v1/memory", apiKey, body)
        json.decodeFromString(LazuriMemoryResponse.serializer(), response)
    }

    /**
     * Saves a note to the shared brain, so "запиши идею" on the phone is
     * readable from the PC. Best-effort like everything else here.
     */
    suspend fun createNote(
        baseUrl: String,
        apiKey: String,
        title: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(
                LazuriNoteCreateRequest.serializer(),
                LazuriNoteCreateRequest(title = title, content = content)
            )
            post("${normalize(baseUrl)}/api/v1/notes", apiKey, body)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Note push failed: ${e.message}")
            false
        }
    }

    suspend fun listMemory(
        baseUrl: String,
        apiKey: String,
        since: String? = null,
        limit: Int = 100
    ): List<LazuriMemoryResponse> = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder("${normalize(baseUrl)}/api/v1/memory?limit=$limit")
        if (since != null) urlBuilder.append("&since=$since")

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("X-API-Key", apiKey)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw LazuriApiException(response.code, "Lazuri error: ${response.code}")
            }
            val body = response.body?.string() ?: "[]"
            json.decodeFromString(ListSerializer(LazuriMemoryResponse.serializer()), body)
        }
    }

    private fun post(url: String, apiKey: String, jsonBody: String): String {
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw LazuriApiException(response.code, "Lazuri error (${response.code}): $errorBody")
            }
            return response.body?.string() ?: throw LazuriApiException(0, "Empty response")
        }
    }

    private fun normalize(baseUrl: String): String = baseUrl.trimEnd('/')
}
