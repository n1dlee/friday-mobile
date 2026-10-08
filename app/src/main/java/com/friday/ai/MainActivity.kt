package com.friday.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.friday.ai.core.DeviceContext
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.service.FridayWakeWordService
import com.friday.ai.ui.navigation.FridayNavGraph
import com.friday.ai.ui.theme.FridayTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    var onWakeWordActivated: (() -> Unit)? = null

    private val deviceContext: DeviceContext by inject()
    private val prefs: UserPreferenceDao by inject()

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

    /**
     * Back from a system settings screen, a permission may have changed:
     * re-read what works, so the next request (and the diagnostics screen)
     * reflects it.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            deviceContext.refresh()
            // An update or a reboot stops the listening service, and Android
            // won't let a microphone service start itself in the background.
            // Opening Friday brings her back; if she is already listening,
            // this changes nothing.
            if (prefs.get("wake_word_enabled") == "true") FridayWakeWordService.start(this@MainActivity)
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
