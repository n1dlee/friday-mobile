package com.friday.ai.ui.theme

import androidx.compose.ui.graphics.Color

/** What the bar says under the title, and in which light. */
data class HudStatus(val text: String, val color: Color = ArcCyan, val live: Boolean = false)
