package com.friday.ai.service

import android.media.AudioRecord
import android.util.Log

/**
 * Stops and releases a recorder, each step on its own.
 *
 * `stop()` throws on a recorder that never initialised — which is exactly what
 * happens when another app holds the microphone. With both calls in one `try`,
 * that exception skipped `release()` and the native handle lingered until the
 * garbage collector found it.
 */
fun AudioRecord?.stopAndRelease(tag: String) {
    val record = this ?: return
    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(tag, "AudioRecord.stop failed: ${e.message}")
        }
    }
    try {
        record.release()
    } catch (e: Exception) {
        Log.w(tag, "AudioRecord.release failed: ${e.message}")
    }
}
