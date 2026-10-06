package com.friday.ai.data.remote

import android.util.Log
import com.friday.ai.core.ErrandPlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Finds shops and other places near a point, using OpenStreetMap's Overpass
 * API — free, no key, no account.
 *
 * Queries are slow by web-API standards (ten seconds is normal), which is
 * fine here: this runs on a background schedule, never while the user waits.
 */
class NearbyPlacesService private constructor(
    private val client: OkHttpClient,
    private val json: Json
) {

    companion object {
        private const val TAG = "NearbyPlaces"
        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

        /** The radius the user asked for. */
        const val DEFAULT_RADIUS_METERS = 500

        fun create(): NearbyPlacesService = NearbyPlacesService(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .build(),
            Json { ignoreUnknownKeys = true; isLenient = true }
        )
    }

    data class Place(
        val name: String,
        val kind: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Int
    )

    /**
     * Places matching [placeTypes] within [radius] of the given point,
     * nearest first. Empty on any failure — a missed reminder is better than
     * a crash in a background worker.
     */
    suspend fun near(
        latitude: Double,
        longitude: Double,
        placeTypes: List<ErrandPlaces.PlaceType>,
        radius: Int = DEFAULT_RADIUS_METERS
    ): List<Place> = withContext(Dispatchers.IO) {
        if (placeTypes.isEmpty()) return@withContext emptyList()

        val clauses = placeTypes.joinToString("\n  ") {
            it.overpassClause(radius, latitude, longitude)
        }
        val query = """
            [out:json][timeout:25];
            (
              $clauses
            );
            out body 30;
        """.trimIndent()

        try {
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("User-Agent", "FridayAI/1.0 (personal assistant)")
                .post(FormBody.Builder().add("data", query).build())
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Overpass returned ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val elements = json.parseToJsonElement(body)
                    .jsonObject["elements"]?.jsonArray ?: return@withContext emptyList()

                elements.mapNotNull { element ->
                    val obj = element.jsonObject
                    val lat = obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val lon = obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val tags = obj["tags"]?.jsonObject
                    Place(
                        name = tags?.get("name")?.jsonPrimitive?.content ?: "Место",
                        kind = tags?.get("shop")?.jsonPrimitive?.content
                            ?: tags?.get("amenity")?.jsonPrimitive?.content
                            ?: "place",
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = ErrandPlaces.distanceMeters(latitude, longitude, lat, lon)
                    )
                }.sortedBy { it.distanceMeters }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lookup failed: ${e.message}")
            emptyList()
        }
    }
}
