package com.friday.ai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.links.QuickLink
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.hudFieldColors
import java.net.URI
import org.koin.androidx.compose.koinViewModel

@Composable
fun QuickLinksPanel(viewModel: QuickLinksViewModel = koinViewModel()) {
    val links by viewModel.links.collectAsStateWithLifecycle()
    // null: closed; an empty link: adding; otherwise the one being edited.
    var editing by remember { mutableStateOf<QuickLink?>(null) }

    HudPanel("Быстрые ссылки", index = 13) {
        HudNote(
            "Ваша фраза → ваш сайт в новой вкладке Chrome. Формы слов и вступления не важны: «давай посмотрим " +
                "фильм» сработает и от «хочу посмотреть фильмы». Фраза из одного слова — только в короткой просьбе. " +
                "«Молча»: без ответа, и ни в чате, ни в памяти Пятницы не остаётся."
        )
        links.sortedBy { it.name.lowercase() }.forEach { link -> LinkRow(link) { editing = link } }
        HudOutlinedButton(
            onClick = { editing = QuickLink("", "", emptyList(), "") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Добавить ссылку") }
    }

    editing?.let { link ->
        LinkDialog(
            link = link,
            onSave = { name, phrases, address, silent ->
                viewModel.save(link.id.ifEmpty { null }, name, phrases, address, silent).also {
                    if (it == null) editing = null
                }
            },
            onDelete = if (link.id.isEmpty()) null else ({ viewModel.delete(link.id); editing = null }),
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun LinkRow(link: QuickLink, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Изменить", onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(
            link.name + if (link.silent) " · молча" else "",
            color = ArcCyan,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "«${link.phrases.joinToString("», «")}» → ${host(link.url)}",
            color = OnBackground,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Only the site's name in the list; the full address is in the editor. */
private fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

@Composable
private fun LinkDialog(
    link: QuickLink,
    onSave: (name: String, phrases: String, address: String, silent: Boolean) -> String?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(link.name) }
    var phrases by remember { mutableStateOf(link.phrases.joinToString(", ")) }
    var address by remember { mutableStateOf(link.url) }
    var silent by remember { mutableStateOf(link.silent) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (link.id.isEmpty()) "Новая ссылка" else "Ссылка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkField(name, { name = it }, "Название", "Фильмы")
                LinkField(phrases, { phrases = it }, "Фразы, через запятую", "давай посмотрим фильм, включи кино")
                LinkField(address, { address = it }, "Адрес сайта", "example.com")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Открывать молча", Modifier.weight(1f))
                    Switch(checked = silent, onCheckedChange = { silent = it })
                }
                problem?.let { Text(it, color = ErrorColor, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { problem = onSave(name, phrases, address, silent) }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Удалить", color = ErrorColor) } }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

@Composable
private fun LinkField(value: String, onChange: (String) -> Unit, label: String, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        colors = hudFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}
