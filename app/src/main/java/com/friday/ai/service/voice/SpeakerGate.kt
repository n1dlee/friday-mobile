package com.friday.ai.service.voice

import android.util.Log
import com.friday.ai.core.VoiceProfile
import com.friday.ai.service.SpeakerEmbedder

/**
 * Decides whether the person speaking is the enrolled owner.
 *
 * Fails **open** throughout. With no profile, a model that will not load, or
 * audio too short to score ("стоп", "да"), the turn goes through: an
 * assistant that answers nobody is a far worse failure than one that
 * occasionally answers the wrong person.
 */
class SpeakerGate(private val embedder: SpeakerEmbedder) {

    private companion object {
        const val TAG = "SpeakerGate"
    }

    @Volatile
    var profile: VoiceProfile.Profile? = null
        private set

    fun load(serialised: String?) {
        profile = VoiceProfile.deserialise(serialised)
        Log.i(
            TAG,
            "Voice profile: " + (profile?.let { "v${it.version}, ${it.sampleCount} samples, threshold ${it.threshold}" }
                ?: "none — anyone may wake Friday")
        )
    }

    /** Whether the wake word in [audio] came from the owner. */
    fun admitsWake(audio: FloatArray): Boolean =
        profile?.let { isOwner(it, audio, "wake word") } ?: true

    /**
     * The check applied to what is said after the wake word, or null when
     * there is nothing to check against. A wake-word-only profile is left out
     * on purpose: it rejects its own owner's commands about half the time.
     */
    fun commandCheck(): ((FloatArray) -> Boolean)? {
        val p = profile?.takeIf { it.coversCommands } ?: return null
        return { audio -> isOwner(p, audio, "command") }
    }

    private fun isOwner(p: VoiceProfile.Profile, audio: FloatArray, what: String): Boolean {
        val embedding = embedder.embed(audio) ?: run {
            Log.w(TAG, "Could not embed $what audio; letting it through")
            return true
        }
        val score = VoiceProfile.score(p, embedding)
        Log.i(TAG, "Speaker score for %s %.3f against %.3f".format(what, score, p.threshold))
        return score >= p.threshold
    }
}
