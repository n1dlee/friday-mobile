package com.friday.ai

import android.app.Activity
import android.os.Bundle
import com.friday.ai.service.FridayWakeWordService

/**
 * What the system opens for "the assistant": a long press of the side
 * button (Samsung: Side button → Press and hold → Digital assistant), the
 * assist gesture, a headset's voice button.
 *
 * It has no screen of its own. It asks the voice service to start a
 * conversation as if "Пятница" had been heard, and closes; the listening
 * panel appears over whatever was open. Declaring it is what makes Friday
 * selectable as the phone's digital assistant.
 *
 * The press is not a voice: with a voice profile recorded, what is said
 * next is still checked against it.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started while this activity is in the foreground, so Android allows a
        // microphone service even if Friday wasn't running.
        FridayWakeWordService.invoke(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
