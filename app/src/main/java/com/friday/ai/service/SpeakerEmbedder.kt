package com.friday.ai.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.friday.ai.core.KaldiFbank
import java.nio.FloatBuffer

/**
 * Turns a piece of speech into a 256-dimensional speaker embedding.
 *
 * Model: wespeaker `voxceleb_resnet34_LM` (CC-BY-4.0), ResNet34 trained on
 * VoxCeleb with large-margin fine-tuning. It is text-independent, so the same
 * profile works for the wake word and for anything said afterwards.
 *
 * The weights are stored as float16 and cast to float32 inside the graph;
 * ONNX Runtime folds those casts when the session is created, so inference is
 * unchanged (embeddings match the float32 model to 0.9999) and the file is
 * half the size. Int8 quantisation was tried and dropped: ten times slower
 * on CPU, and embeddings drifted to 0.91.
 *
 * The session is expensive to create and cheap to reuse, so it is built once
 * and held for the life of the service.
 */
class SpeakerEmbedder(private val context: Context) {

    private companion object {
        const val TAG = "SpeakerEmbedder"
        const val MODEL_ASSET = "speaker_embedding.onnx"
        const val INPUT = "feats"

        /** Below this the embedding is dominated by whatever else was in the room. */
        const val MIN_SAMPLES = KaldiFbank.SAMPLE_RATE / 2
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        return try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // One thread: this runs beside audio capture, and the model is
                // small enough that latency is fine without stealing cores.
                setIntraOpNumThreads(1)
            }
            env = environment
            session = environment.createSession(bytes, options)
            Log.i(TAG, "Speaker model loaded (${bytes.size / 1024} KB)")
            session
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load speaker model: ${e.message}")
            null
        }
    }

    /** True once the model is on hand, so callers can fail early and quietly. */
    fun isAvailable(): Boolean = ensureSession() != null

    /**
     * @param samples 16 kHz mono at int16 scale — the same scale the recorder
     *   produces, and the scale the features were trained on
     * @return a 256-float embedding, or null if the audio was too short or the
     *   model is unavailable
     */
    fun embed(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_SAMPLES) return null
        val ortSession = ensureSession() ?: return null

        return try {
            val feats = KaldiFbank.compute(samples)
            if (feats.isEmpty()) return null
            KaldiFbank.subtractMean(feats)

            val frames = feats.size
            val flat = FloatArray(frames * KaldiFbank.NUM_MEL_BINS)
            for (t in 0 until frames) {
                System.arraycopy(feats[t], 0, flat, t * KaldiFbank.NUM_MEL_BINS, KaldiFbank.NUM_MEL_BINS)
            }

            val shape = longArrayOf(1, frames.toLong(), KaldiFbank.NUM_MEL_BINS.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { tensor ->
                ortSession.run(mapOf(INPUT to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as Array<FloatArray>
                    out[0].copyOf()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Embedding failed: ${e.message}")
            null
        }
    }

    @Synchronized
    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {}
        session = null
    }
}
