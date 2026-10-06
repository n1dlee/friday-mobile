package com.friday.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Jarvis workshop palette: one cyan light source in a near-black room.
 *
 * The rule that keeps it coherent — cyan is *emitted*, never painted. It
 * belongs to things that are alive (the arc reactor, an active state, a link
 * you can press). Everything structural is near-black with a hairline edge, so
 * the eye reads the cyan as light rather than as decoration. Amber is the only
 * second hue, reserved for "thinking" and warnings; nothing else gets colour.
 */

// --- The light ---------------------------------------------------------
/** The core LED. Bright enough to read as a source against the dark. */
val ArcCyan = Color(0xFF38E8FF)

/** Hot centre of the glow — used sparingly, at the middle of a bloom. */
val ArcCyanHot = Color(0xFFBFF6FF)

/** Cyan pushed back into the dark: rings, hairlines, inactive rails. */
val ArcCyanDim = Color(0xFF0E7C93)

/** Stark amber. The only other hue, and only for "working" / caution. */
val ArcAmber = Color(0xFFFFB020)

// --- The room ----------------------------------------------------------
val BackgroundDark = Color(0xFF04070A)   // near-black with a blue cast
val SurfaceDark = Color(0xFF0A1016)      // panels
val SurfaceVariant = Color(0xFF121C24)   // raised panels, input fields
val OutlineDim = Color(0xFF1E2C36)       // hairline structure

// --- Text --------------------------------------------------------------
val OnBackground = Color(0xFFDDF2F8)     // cool white, not pure white
val OnSurface = Color(0xFFC6DDE6)
val OnSurfaceMuted = Color(0xFF7E97A3)   // 4.6:1 on SurfaceDark — still readable
val OnPrimary = Color(0xFF00181F)        // dark ink on a lit cyan surface

val ErrorColor = Color(0xFFFF5A63)

// --- Chat --------------------------------------------------------------
/** The user speaks from a lit surface; Friday answers from the dark. */
val UserBubble = Color(0xFF10485A)
val AssistantBubble = Color(0xFF0D141B)

// Kept so older call sites still compile; both now point at the arc.
val PrimaryDark = ArcCyan
val PrimaryVariant = ArcCyanHot
val SecondaryDark = ArcAmber
