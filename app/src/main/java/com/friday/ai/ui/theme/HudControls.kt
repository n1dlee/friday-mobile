package com.friday.ai.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Buttons, switches and fields in the HUD language, so every screen draws them the same way. */

private val ControlShape = RoundedCornerShape(10.dp)

/** The one lit action of a panel. */
@Composable
fun HudButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ArcCyan,
            contentColor = OnPrimary,
            disabledContainerColor = ArcCyan.copy(alpha = 0.15f),
            disabledContentColor = OnSurfaceMuted
        ),
        content = content
    )
}

/** Secondary actions: an outline that lights up, nothing filled. */
@Composable
fun HudOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, ArcCyan.copy(alpha = if (selected) 0.8f else if (enabled) 0.35f else 0.12f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) ArcCyan.copy(alpha = 0.14f) else ArcCyan.copy(alpha = 0.02f),
            contentColor = ArcCyan
        ),
        content = content
    )
}

@Composable
fun HudSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = OnBackground)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ArcCyanHot,
                checkedTrackColor = ArcCyan.copy(alpha = 0.45f),
                checkedBorderColor = ArcCyan,
                uncheckedThumbColor = OnSurfaceMuted,
                uncheckedTrackColor = SurfaceVariant,
                uncheckedBorderColor = OutlineDim
            )
        )
    }
}

/** Plain explanatory text inside a panel. */
@Composable
fun HudNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted, modifier = modifier)
}

@Composable
fun hudFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ArcCyan.copy(alpha = 0.8f),
    unfocusedBorderColor = ArcCyan.copy(alpha = 0.2f),
    focusedLabelColor = ArcCyan,
    unfocusedLabelColor = OnSurfaceMuted,
    cursorColor = ArcCyan,
    focusedContainerColor = BackgroundDark.copy(alpha = 0.5f),
    unfocusedContainerColor = BackgroundDark.copy(alpha = 0.4f)
)
