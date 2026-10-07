package com.friday.ai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.ChatUiState
import com.friday.ai.ui.chat.components.ChatInputBar
import com.friday.ai.ui.chat.components.MessageBubble
import com.friday.ai.ui.chat.components.ModeSelector
import com.friday.ai.ui.chat.components.ReactorHero
import com.friday.ai.ui.chat.components.SessionDrawer
import com.friday.ai.ui.chat.components.label
import com.friday.ai.ui.overlay.ArcReactorView
import com.friday.ai.ui.theme.ArcAmber
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.BackgroundDark
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.HudScanLine
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.OnSurfaceMuted
import com.friday.ai.ui.theme.hudFrame
import java.util.Calendar
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToModes: () -> Unit = {},
    onNavigateToNotebook: () -> Unit = {},
    viewModel: ChatViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onMicClick()
    }

    // Asked once on first open. Contacts turn "позвони папе" into a number;
    // calendar access lets a reminder be saved without the user having to
    // confirm it in the calendar app. Nothing here leaves the device.
    val assistantPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val needed = listOfNotNull(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // Bluetooth device names, for modes started by the car or headphones.
            Manifest.permission.BLUETOOTH_CONNECT.takeIf {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            }
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) assistantPermissionsLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    val onMic = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) viewModel.onMicClick()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val ask = { text: String ->
        viewModel.onInputChange(text)
        viewModel.onSend()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = BackgroundDark) {
                SessionDrawer(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    onNewChat = {
                        viewModel.onNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpenSession = { id ->
                        viewModel.onOpenSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteSession = viewModel::onDeleteSession
                )
            }
        }
    ) {
        ChatLayout(
            state = uiState,
            inputText = inputText,
            listState = listState,
            onMenu = { scope.launch { drawerState.open() } },
            onSettings = onNavigateToSettings,
            onModes = onNavigateToModes,
            onNotebook = onNavigateToNotebook,
            onMode = viewModel::onModeChange,
            onInput = viewModel::onInputChange,
            onSend = viewModel::onSend,
            onMic = onMic,
            onSuggestion = ask,
            onDismissError = viewModel::onDismissError
        )
    }
}

/** The chat itself, without its view model: what the screenshot test renders. */
@Composable
internal fun ChatLayout(
    state: ChatUiState,
    inputText: String,
    listState: LazyListState,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onModes: () -> Unit,
    onNotebook: () -> Unit = {},
    onMode: (AssistantMode) -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onSuggestion: (String) -> Unit,
    onDismissError: () -> Unit
) {
    HudBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The bottom is the *larger* of the keyboard and the
                // navigation bar, not their sum — adding both left a dead
                // gap above the keyboard.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            HudTopBar(
                title = "F.R.I.D.A.Y.",
                status = status(state),
                // Two icons each side, so the centred title never runs into them.
                navigation = {
                    Row {
                        IconButton(onClick = onMenu) {
                            Icon(Icons.Filled.Menu, contentDescription = "Журнал разговоров", tint = OnBackground)
                        }
                        IconButton(onClick = onNotebook) {
                            Icon(Icons.Filled.Draw, contentDescription = "Блокнот", tint = ArcCyan)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onModes) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Режимы", tint = ArcCyan)
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = OnBackground)
                    }
                }
            )

            AnimatedVisibility(visible = state.isLoading) { HudScanLine() }

            ModeSelector(
                currentMode = state.currentMode,
                onModeSelected = onMode,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Box(Modifier.weight(1f)) {
                if (state.messages.isEmpty()) {
                    Standby(state, onSuggestion = onSuggestion)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                    }
                }
            }

            state.error?.let { error -> ErrorStrip(error, onDismiss = onDismissError) }

            ChatInputBar(
                text = inputText,
                onTextChange = onInput,
                onSend = onSend,
                onMicClick = onMic,
                isLoading = state.isLoading,
                isListening = state.isVoiceListening
            )
        }
    }
}

private fun status(state: ChatUiState): HudStatus = when {
    state.isVoiceListening -> HudStatus("Слушаю", ArcCyan, live = true)
    state.isLoading -> HudStatus("Обрабатываю", ArcAmber, live = true)
    state.currentMode != AssistantMode.DEFAULT -> HudStatus("На связи · ${state.currentMode.label}")
    else -> HudStatus("На связи")
}

/** Nothing said yet: the reactor, a greeting, and a few things to try. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Standby(state: ChatUiState, onSuggestion: (String) -> Unit) {
    val greeting = remember { greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val mood = when {
        state.isVoiceListening -> ArcReactorView.Mood.LISTENING
        state.isLoading -> ArcReactorView.Mood.THINKING
        else -> ArcReactorView.Mood.IDLE
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ReactorHero(mood)
        Spacer(Modifier.height(20.dp))
        Text(
            greeting,
            style = MaterialTheme.typography.headlineMedium,
            color = OnBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Скажите «Пятница» — или напишите ниже",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SUGGESTIONS.forEach { Suggestion(it, onClick = { onSuggestion(it) }) }
        }
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = OnBackground,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(ArcCyan.copy(alpha = 0.05f))
            .border(1.dp, ArcCyan.copy(alpha = 0.2f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun ErrorStrip(error: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .hudFrame(accent = ErrorColor)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text("СБОЙ", style = HudLabelStyle, color = ErrorColor)
            Text(error, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
        }
        TextButton(onClick = onDismiss) { Text("Скрыть", color = ErrorColor) }
    }
}

private val SUGGESTIONS = listOf(
    "Какая погода сегодня?",
    "Прочитай новые сообщения",
    "Включи музыку",
    "Поставь будильник на 7:00",
    "Что нового в мире?"
)

private fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Доброе утро, сэр"
    in 12..17 -> "Добрый день, сэр"
    in 18..22 -> "Добрый вечер, сэр"
    else -> "Доброй ночи, сэр"
}
