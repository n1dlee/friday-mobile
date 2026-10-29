package com.friday.ai.ui.diagnostics

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.friday.ai.core.voice.PlaybackEvent
import com.friday.ai.core.voice.PlaybackPhase
import com.friday.ai.core.voice.VoiceDiagnosticSnapshot
import com.friday.ai.core.voice.VoiceEngine
import com.friday.ai.core.voice.VoiceStage
import com.friday.ai.ui.theme.FridayTheme
import com.friday.ai.ui.theme.HudBackground
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class VoiceDiagnosticsPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `queued audio is labelled without claiming playback even at large font size`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                FridayTheme {
                    HudBackground {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            VoiceDiagnosticsPanel(VoiceDiagnosticSnapshot(
                                stage = VoiceStage.THINKING,
                                playback = PlaybackEvent(PlaybackPhase.QUEUED, VoiceEngine.EDGE)
                            ))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Подготовка ответа").assertIsDisplayed()
        compose.onNodeWithText("Воспроизведение: в очереди", substring = true).assertIsDisplayed()
        if (System.getProperty("screenshots") == "true") {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val folder = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
            File(folder, "voice_diagnostics_large_font.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
