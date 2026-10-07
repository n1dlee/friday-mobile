package com.friday.ai.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.OnSurfaceMuted

/** What each personality is called on screen; the enum's names are for the prompt. */
val AssistantMode.label: String
    get() = when (this) {
        AssistantMode.DEFAULT -> "Обычный"
        AssistantMode.ANALYTICAL -> "Аналитик"
        AssistantMode.CREATIVE -> "Творческий"
        AssistantMode.CODING -> "Код"
        AssistantMode.STUDY -> "Учёба"
        AssistantMode.FOUNDER -> "Стратег"
        AssistantMode.FOCUS -> "Фокус"
    }

/** Personality tabs as HUD toggles: lit edge and text when active, dim otherwise. */
@Composable
fun ModeSelector(
    currentMode: AssistantMode,
    onModeSelected: (AssistantMode) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(AssistantMode.entries.toList()) { mode ->
            val on = mode == currentMode
            val shape = RoundedCornerShape(8.dp)
            Text(
                mode.label.uppercase(),
                style = HudLabelStyle,
                color = if (on) ArcCyan else OnSurfaceMuted,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .background(if (on) ArcCyan.copy(alpha = 0.12f) else ArcCyan.copy(alpha = 0.03f))
                    .border(1.dp, ArcCyan.copy(alpha = if (on) 0.6f else 0.12f), shape)
                    .semantics { selected = on }
                    .clickable(role = Role.Tab) { onModeSelected(mode) }
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            )
        }
    }
}
