package com.friday.ai.core

import kotlin.math.sqrt

/**
 * The enrolled owner's voice, and the decision of whether a given utterance
 * came from them.
 *
 * Kept free of Android and of the model so the scoring, the calibration and
 * the serialisation can all be tested directly — the parts most likely to be
 * subtly wrong are exactly the parts a device test would not surface.
 */
object VoiceProfile {

    /**
     * @param centroid   unit-length mean of the enrolled embeddings
     * @param threshold  cosine below which an utterance is not the owner
     * @param cohesion   how alike the enrolment samples were, for diagnostics
     * @param version    which enrolment produced it; see [coversCommands]
     */
    data class Profile(
        val centroid: FloatArray,
        val threshold: Float,
        val cohesion: Float,
        val sampleCount: Int,
        val version: Int = CURRENT_VERSION
    ) {
        /**
         * Whether this profile can judge what is said *after* the wake word.
         *
         * Version 1 was enrolled on "Пятница" alone. Measured against
         * synthetic speech, such a profile rejected its own owner's commands
         * 33 times out of 60: the centroid had learned the word as much as the
         * voice. It still verifies the wake word fine, so it keeps doing that
         * and nothing more.
         */
        val coversCommands: Boolean get() = version >= 2

        // Data classes compare arrays by reference; these exist so equality
        // means what a caller would assume.
        override fun equals(other: Any?): Boolean =
            other is Profile &&
                centroid.contentEquals(other.centroid) &&
                threshold == other.threshold &&
                cohesion == other.cohesion &&
                sampleCount == other.sampleCount &&
                version == other.version

        override fun hashCode(): Int = centroid.contentHashCode() * 31 + sampleCount
    }

    /** Fewer than this and the centroid is dominated by one bad recording. */
    const val MIN_SAMPLES = 5

    const val CURRENT_VERSION = 2

    /** Serialised field count with the version tag in front; untagged is one fewer. */
    private const val TAGGED_FIELDS = 5

    /**
     * What the user is asked to say during enrolment, in order.
     *
     * The wake word alternates with ordinary commands so the profile learns
     * the voice rather than one word. With this mix the same synthetic test
     * that broke the wake-only profile rejected none of 60 own commands and
     * accepted none of 360 other voices.
     */
    val ENROLMENT_PHRASES = listOf(
        "Пятница",
        "Какая погода завтра",
        "Пятница",
        "Позвони маме",
        "Поставь будильник на семь тридцать",
        "Пятница",
        "Включи музыку",
        "Напомни купить молоко"
    )

    /**
     * Floor and ceiling for the calibrated threshold.
     *
     * The floor stops a set of wildly inconsistent recordings from producing a
     * profile that accepts everyone; the ceiling stops an unusually tidy
     * enrolment from producing one that rejects the owner on a bad day.
     */
    const val MIN_THRESHOLD = 0.30f
    const val MAX_THRESHOLD = 0.62f

    /**
     * How far below the enrolment's own cohesion an utterance may fall.
     *
     * Calibrating against the user's own consistency beats a fixed number:
     * someone who says the wake word the same way every time gets a tight
     * profile, and someone who varies gets a loose one, without either having
     * to tune anything.
     */
    private const val COHESION_SLACK = 0.22f

    /**
     * Builds a profile from enrolment embeddings.
     *
     * @param embeddings raw model outputs; normalisation happens here
     * @return null when there are too few samples to mean anything
     */
    fun enroll(embeddings: List<FloatArray>): Profile? {
        if (embeddings.size < MIN_SAMPLES) return null
        val unit = embeddings.map { normalise(it) }

        val dim = unit[0].size
        val centroid = FloatArray(dim)
        unit.forEach { e -> for (i in 0 until dim) centroid[i] += e[i] }
        for (i in 0 until dim) centroid[i] /= unit.size
        val centre = normalise(centroid)

        // Cohesion is the *worst* sample's agreement with the centre, not the
        // average: the threshold has to admit the user's least typical
        // reading, or enrolment quietly excludes a way they actually speak.
        val cohesion = unit.minOf { cosine(centre, it) }
        val threshold = (cohesion - COHESION_SLACK).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        return Profile(centre, threshold, cohesion, unit.size)
    }

    fun score(profile: Profile, embedding: FloatArray): Float =
        cosine(profile.centroid, normalise(embedding))

    fun accepts(profile: Profile, embedding: FloatArray): Boolean =
        score(profile, embedding) >= profile.threshold

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot.toFloat()
    }

    fun normalise(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f || !norm.isFinite()) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    // --- storage ------------------------------------------------------
    // Plain text rather than a Room table: one profile exists, it is small,
    // and it belongs with the other settings.

    fun serialise(profile: Profile): String = buildString {
        append('v'); append(profile.version); append('|')
        append(profile.threshold); append('|')
        append(profile.cohesion); append('|')
        append(profile.sampleCount); append('|')
        append(profile.centroid.joinToString(","))
    }

    fun deserialise(raw: String?): Profile? {
        if (raw.isNullOrBlank()) return null
        return try {
            var parts = raw.split('|')
            // Version 1 had no tag: four fields, threshold first.
            val version = if (parts.size == TAGGED_FIELDS && parts[0].startsWith("v")) {
                parts[0].drop(1).toInt().also { parts = parts.drop(1) }
            } else 1
            if (parts.size != 4) return null
            val centroid = parts[3].split(',').map { it.toFloat() }.toFloatArray()
            if (centroid.isEmpty()) return null
            Profile(
                centroid = centroid,
                threshold = parts[0].toFloat(),
                cohesion = parts[1].toFloat(),
                sampleCount = parts[2].toInt(),
                version = version
            )
        } catch (_: Exception) {
            // A corrupt profile must read as "not enrolled", never crash the
            // voice loop on startup.
            null
        }
    }
}
