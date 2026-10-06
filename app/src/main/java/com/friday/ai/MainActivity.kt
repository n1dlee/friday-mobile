package com.friday.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.friday.ai.ui.navigation.FridayNavGraph
import com.friday.ai.ui.theme.FridayTheme

class MainActivity : ComponentActivity() {

    var onWakeWordActivated: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWakeWordIntent(intent)

        setContent {
            FridayTheme {
                FridayNavGraph(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeWordIntent(intent)
    }

    private fun handleWakeWordIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("wake_word_activated", false) == true) {
            onWakeWordActivated?.invoke()
        }
    }
}
