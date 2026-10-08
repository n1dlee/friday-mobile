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
class SpeakerGate(
    private val embedder: SpeakerEmbedder,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime
) {

    private companion object {
        const val TAG = "SpeakerGate"

        /** How much below the profile's threshold a lone wake word may score. */
        const val WAKE_SLACK = 0.06f

        /**
         * How much below it a command may score right after the owner's
         * wake word passed. The owner was recognised seconds ago and is
         * plainly the one answering "Слушаю"; scoring the command as strictly
         * as a stranger's turned them away — "Не узнала голос" on the second
         * try, after the first had already failed.
         */
        const val COMMAND_SLACK_AFTER_WAKE = 0.1f

        /** How long a recognised wake word vouches for what follows. */
        const val WAKE_TRUST_MS = 30_000L
    }

    @Volatile
    private var wakeVerifiedAt: Long? = null

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

    /**
     * Whether the wake word in [audio] came from the owner. Only the spoken
     * part is checked, like the enrolment; and a single short word scores a
     * little lower than a phrase, so it gets a little slack.
     */
    fun admitsWake(audio: FloatArray): Boolean {
        val passed = profile?.let { isOwner(it, com.friday.ai.core.VoiceTrim.speech(audio), "wake word", WAKE_SLACK) }
        if (passed == true) wakeVerifiedAt = clock()
        return passed ?: true
    }

    /**
     * The check applied to what is said after the wake word, or null when
     * there is nothing to check against. A wake-word-only profile is left out
     * on purpose: it rejects its own owner's commands about half the time.
     */
    fun commandCheck(): ((FloatArray) -> Boolean)? {
        val p = profile?.takeIf { it.coversCommands } ?: return null
        return { audio ->
            val vouched = wakeVerifiedAt?.let { clock() - it < WAKE_TRUST_MS } == true
            val slack = if (vouched) COMMAND_SLACK_AFTER_WAKE else 0f
            isOwner(p, com.friday.ai.core.VoiceTrim.speech(audio), "command", slack)
        }
    }

    private fun isOwner(p: VoiceProfile.Profile, audio: FloatArray, what: String, slack: Float = 0f): Boolean {
        val embedding = embedder.embed(audio) ?: run {
            Log.w(TAG, "Could not embed $what audio; letting it through")
            return true
        }
        val score = VoiceProfile.score(p, embedding)
        val threshold = p.threshold - slack
        val passed = score >= threshold
        Log.i(TAG, "Speaker score for %s %.3f against %.3f".format(what, score, threshold))
        VoiceChecks.record(what, score, threshold, passed)
        return passed
    }
}
