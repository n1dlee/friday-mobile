package com.friday.ai.ui.settings

import com.friday.ai.core.VoiceProfile
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.friday.ai.service.FridayNotificationListener
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.service.VoiceCalibrator
import com.friday.ai.service.VoskModelManager
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenDashboard: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel()
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val modelState by viewModel.modelDownloadState.collectAsStateWithLifecycle()
    val enrolment by viewModel.enrolment.collectAsStateWithLifecycle()
    val gmail by viewModel.gmail.collectAsStateWithLifecycle()

    // Google's consent screen. A cancelled screen comes back without data,
    // which the view model reports as "Вход отменён".
    val gmailConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onGmailConsent(result.data, completed = result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(gmail.consent) {
        gmail.consent?.let { gmailConsentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    val profile by viewModel.voiceProfile.collectAsStateWithLifecycle()
    val calibrationPhase by viewModel.calibrationPhase.collectAsStateWithLifecycle()
    val calibrationEnergy by viewModel.calibrationEnergy.collectAsStateWithLifecycle()
    val isCalibrated by viewModel.isCalibrated.collectAsStateWithLifecycle()
    val calibrationResult by viewModel.calibrationResult.collectAsStateWithLifecycle()
    val lazuriUrl by viewModel.lazuriUrl.collectAsStateWithLifecycle()
    val lazuriApiKey by viewModel.lazuriApiKey.collectAsStateWithLifecycle()
    val lazuriStatus by viewModel.lazuriStatus.collectAsStateWithLifecycle()
    val lazuriError by viewModel.lazuriError.collectAsStateWithLifecycle()
    val mapsProvider by viewModel.mapsProvider.collectAsStateWithLifecycle()
    val announceCalls by viewModel.announceCalls.collectAsStateWithLifecycle()
    val announceMessages by viewModel.announceMessages.collectAsStateWithLifecycle()
    val learnedCommands by viewModel.learnedCommands.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshLearned() }
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val availableModels by viewModel.models.collectAsStateWithLifecycle()

    var modelExpanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (Settings.canDrawOverlays(context)) {
                viewModel.onWakeWordToggle(true)
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
                scope.launch {
                    snackbarHost.showSnackbar("Grant 'Display over other apps', then toggle again")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.MonitorHeart, contentDescription = "Diagnostics")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHost) { data -> Snackbar(snackbarData = data) }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Groq API Configuration",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("Groq API Key") },
                placeholder = { Text("gsk_...") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )

                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    availableModels.forEach { modelName ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = {
                                viewModel.onModelChange(modelName)
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::saveSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaved) "Saved" else "Save Settings")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Voice Assistant",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Voice model status
            when (val state = modelState) {
                is VoskModelManager.DownloadState.NotDownloaded -> {
                    Text(
                        "Voice model not installed (~45 MB download)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.downloadVoiceModel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Voice Model")
                    }
                }
                is VoskModelManager.DownloadState.Downloading -> {
                    Text(
                        "Downloading voice model... ${state.progress}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is VoskModelManager.DownloadState.Extracting -> {
                    Text(
                        "Extracting voice model...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is VoskModelManager.DownloadState.Ready -> {
                    Text(
                        "Voice model installed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                is VoskModelManager.DownloadState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.downloadVoiceModel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retry Download")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Wake Word \"Friday\"",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Silently listens for \"Friday\" and shows assistant overlay",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = wakeWordEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (modelState !is VoskModelManager.DownloadState.Ready) {
                                scope.launch {
                                    snackbarHost.showSnackbar("Download voice model first")
                                }
                                return@Switch
                            }

                            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            val allGranted = perms.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }
                            if (!allGranted) {
                                permissionLauncher.launch(perms.toTypedArray())
                            } else if (!Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                scope.launch {
                                    snackbarHost.showSnackbar("Grant 'Display over other apps', then toggle again")
                                }
                            } else {
                                viewModel.onWakeWordToggle(true)
                                // Asked once, right when it starts mattering.
                                if (isBatteryRestricted(context)) requestBatteryExemption(context)
                            }
                        } else {
                            viewModel.onWakeWordToggle(false)
                        }
                    },
                    enabled = modelState is VoskModelManager.DownloadState.Ready || wakeWordEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                )
            }
            BackgroundWorkStatus(visible = wakeWordEnabled)

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Voice Calibration",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Calibrate Friday to your voice and environment for better wake word detection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (calibrationPhase != null) {
                val phaseText = when (calibrationPhase) {
                    VoiceCalibrator.Phase.MEASURING_SILENCE -> "Be quiet... measuring ambient noise"
                    VoiceCalibrator.Phase.WAITING_FOR_SPEECH -> "Now say something (e.g. \"Friday, open camera\")"
                    VoiceCalibrator.Phase.MEASURING_SPEECH -> "Keep talking... measuring your voice"
                    VoiceCalibrator.Phase.DONE -> "Done!"
                    null -> ""
                }
                Text(
                    phaseText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = {
                        when (calibrationPhase) {
                            VoiceCalibrator.Phase.MEASURING_SILENCE -> 0.25f
                            VoiceCalibrator.Phase.WAITING_FOR_SPEECH -> 0.5f
                            VoiceCalibrator.Phase.MEASURING_SPEECH -> 0.75f
                            VoiceCalibrator.Phase.DONE -> 1f
                            null -> 0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Energy: %.0f".format(calibrationEnergy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Button(
                    onClick = { viewModel.startCalibration() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isCalibrated) "Recalibrate Voice" else "Calibrate Voice")
                }
            }

            if (isCalibrated && calibrationPhase == null) {
                Spacer(modifier = Modifier.height(12.dp))
                val result = calibrationResult
                if (result != null) {
                    Text(
                        ("Ambient noise: %.0f  |  Your voice: %.0f\n" +
                            "Wake threshold: %.0f  |  Whisper threshold: %.0f").format(
                            result.ambientNoise, result.speechEnergy,
                            result.wakeWordThreshold, result.whisperThreshold
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Text(
                        "Voice calibrated",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Голосовой профиль",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            val current = profile
            Text(
                when {
                    current == null ->
                        "Пока Friday откликается на любой голос. Запишите голос — " +
                            "${VoiceProfile.ENROLMENT_PHRASES.size} коротких фраз, и она перестанет " +
                            "реагировать на чужих."
                    !current.coversCommands ->
                        "Профиль записан только на слове «Пятница», поэтому проверяется лишь " +
                            "пробуждение: команды после него он отвергал бы через раз. " +
                            "Перезапишите, чтобы проверялось и то, что вы говорите дальше."
                    else ->
                        "Проверяется и пробуждение, и команды. Записано фраз: ${current.sampleCount}, " +
                            "порог %.2f, похожесть записей %.2f.".format(current.threshold, current.cohesion)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            if (enrolment.total > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { enrolment.recorded.toFloat() / enrolment.total },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${enrolment.recorded} / ${enrolment.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            enrolment.message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enrolment.recording) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.enrollVoice()
                        else permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                    enabled = !enrolment.recording,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (profile == null) "Записать голос" else "Перезаписать")
                }
                if (profile != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.clearVoiceProfile() },
                        enabled = !enrolment.recording
                    ) {
                        Text("Сбросить")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Записывайте там же, где обычно зовёте Friday — профиль, снятый в тишине, " +
                    "потом не узнаёт вас в машине.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Почта Gmail",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                gmail.account?.let {
                    "Подключено: $it. Friday проверяет входящие, читает письма и отвечает — " +
                        "отправляет только после вашего «да»."
                } ?: "Не подключено. После подключения можно спросить «есть новые письма?», " +
                    "«прочитай письмо от Ивана», «ответь Ивану: буду в пять».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            gmail.message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = { viewModel.connectGmail() },
                    enabled = !gmail.busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (gmail.account == null) "Подключить Gmail" else "Переподключить")
                }
                if (gmail.account != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.disconnectGmail() }, enabled = !gmail.busy) {
                        Text("Отключить")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onOpenDashboard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Lazuri dashboard")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "A map of what Friday knows about you: facts it has learned, " +
                    "and the conversations they came from.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Lazuri (shared memory across devices)",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Connect to your Lazuri Core server so facts and conversations sync " +
                    "with your other devices (PC, future desktop assistant).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = lazuriUrl,
                onValueChange = viewModel::onLazuriUrlChange,
                label = { Text("Server address") },
                placeholder = { Text("http://100.x.y.z:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = lazuriStatus != SettingsViewModel.LazuriStatus.CONNECTED
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = lazuriApiKey,
                onValueChange = viewModel::onLazuriApiKeyChange,
                label = { Text("X-API-Key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = lazuriStatus != SettingsViewModel.LazuriStatus.CONNECTED
            )
            Spacer(modifier = Modifier.height(16.dp))

            when (lazuriStatus) {
                SettingsViewModel.LazuriStatus.CONNECTED -> {
                    Text(
                        "Connected to Lazuri",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectLazuri() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect")
                    }
                }
                SettingsViewModel.LazuriStatus.CONNECTING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Button(
                        onClick = { viewModel.connectToLazuri() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect")
                    }
                    if (lazuriStatus == SettingsViewModel.LazuriStatus.ERROR && lazuriError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            lazuriError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Learned commands",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Phrases the AI worked out once and Friday now carries out directly, without the AI. " +
                    "Say \"нет, не то\" right after a wrong one and it is forgotten; or remove it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (learnedCommands.isEmpty()) {
                Text(
                    "Nothing learned yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            learnedCommands.forEach { (phrase, action) ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("«$phrase»", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            action,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = { viewModel.forgetLearned(phrase) }) {
                        Icon(Icons.Default.Close, contentDescription = "Forget «$phrase»")
                    }
                }
            }
            if (learnedCommands.isNotEmpty()) {
                OutlinedButton(onClick = { viewModel.forgetAllLearned() }) { Text("Forget all") }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Calls & messages",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Friday reads WhatsApp, Telegram and SMS from their notifications and can answer them " +
                    "(\"прочитай сообщения\", \"ответь маме, что еду\"). Needs notification access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Both need the notification listener; turning one on without it
            // goes straight to the screen that grants it.
            val needsAccess = { on: Boolean ->
                if (on && !FridayNotificationListener.isEnabled(context)) {
                    FridayNotificationListener.openSettings(context)
                }
            }
            ToggleRow(
                title = "Announce calls",
                subtitle = "Say who is calling — phone, WhatsApp or Telegram",
                checked = announceCalls,
                onChange = { needsAccess(it); viewModel.onAnnounceCallsChange(it) }
            )
            ToggleRow(
                title = "Read new messages aloud",
                subtitle = "Say each new personal message, then listen for a reply",
                checked = announceMessages,
                onChange = { needsAccess(it); viewModel.onAnnounceMessagesChange(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Maps",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Which app to use for \"find nearest coffee shop / bank / ...\". " +
                    "Phone default opens whatever maps app you set in Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                val providers = listOf("auto" to "Phone default", "google" to "Google", "yandex" to "Yandex")
                providers.forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { viewModel.onMapsProviderChange(value) },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        colors = if (mapsProvider == value) {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                        } else {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
