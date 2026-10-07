package com.friday.ai.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The HUD language shared by every screen.
 *
 * Same rule as the palette: cyan is light, not paint. A panel is the dark
 * room with a hairline edge and four bright corner brackets, the way a
 * heads-up display frames a target; text that labels something (section
 * names, states, numbers) is monospaced capitals, text that says something
 * is ordinary sans. Motion is slow and stops entirely when the system's
 * animations are off.
 */

/** Monospaced capitals for labels, states and figures. */
val HudLabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.6.sp
)

/** The wordmark: wide-tracked, the way the suit spells its own name. */
val HudBrandStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    letterSpacing = 4.sp
)

/** True when the user turned animations off (accessibility or developer setting). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The room: near-black, a faint cyan glow from above as if the reactor
 * were just off-screen, and a measuring grid at the edge of visibility.
 */
@Composable
fun HudBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(ArcCyan.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width / 2f, -size.height * 0.05f),
                        radius = size.maxDimension * 0.75f
                    )
                )
                drawGrid(step = 28.dp.toPx(), color = ArcCyan.copy(alpha = 0.028f))
            },
        content = content
    )
}

private fun DrawScope.drawGrid(step: Float, color: Color) {
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/**
 * A hairline frame with bright corner brackets. [accent] lights the
 * brackets; the edge between them stays dim.
 */
fun Modifier.hudFrame(
    accent: Color = ArcCyan,
    corner: Dp = 14.dp,
    bracket: Dp = 12.dp,
    fill: Color = SurfaceDark.copy(alpha = 0.72f)
): Modifier = drawBehind {
    val r = corner.toPx()
    val b = bracket.toPx()
    val stroke = 1.dp.toPx()
    drawRoundRect(fill, cornerRadius = CornerRadius(r))
    drawRoundRect(
        accent.copy(alpha = 0.16f),
        cornerRadius = CornerRadius(r),
        style = Stroke(width = stroke)
    )
    drawBrackets(accent, r, b, 2.dp.toPx())
}

/** Four L-shaped ticks hugging the rounded corners. */
private fun DrawScope.drawBrackets(color: Color, r: Float, length: Float, width: Float) {
    val w = size.width
    val h = size.height
    val inset = width / 2f
    val arc = Size(r * 2 - width, r * 2 - width)
    val style = Stroke(width = width, cap = StrokeCap.Round)
    val c = color.copy(alpha = 0.85f)
    // Each corner: the arc itself, then a short straight run along both edges.
    drawArc(c, 180f, 90f, false, Offset(inset, inset), arc, style = style)
    drawLine(c, Offset(r, inset), Offset(r + length, inset), width, StrokeCap.Round)
    drawLine(c, Offset(inset, r), Offset(inset, r + length), width, StrokeCap.Round)

    drawArc(c, 270f, 90f, false, Offset(w - r * 2 + inset, inset), arc, style = style)
    drawLine(c, Offset(w - r - length, inset), Offset(w - r, inset), width, StrokeCap.Round)
    drawLine(c, Offset(w - inset, r), Offset(w - inset, r + length), width, StrokeCap.Round)

    drawArc(c, 0f, 90f, false, Offset(w - r * 2 + inset, h - r * 2 + inset), arc, style = style)
    drawLine(c, Offset(w - r - length, h - inset), Offset(w - r, h - inset), width, StrokeCap.Round)
    drawLine(c, Offset(w - inset, h - r - length), Offset(w - inset, h - r), width, StrokeCap.Round)

    drawArc(c, 90f, 90f, false, Offset(inset, h - r * 2 + inset), arc, style = style)
    drawLine(c, Offset(r, h - inset), Offset(r + length, h - inset), width, StrokeCap.Round)
    drawLine(c, Offset(inset, h - r - length), Offset(inset, h - r), width, StrokeCap.Round)
}

/** Section label: "02 · ГОЛОС", with a rule running to the edge. */
@Composable
fun HudLabel(text: String, modifier: Modifier = Modifier, index: Int? = null, color: Color = ArcCyan) {
    Row(modifier = modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        if (index != null) {
            Text("%02d".format(index), style = HudLabelStyle, color = color.copy(alpha = 0.55f))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = HudLabelStyle, color = color)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        )
    }
}

/** A framed section of a screen. */
@Composable
fun HudPanel(
    title: String,
    modifier: Modifier = Modifier,
    index: Int? = null,
    accent: Color = ArcCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hudFrame(accent = accent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HudLabel(title, index = index, color = accent)
        content()
    }
}

/** A lit dot: a state you can read at a glance. Pulses when [live]. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 8.dp) {
    val reduced = rememberReducedMotion()
    val pulse = if (live && !reduced) {
        val t = rememberInfiniteTransition(label = "dot")
        val v by t.animateFloat(
            0.45f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "dot"
        )
        v
    } else {
        1f
    }
    Canvas(modifier.size(size * 2)) {
        val r = size.toPx() / 2f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.45f * pulse), Color.Transparent), radius = r * 2f),
            radius = r * 2f
        )
        drawCircle(color.copy(alpha = pulse), radius = r)
    }
}

/** A key/value line in monospace: "МОДЕЛЬ ……… gpt-oss-120b". */
@Composable
fun HudReadout(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = OnBackground) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = HudLabelStyle, color = OnSurfaceMuted)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .padding(top = 6.dp)
                .height(1.dp)
                .background(OutlineDim)
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = HudLabelStyle.copy(letterSpacing = 0.5.sp), color = valueColor)
    }
}

/** A thin scanning line: "working on it", amber, calm. */
@Composable
fun HudScanLine(modifier: Modifier = Modifier, color: Color = ArcAmber) {
    val reduced = rememberReducedMotion()
    val x = if (reduced) {
        0.5f
    } else {
        val t = rememberInfiniteTransition(label = "scan")
        val v by t.animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "scan"
        )
        v
    }
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(color.copy(alpha = 0.12f))
        val span = size.width * 0.28f
        val start = -span + (size.width + span) * x
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, color, Color.Transparent),
                startX = start, endX = start + span
            )
        )
    }
}

