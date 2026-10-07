package com.friday.ai.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.VoiceProfile
import com.friday.ai.service.FridayNotificationListener
import com.friday.ai.service.VoiceCalibrator
import com.friday.ai.service.VoskModelManager
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudSwitchRow
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.hudFieldColors
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/* The voice panels of Settings: wake word, calibration and the owner's voice profile. */

@Composable
internal fun VoicePanel(viewModel: SettingsViewModel, say: (String) -> Unit) {
    val context = LocalContext.current
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val modelState by viewModel.modelDownloadState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            if (Settings.canDrawOverlays(context)) {
                viewModel.onWakeWordToggle(true)
            } else {
                openOverlaySettings(context)
                say(OVERLAY_HINT)
            }
        }
    }

    HudPanel("Голос", index = 2) {
        VoiceModelState(modelState, onDownload = viewModel::downloadVoiceModel)
        HudSwitchRow(
            title = "Слово «Пятница»",
            subtitle = "Тихо слушает имя и открывает панель поверх любого приложения",
            checked = wakeWordEnabled,
            enabled = modelState is VoskModelManager.DownloadState.Ready || wakeWordEnabled,
            onChange = { enabled ->
                when {
                    !enabled -> viewModel.onWakeWordToggle(false)
                    modelState !is VoskModelManager.DownloadState.Ready -> say("Сначала скачайте голосовую модель")
                    !wakePermissionsGranted(context) -> permissionLauncher.launch(wakePermissions())
                    !Settings.canDrawOverlays(context) -> {
                        openOverlaySettings(context)
                        say(OVERLAY_HINT)
                    }
                    else -> {
                        viewModel.onWakeWordToggle(true)
                        // Asked once, right when it starts mattering.
                        if (isBatteryRestricted(context)) requestBatteryExemption(context)
                    }
                }
            }
        )
        BackgroundWorkStatus(visible = wakeWordEnabled)
    }
}

