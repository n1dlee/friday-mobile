package com.friday.ai.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.OnPrimary
import com.friday.ai.ui.theme.OnSurfaceMuted
import com.friday.ai.ui.theme.SurfaceDark

/** A glass command line with the reactor microphone beside it. */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    isLoading: Boolean,
    isListening: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                // A physical keyboard (DeX, a Bluetooth one): Enter sends, Shift+Enter starts a new line.
                .onPreviewKeyEvent { e ->
                    val send = e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed
                    if (send && text.isNotBlank() && !isLoading) onSend()
                    send
                },
            placeholder = {
                Text(
                    if (isListening) "Слушаю…" else "Команда или вопрос",
                    color = OnSurfaceMuted
                )
            },
            shape = RoundedCornerShape(26.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isListening) ErrorColor else ArcCyan.copy(alpha = 0.7f),
                unfocusedBorderColor = ArcCyan.copy(alpha = 0.18f),
                focusedContainerColor = SurfaceDark.copy(alpha = 0.85f),
                unfocusedContainerColor = SurfaceDark.copy(alpha = 0.7f),
                cursorColor = ArcCyan
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4,
            enabled = !isLoading
        )

        AnimatedVisibility(
            visible = text.isNotBlank() && !isListening,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            FilledIconButton(
                onClick = onSend,
                enabled = !isLoading,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ArcCyan, contentColor = OnPrimary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
        }

        ReactorMicButton(listening = isListening, onClick = onMicClick)
    }
}
