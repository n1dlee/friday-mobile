package com.friday.ai.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.friday.ai.core.OfflineCommands
import com.friday.ai.core.PcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Understands a handful of device commands with no network.
 *
 * Uses the same on-device model as the wake word, but with a grammar of
 * command phrases instead of just the assistant's name. Accuracy comes from
 * the grammar being closed: the decoder picks among known phrases rather than
 * guessing at open speech, which is what makes this usable from a model small
 * enough to ship.
 */
class OfflineCommandRecognizer(private val context: Context, private val sharedModel: () -> Model? = { null }) {

    @Volatile private var activeRecord: AudioRecord? = null

    fun abort() {
        activeRecord.interruptCapture(TAG)
    }

    private companion object {
        const val TAG = "OfflineCommands"
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val SILENCE_THRESHOLD = 250.0
        const val SILENCE_DURATION_MS = 900L
        const val MAX_RECORD_MS = 6000L
        const val LEAD_IN_MS = 3500L
    }

    /** True when there's no usable internet, so Whisper would fail anyway. */
    fun isOffline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read connectivity: ${e.message}")
        false
    }

    /**
     * Records and decodes one command against the offline grammar.
     * @return the recognised phrase, or null if nothing usable was heard.
     */
    suspend fun listenForCommand(modelPath: String): String? = withContext(Dispatchers.IO) {
        var ownedModel: Model? = null
        var recognizer: Recognizer? = null
        var audioRecord: AudioRecord? = null

        try {
            currentCoroutineContext().ensureActive()
            val model = sharedModel() ?: Model(modelPath).also { ownedModel = it }
            val grammar = JSONArray(OfflineCommands.GRAMMAR + "[unk]").toString()
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar)

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                .coerceAtLeast(4096)
            audioRecord = openVoiceRecord(SAMPLE_RATE, bufferSize, TAG) ?: return@withContext null
            activeRecord = audioRecord

            currentCoroutineContext().ensureActive()
            audioRecord.startRecording()
            val heardSpeech = capture(audioRecord, recognizer, ShortArray(bufferSize / 2))

            audioRecord.stop()
            if (!heardSpeech) return@withContext null

            val text = JSONObject(recognizer.finalResult).optString("text", "").trim()
            // "[unk]" means the decoder matched nothing in the grammar.
            val cleaned = text.replace("[unk]", "").replace(Regex("\\s+"), " ").trim()

            cleaned.ifBlank { null }?.also { Log.i(TAG, "Offline heard ${it.length} characters") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Offline recognition failed: ${e.message}")
            null
        } finally {
            audioRecord.stopAndRelease(TAG)
            if (activeRecord === audioRecord) activeRecord = null
            runCatching { recognizer?.close() }
            runCatching { ownedModel?.close() }
        }
    }

    private suspend fun capture(record: AudioRecord, recognizer: Recognizer, buffer: ShortArray): Boolean {
        var totalMs = 0L
        var silenceMs = 0L
        var heardSpeech = false
        while (totalMs < MAX_RECORD_MS) {
            currentCoroutineContext().ensureActive()
            val read = record.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read <= 0) break
            totalMs += read * 1000L / SAMPLE_RATE
            if (PcmAudio.rms(buffer, read) > SILENCE_THRESHOLD) {
                heardSpeech = true
                silenceMs = 0
            } else if (heardSpeech) {
                silenceMs += read * 1000L / SAMPLE_RATE
                if (silenceMs >= SILENCE_DURATION_MS) break
            } else if (totalMs >= LEAD_IN_MS) {
                break
            }
            val bytes = PcmAudio.toLittleEndian(buffer, read)
            recognizer.acceptWaveForm(bytes, bytes.size)
        }
        return heardSpeech
    }
}