@Composable
private fun VoiceModelState(state: VoskModelManager.DownloadState, onDownload: () -> Unit) {
    when (state) {
        is VoskModelManager.DownloadState.NotDownloaded -> {
            HudReadout("Модель слова", "НЕ УСТАНОВЛЕНА", valueColor = ErrorColor)
            HudOutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Скачать голосовую модель (~45 МБ)")
            }
        }
        is VoskModelManager.DownloadState.Downloading -> {
            HudReadout("Модель слова", "ЗАГРУЗКА ${state.progress}%")
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is VoskModelManager.DownloadState.Extracting -> {
            HudReadout("Модель слова", "РАСПАКОВКА")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is VoskModelManager.DownloadState.Ready -> HudReadout("Модель слова", "ГОТОВА", valueColor = ArcCyan)
        is VoskModelManager.DownloadState.Error -> {
            HudReadout("Модель слова", "ОШИБКА", valueColor = ErrorColor)
            HudNote(state.message)
            HudOutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Повторить загрузку") }
        }
    }
}

@Composable
internal fun CalibrationPanel(viewModel: SettingsViewModel) {
    val phase by viewModel.calibrationPhase.collectAsStateWithLifecycle()
    val energy by viewModel.calibrationEnergy.collectAsStateWithLifecycle()
    val isCalibrated by viewModel.isCalibrated.collectAsStateWithLifecycle()
    val result by viewModel.calibrationResult.collectAsStateWithLifecycle()

    HudPanel("Калибровка", index = 3) {
        HudNote("Подстраивает Пятницу под ваш голос и шум вокруг, чтобы слово срабатывало надёжно.")
        val current = phase
        if (current != null) {
            Text(phaseText(current), style = MaterialTheme.typography.titleMedium, color = ArcCyan)
            LinearProgressIndicator(progress = { phaseProgress(current) }, modifier = Modifier.fillMaxWidth())
            HudReadout("Уровень", "%.0f".format(energy))
        } else {
            HudButton(onClick = viewModel::startCalibration, modifier = Modifier.fillMaxWidth()) {
                Text(if (isCalibrated) "Откалибровать заново" else "Откалибровать")
            }
        }
        val r = result
        if (isCalibrated && current == null && r != null) {
            HudReadout("Фоновый шум", "%.0f".format(r.ambientNoise))
            HudReadout("Ваш голос", "%.0f".format(r.speechEnergy))
            HudReadout("Порог слова", "%.0f".format(r.wakeWordThreshold))
            HudReadout("Порог речи", "%.0f".format(r.whisperThreshold))
        } else if (isCalibrated && current == null) {
            HudReadout("Статус", "ОТКАЛИБРОВАНО", valueColor = ArcCyan)
        }
    }
}

private fun phaseText(phase: VoiceCalibrator.Phase): String = when (phase) {
    VoiceCalibrator.Phase.MEASURING_SILENCE -> "Тишина… измеряю фоновый шум"
    VoiceCalibrator.Phase.WAITING_FOR_SPEECH -> "Теперь скажите что-нибудь, например «Пятница, открой камеру»"
    VoiceCalibrator.Phase.MEASURING_SPEECH -> "Продолжайте… измеряю голос"
    VoiceCalibrator.Phase.DONE -> "Готово"
}

private fun phaseProgress(phase: VoiceCalibrator.Phase): Float = when (phase) {
    VoiceCalibrator.Phase.MEASURING_SILENCE -> 0.25f
    VoiceCalibrator.Phase.WAITING_FOR_SPEECH -> 0.5f
    VoiceCalibrator.Phase.MEASURING_SPEECH -> 0.75f
    VoiceCalibrator.Phase.DONE -> 1f
}

@Composable
internal fun VoiceProfilePanel(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val profile by viewModel.voiceProfile.collectAsStateWithLifecycle()
    val enrolment by viewModel.enrolment.collectAsStateWithLifecycle()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enrollVoice()
    }

    HudPanel("Голосовой профиль", index = 4) {
        val current = profile
        HudReadout(
            "Доступ",
            when {
                current == null -> "ЛЮБОЙ ГОЛОС"
                !current.coversCommands -> "ТОЛЬКО СЛОВО"
                else -> "ТОЛЬКО ВЫ"
            },
            valueColor = if (current?.coversCommands == true) ArcCyan else ErrorColor
        )
        HudNote(profileText(current))
        if (enrolment.total > 0) {
            LinearProgressIndicator(
                progress = { enrolment.recorded.toFloat() / enrolment.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${enrolment.recorded} / ${enrolment.total}", style = HudLabelStyle, color = ArcCyan)
        }
        enrolment.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enrolment.recording) ArcCyan else OnBackground
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.enrollVoice() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !enrolment.recording,
                modifier = Modifier.weight(1f)
            ) { Text(if (current == null) "Записать голос" else "Перезаписать") }
            if (current != null) {
                HudOutlinedButton(onClick = viewModel::clearVoiceProfile, enabled = !enrolment.recording) {
                    Text("Сбросить")
                }
            }
        }
        HudNote(
            "Записывайте там же, где обычно зовёте Пятницу: профиль, снятый в тишине, потом не узнаёт вас в машине."
        )
    }
}

private fun profileText(profile: VoiceProfile.Profile?): String = when {
    profile == null ->
        "Пока Пятница откликается на любой голос. Запишите голос — " +
            "${VoiceProfile.ENROLMENT_PHRASES.size} коротких фраз, и она перестанет реагировать на чужих."
    !profile.coversCommands ->
        "Профиль записан только на слове «Пятница», поэтому проверяется лишь пробуждение. " +
            "Перезапишите, чтобы проверялось и то, что вы говорите дальше."
    else ->
        "Проверяется и пробуждение, и команды. Фраз: ${profile.sampleCount}, " +
            "порог %.2f, похожесть записей %.2f.".format(profile.threshold, profile.cohesion)
}

private const val OVERLAY_HINT = "Разрешите «Поверх других приложений» и включите снова"

private fun wakePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun wakePermissionsGranted(context: Context): Boolean = wakePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    )
}
