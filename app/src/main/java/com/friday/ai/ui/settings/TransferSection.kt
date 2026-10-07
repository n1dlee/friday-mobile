package com.friday.ai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.data.backup.SettingsArchive
import com.friday.ai.data.backup.SettingsTransfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/** "Перенос на другой телефон": export everything to one encrypted file, or import one. */
@Composable
fun TransferSection(viewModel: TransferViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Which file the passphrase dialog is for, and whether it's an export (asks twice).
    var asking by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { asking = it to true } }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { asking = it to false }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Ключ Groq, голосовой профиль, память, выученные команды, история и настройки — в одном " +
                "файле, зашифрованном вашим паролем. Сохраните его и откройте на новом телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { createFile.launch("friday-${today()}.friday") },
                enabled = state !is TransferViewModel.State.Working,
                modifier = Modifier.weight(1f)
            ) { Text("Экспорт") }
            OutlinedButton(
                onClick = { openFile.launch(arrayOf("*/*")) },
                enabled = state !is TransferViewModel.State.Working,
                modifier = Modifier.weight(1f)
            ) { Text("Импорт") }
        }
        when (val s = state) {
            TransferViewModel.State.Working -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is TransferViewModel.State.Done ->
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            is TransferViewModel.State.Failed ->
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }

    asking?.let { (uri, export) ->
        PassphraseDialog(
            export = export,
            onDismiss = { asking = null },
            onConfirm = { pass ->
                asking = null
                if (export) viewModel.export(uri, pass) else viewModel.open(uri, pass)
            }
        )
    }
    (state as? TransferViewModel.State.Review)?.let { review ->
        ReviewDialog(review.summary, onConfirm = viewModel::confirmImport, onDismiss = viewModel::dismiss)
    }
}

@Composable
private fun PassphraseDialog(export: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val longEnough = pass.length >= SettingsArchive.MIN_PASSPHRASE
    val ok = longEnough && (!export || pass == repeat)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (export) "Пароль для файла" else "Пароль от файла") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export) {
                    Text(
                        "Без него файл не открыть — ни вам, ни кому-то ещё. Восстановить пароль нельзя.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (export && pass.isNotEmpty() && !longEnough) {
                        { Text("Не меньше ${SettingsArchive.MIN_PASSPHRASE} символов") }
                    } else null
                )
                if (export) {
                    OutlinedTextField(
                        value = repeat, onValueChange = { repeat = it }, singleLine = true,
                        label = { Text("Ещё раз") }, visualTransformation = PasswordVisualTransformation(),
                        isError = repeat.isNotEmpty() && repeat != pass
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pass.toCharArray()) }, enabled = ok) {
                Text(if (export) "Сохранить" else "Открыть")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ReviewDialog(summary: SettingsTransfer.Summary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заменить всё на этом телефоне?") },
        text = {
            Column {
                Text("Файл от ${date(summary.createdAt)}, Пятница ${summary.appVersion}:")
                Spacer(Modifier.height(8.dp))
                Text("• ключ Groq: ${if (summary.hasApiKey) "есть" else "нет"}")
                Text("• голосовой профиль: ${if (summary.hasVoiceProfile) "есть" else "нет"}")
                Text("• фактов в памяти: ${summary.memories}")
                Text("• выученных команд: ${summary.learnedCommands}")
                Text("• сообщений в истории: ${summary.chatMessages}")
                Text("• дел «по пути»: ${summary.errands}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Текущие настройки, память и история будут заменены. Gmail, если был подключён, " +
                        "может попросить вход заново.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Заменить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun date(millis: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date(millis))
