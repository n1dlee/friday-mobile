package com.friday.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.friday.ai.core.modes.Days
import com.friday.ai.core.modes.Schedule
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.ChatUiState
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.ui.chat.ChatLayout
import com.friday.ai.ui.theme.ArcAmber
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.FridayTheme
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudSwitchRow
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.StatusDot
import java.io.File
import java.time.LocalTime
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNG so the design can be looked at without a
 * phone. Off by default (slow, and pixels aren't assertions):
 * `./gradlew testDebugUnitTest --tests "*HudScreenshots*" -Pscreenshots`,
 * output in `build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class HudScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(System.getProperty("screenshots") == "true")

    @Test
    fun chatStandby() = shoot("chat_standby") { Chat(ChatUiState()) }

    @Test
    fun chatListening() = shoot("chat_listening") { Chat(ChatUiState(isVoiceListening = true)) }

    @Test
    fun chatConversation() = shoot("chat_conversation") {
        Chat(
            ChatUiState(
                currentMode = AssistantMode.ANALYTICAL,
                isLoading = true,
                messages = listOf(
                    Message(content = "Какая погода завтра?", role = MessageRole.USER, timestamp = at(9, 41)),
                    Message(
                        content = "Завтра в Нью-Йорке +18°, переменная облачность, после обеда возможен дождь. " +
                            "Зонт лучше взять.",
                        role = MessageRole.ASSISTANT, timestamp = at(9, 41)
                    ),
                    Message(content = "Включи музыку в Spotify", role = MessageRole.USER, timestamp = at(9, 42)),
                    Message(content = "Играет в Spotify", role = MessageRole.ASSISTANT, timestamp = at(9, 42)),
                    Message(content = "Напиши маме, что буду в семь", role = MessageRole.USER, timestamp = at(9, 43)),
                    Message(
                        content = "Пишу маме в WhatsApp: «Буду в семь». Отправить?",
                        role = MessageRole.ASSISTANT, timestamp = at(9, 43), isStreaming = true
                    )
                )
            ),
            input = ""
        )
    }

    @Test
    fun chatError() = shoot("chat_error") {
        Chat(ChatUiState(error = "Groq не отвечает: нет сети"), input = "Поставь будильник на 7")
    }

    @Test
    fun panels() = shoot("panels") {
        HudBackground {
            Column(Modifier.fillMaxSize()) {
                HudTopBar("СИСТЕМЫ", status = HudStatus("Настройки"))
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HudPanel("Интеллект · Groq", index = 1) {
                        HudReadout("Статус", "КЛЮЧ ЗАДАН", valueColor = ArcCyan)
                        HudReadout("Модель", "openai/gpt-oss-120b")
                        HudButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
                    }
                    HudPanel("Голос", index = 2) {
                        HudReadout("Модель слова", "ГОТОВА", valueColor = ArcCyan)
                        HudSwitchRow("Слово «Пятница»", "Тихо слушает имя и открывает панель", true, {})
                        HudSwitchRow("Читать новые сообщения", "Зачитывает и ждёт ответа", false, {})
                    }
                    HudPanel("Это устройство", index = 5, accent = ArcAmber) {
                        DotRow(ArcCyan, "Микрофон · OK")
                        DotRow(ArcAmber, "Доступ к уведомлениям · СБОЙ")
                        DotRow(ErrorColor, "Ключ Groq · нет")
                        HudNote("Выключен: нет чтения и ответов в чатах, управления музыкой, объявления звонков")
                        HudOutlinedButton(onClick = {}) { Text("Исправить") }
                    }
                }
            }
        }
    }

    @Test
    fun modes() = shoot("modes") {
        fun e(tool: String, json: String) = com.friday.ai.agent.ActionEnvelope(
            tool, kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        )
        val rest = com.friday.ai.core.modes.Mode(
            id = "1", name = "отдыха", aliases = emptyList(),
            description = "полный беззвучный, не беспокоить и яркость на минимум",
            steps = listOf(
                e("phone_control", """{"target":"ringer","state":"silent"}"""),
                e("phone_control", """{"target":"dnd","state":"on"}"""),
                e("phone_control", """{"target":"brightness","level":5}""")
            ),
            undo = emptyList(), createdAt = 0, lastRunAt = 0, runCount = 4
        )
        val sad = rest.copy(
            id = "2", name = "грусти", description = "включается Spotify с грустными песнями",
            steps = listOf(e("play", """{"query":"грустные песни","kind":"music","app":"Spotify"}""")),
            undo = null, runCount = 1
        )
        com.friday.ai.ui.modes.ModesLayout(
            listOf(rest, sad),
            status = "Режим отдыха. Полностью беззвучно. «Не беспокоить» включён. Яркость 5%.",
            busy = null, onNavigateBack = {}, onRun = {}, onStop = {}, onDelete = {},
            schedules = mapOf(
                "1" to listOf(
                    Schedule("a", "1", false, LocalTime.of(23, 0), Days.ALL),
                    Schedule("b", "1", true, LocalTime.of(7, 0), Days.WEEKDAYS)
                )
            )
        )
    }

    @Test
    fun modesEmpty() = shoot("modes_empty") {
        com.friday.ai.ui.modes.ModesLayout(emptyList(), null, null, {}, {}, {}, {})
    }

    @Composable
    private fun DotRow(color: androidx.compose.ui.graphics.Color, text: String) {
        Row { StatusDot(color, live = false); Text(text, color = color) }
    }

    @Composable
    private fun Chat(state: ChatUiState, input: String = "") {
        ChatLayout(
            state = state,
            inputText = input,
            listState = rememberLazyListState(),
            onMenu = {}, onSettings = {}, onModes = {}, onMode = {}, onInput = {}, onSend = {},
            onMic = {}, onSuggestion = {}, onDismissError = {}
        )
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        // Infinite animations never go idle: drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { FridayTheme(content) }
        compose.mainClock.advanceTimeBy(1_200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // Drawn straight into a bitmap: captureToImage waits for a frame the paused looper never schedules.
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun at(hour: Int, minute: Int): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
    }.timeInMillis
}
