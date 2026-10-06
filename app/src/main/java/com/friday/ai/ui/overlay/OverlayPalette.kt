package com.friday.ai.ui.overlay

/**
 * The overlay is drawn with android.graphics, not Compose, so it needs plain
 * ARGB ints. These are the same values as the Compose palette in
 * `ui.theme.Color` — keep the two in step.
 */
object OverlayPalette {
    /** 94% opaque: the app underneath stays visible, the panel still reads as solid. */
    const val PANEL = 0xF00A1016.toInt()
    const val PANEL_EDGE = 0x4438E8FF
    const val CYAN = 0xFF38E8FF.toInt()
    const val CYAN_HOT = 0xFFBFF6FF.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val TEXT = 0xFFDDF2F8.toInt()
    const val TEXT_MUTED = 0xFF7E97A3.toInt()
}
