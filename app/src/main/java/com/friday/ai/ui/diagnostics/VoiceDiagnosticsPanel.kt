package com.friday.ai.ui.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.friday.ai.core.voice.PlaybackPhase
import com.friday.ai.core.voice.VoiceDiagnosticSnapshot
import com.friday.ai.core.voice.VoiceStage
import com.friday.ai.core.voice.VoiceEngine
import com.friday.ai.core.voice.OwnerCheck
import com.friday.ai.core.voice.VoiceIssue
import com.friday.ai.ui.theme.ArcAmber
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.OnSurfaceMuted

@Composable
fun VoiceDiagnosticsPanel(snapshot: VoiceDiagnosticSnapshot) {
    HudPanel("Голос · текущий процесс", accent = if (snapshot.issue == null) ArcCyan else ArcAmber) {
        Text(stageLabel(snapshot.stage), color = OnBackground, style = MaterialTheme.typography.titleMedium)
        Text(
            "Распознавание: ${engineLabel(snapshot.asrEngine)}\n" +
                "Проверка голоса: ${ownerLabel(snapshot.owner)}\n" +
                "Воспроизведение: ${playbackLabel(snapshot.playback?.phase)}\n" +
                "Движок речи: ${engineLabel(snapshot.playback?.engine)}",
            color = OnBackground, style = MaterialTheme.typography.bodyMedium
        )
        snapshot.issue?.let {
            Text("Последняя причина: ${issueLabel(it)}", color = ArcAmber, style = MaterialTheme.typography.bodyMedium)
        }
        val counts = snapshot.counts
        Text(
            "Принято: ${counts.accepted} · отклонено: ${counts.rejected}\n" +
                "Отменено: ${counts.cancelled} · ошибок: ${counts.failures}\n" +
                "Резервный путь: ${counts.fallbacks} · перезапуски: ${counts.restarts}",
            color = OnSurfaceMuted, style = MaterialTheme.typography.bodySmall
        )
        snapshot.durationsMs.filterKeys { it != VoiceStage.IDLE }.forEach { (stage, ms) ->
            Text("${stageLabel(stage)}: $ms мс", color = OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text("Без аудио и текста разговоров. Данные сбрасываются при перезапуске процесса.",
            color = OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun engineLabel(engine: VoiceEngine?): String = when (engine) {
    null -> "ещё нет результата"
    VoiceEngine.EDGE -> "Edge"
    VoiceEngine.SYSTEM -> "системный TTS"
    VoiceEngine.CLOUD_ASR -> "облачное"
    VoiceEngine.LOCAL_ASR -> "локальное"
}

private fun ownerLabel(check: OwnerCheck): String = when (check) {
    OwnerCheck.NOT_CHECKED -> "не выполнялась"
    OwnerCheck.CHECKING -> "выполняется"
    OwnerCheck.ACCEPTED -> "голос принят"
    OwnerCheck.REJECTED -> "голос отклонён"
}

private fun issueLabel(issue: VoiceIssue): String = when (issue) {
    VoiceIssue.NO_SPEECH -> "не обнаружена речь"
    VoiceIssue.OWNER_REJECTED -> "голос отклонён"
    VoiceIssue.NETWORK -> "нет связи с облаком"
    VoiceIssue.TIMEOUT -> "истекло время ожидания"
    VoiceIssue.SERVICE -> "облачный сервис недоступен"
    VoiceIssue.AUTH -> "ключ отклонён"
    VoiceIssue.CONFIG, VoiceIssue.NO_KEY -> "нужна настройка распознавания"
    VoiceIssue.CAPTURE -> "ошибка записи"
    VoiceIssue.PLAYBACK -> "ошибка воспроизведения"
}

private fun stageLabel(stage: VoiceStage): String = when (stage) {
    VoiceStage.IDLE -> "Нет активного обмена"
    VoiceStage.GREETING -> "Вызов принят"
    VoiceStage.LISTENING -> "Запись запроса"
    VoiceStage.TRANSCRIBING -> "Распознавание"
    VoiceStage.THINKING -> "Подготовка ответа"
    VoiceStage.PLAYBACK -> "Воспроизведение ответа"
    VoiceStage.FINISHED -> "Завершено"
    VoiceStage.CANCELLED -> "Отменено"
    VoiceStage.FAILED -> "Ошибка"
}

private fun playbackLabel(phase: PlaybackPhase?): String = when (phase) {
    null -> "не запускалось"
    PlaybackPhase.QUEUED -> "в очереди"
    PlaybackPhase.SYNTHESIZING -> "синтезируется"
    PlaybackPhase.READY -> "аудио готово"
    PlaybackPhase.PLAYING -> "запуск подтверждён движком"
    PlaybackPhase.WAITING -> "ожидает следующую фразу"
    PlaybackPhase.FINISHED -> "завершено"
    PlaybackPhase.CANCELLED -> "отменено"
    PlaybackPhase.FAILED -> "ошибка воспроизведения"
}
