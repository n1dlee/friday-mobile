package com.friday.ai

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * An invisible activity that hands something to the voice service: the
 * side button, an NFC tag, a mode shortcut.
 *
 * The service holds the microphone, and Android only lets an app start a
 * microphone service while it is in the foreground. So the activity stays a
 * moment after handing over — long enough for the service to take the
 * microphone — instead of closing at once. Meanwhile touches pass straight
 * through to whatever is underneath.
 */
abstract class HandOffActivity : Activity() {

    private companion object {
        const val LINGER_MS = 1_000L
    }

    /** Runs once, while this activity is in the foreground. */
    protected abstract fun handOff()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        if (savedInstanceState == null) handOff()
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finishQuietly() }, LINGER_MS)
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
