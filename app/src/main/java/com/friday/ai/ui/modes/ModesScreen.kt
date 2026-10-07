package com.friday.ai.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.modes.Mode
import com.friday.ai.core.modes.ModeSteps
import com.friday.ai.core.modes.Schedule
import com.friday.ai.core.modes.ScheduleMath
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ArcCyanDim
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.OnSurfaceMuted
import com.friday.ai.ui.theme.StatusDot
import com.friday.ai.ui.theme.hudFrame
import org.koin.androidx.compose.koinViewModel

/** The owner's modes: what each does, whether it's on, and buttons for what a phrase would do. */
@Composable
fun ModesScreen(onNavigateBack: () -> Unit, viewModel: ModesViewModel = koinViewModel()) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    ModesLayout(
        modes, status, busy, onNavigateBack, viewModel::run, viewModel::stop, viewModel::delete,
        schedules = schedules, onUnschedule = viewModel::unschedule
    )
}

/** Without the view model: what the screenshot test renders. */
@Composable
internal fun ModesLayout(
    modes: List<Mode>?,
    status: String?,
    busy: String?,
    onNavigateBack: () -> Unit,
    onRun: (Mode) -> Unit,
    onStop: (Mode) -> Unit,
    onDelete: (Mode) -> Unit,
    schedules: Map<String, List<Schedule>> = emptyMap(),
    onUnschedule: (Schedule) -> Unit = {}
) {
    var deleting by remember { mutableStateOf<Mode?>(null) }
    HudBackground {
        Column(Modifier.fillMaxSize()) {
            HudTopBar(
                title = "РЕЖИМЫ",
                status = when {
                    modes == null -> HudStatus("Загрузка")
                    modes.isEmpty() -> HudStatus("Пока нет")
                    else -> HudStatus("Сохранено: ${modes.size} · включено: ${modes.count { it.active }}")
                },
                navigation = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = OnBackground)
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                status?.let { item { StatusStrip(it) } }
                if (modes != null && modes.isEmpty()) item { HowTo() }
                items(modes.orEmpty(), key = { it.id }) { mode ->
                    ModePanel(
                        mode, index = modes.orEmpty().indexOf(mode) + 1, busy = busy == mode.id,
                        onRun = { onRun(mode) }, onStop = { onStop(mode) }, onDelete = { deleting = mode },
                        schedules = schedules[mode.id].orEmpty(), onUnschedule = onUnschedule
                    )
                }
                if (!modes.isNullOrEmpty()) {
                    item { HudNote("Новый режим — голосом: «создай режим … : что в нём делать».") }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    deleting?.let { mode ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить режим ${mode.name}?") },
            text = {
                Text(
                    if (mode.active) "Он сейчас включён: настройки, которые он менял, останутся как есть."
                    else "Его шаги будут забыты."
                )
            },
            confirmButton = { TextButton(onClick = { onDelete(mode); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ModePanel(
    mode: Mode,
    index: Int,
    busy: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    schedules: List<Schedule> = emptyList(),
    onUnschedule: (Schedule) -> Unit = {}
) {
    HudPanel("Режим ${mode.name}", index = index, accent = if (mode.active) ArcCyan else ArcCyanDim) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (mode.active) ArcCyan else OnSurfaceMuted, live = mode.active)
            Spacer(Modifier.width(6.dp))
            Text(
                if (mode.active) "ВКЛЮЧЁН" else "ВЫКЛЮЧЕН",
                style = HudLabelStyle,
                color = if (mode.active) ArcCyan else OnSurfaceMuted
            )
        }
        Text(
            "«${mode.description}»",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = OnSurfaceMuted
        )
        mode.steps.forEachIndexed { i, step ->
            Row {
                Text("%02d".format(i + 1), style = HudLabelStyle, color = ArcCyan.copy(alpha = 0.6f))
                Spacer(Modifier.width(10.dp))
                Text(
                    ModeSteps.describe(step, russian = true),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
            }
        }
        if (schedules.isNotEmpty()) {
            // One group: the panel's spacing between rows would leave gaps around the 48dp remove buttons.
            Column { schedules.sortedBy { it.exit }.forEach { s -> ScheduleRow(s, onRemove = { onUnschedule(s) }) } }
        }
        HudReadout("Запусков", mode.runCount.toString())
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode.active) {
                HudButton(onClick = onStop, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Выключить") }
            } else {
                HudButton(onClick = onRun, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Включить") }
            }
            HudOutlinedButton(onClick = onDelete, enabled = !busy) { Text("Удалить") }
        }
    }
}

@Composable
private fun ScheduleRow(s: Schedule, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Schedule, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            (if (s.exit) "Выключается " else "Включается ") + ScheduleMath.describe(s.days, s.time, russian = true),
            style = MaterialTheme.typography.bodyMedium,
            color = ArcCyan,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Убрать расписание", tint = OnSurfaceMuted)
        }
    }
}

@Composable
private fun StatusStrip(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudFrame()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("ПЯТНИЦА", style = HudLabelStyle, color = ArcCyan)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
    }
}

@Composable
private fun HowTo() {
    HudPanel("Как создать режим") {
        HudNote(
            "Режим — это несколько действий под одним именем. Создайте его голосом, " +
                "Пятница разберёт описание и прочитает шаги вслух."
        )
        Example("«Создай режим грусти. Это режим, где включается Spotify с грустными песнями.»")
        Example("«Создай режим отдыха: полный беззвучный, «Не беспокоить», яркость на минимум.»")
        HudNote("Потом: «режим отдыха» — включить, «выключи режим отдыха» — вернуть всё как было.")
        HudNote("По расписанию: «включай режим отдыха каждый день в 23:00», «по будням в 7 выключай режим отдыха».")
    }
}

@Composable
private fun Example(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = ArcCyan)
}
