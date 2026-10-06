package com.friday.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FridayDarkScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = OnPrimary,
    primaryContainer = ArcCyanDim,
    onPrimaryContainer = ArcCyanHot,
    secondary = ArcAmber,
    onSecondary = OnPrimary,
    // The dashboard graph reads tertiary for its category ring; leaving it at
    // Material's default put a stray purple in an otherwise two-hue palette.
    tertiary = ArcCyanHot,
    onTertiary = OnPrimary,
    background = BackgroundDark,
    onBackground = OnBackground,
    surface = SurfaceDark,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    outline = OutlineDim,
    outlineVariant = OutlineDim,
    error = ErrorColor,
    onError = OnPrimary
)

@Composable
fun FridayTheme(content: @Composable () -> Unit) {
    // Only one scheme on purpose: the arc reactor look depends on a dark room.
    MaterialTheme(
        colorScheme = FridayDarkScheme,
        typography = FridayTypography,
        content = content
    )
}
