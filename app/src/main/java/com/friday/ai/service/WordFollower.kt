package com.friday.ai.service

import android.util.Log
import com.friday.ai.core.audio.SpeechEndpointer
import com.friday.ai.core.audio.SpokenEnd
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Follows a command word by word on the phone, while it is recorded, and
 * ends the recording when the sentence ends ([SpokenEnd]).
 *
 * Free-form decoding on the wake word's own model: nothing is downloaded or
 * loaded again. Its words time the end and provide a local transcript when
 * cloud recognition is unavailable or returns no useful words.
 */
class WordFollower private constructor(private val decoder: Recognizer) : AutoCloseable {

    companion object {
        private const val TAG = "WordFollower"
        private const val SAMPLE_RATE = 16_000f

        /** Null when a decoder cannot be made; the recording then ends by sound alone. */
        fun open(model: Model): WordFollower? = try {
            WordFollower(Recognizer(model, SAMPLE_RATE))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "No word follower: ${e.message}")
            null
        }
    }

    private val spoken = SpokenEnd()

    val wordCount: Int get() = spoken.wordCount

    /** One read of the recording; ends [end] once the words say the owner is done. */
    fun feed(buffer: ShortArray, read: Int, end: SpeechEndpointer) {
        if (decoder.acceptWaveForm(buffer, read)) spoken.onFinal(VoskResults.text(decoder.result), end.elapsedMs)
        else spoken.onPartial(VoskResults.text(decoder.partialResult))
        if (spoken.finished(end.elapsedMs)) end.endByWords()
    }

    /**
     * Everything the decoder heard, the unfinished end included. Whisper is
     * the better ear, but on one short word — "да", "нет", "второй" — it
     * often returns nothing, or the names it was prompted with; these words
     * are what was said then.
     */
    fun heard(): String =
        listOf(spoken.text, VoskResults.text(decoder.finalResult)).filter { it.isNotBlank() }.joinToString(" ")

    override fun close() = decoder.close()
}
