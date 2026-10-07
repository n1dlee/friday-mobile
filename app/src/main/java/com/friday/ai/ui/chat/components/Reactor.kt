package com.friday.ai.ui.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.friday.ai.ui.overlay.ArcReactorView
import com.friday.ai.ui.theme.ArcAmber
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ArcCyanDim
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The arc reactor at the centre of the chat, the same one the overlay
 * shows, framed by two slow instrument rings: a segmented outer ring
 * turning one way and a ticked scale turning the other. The rings are
 * decoration; the reactor inside is the state (idle, listening, thinking).
 */
@Composable
fun ReactorHero(mood: ArcReactorView.Mood, modifier: Modifier = Modifier, size: Dp = 230.dp) {
    val reduced = rememberReducedMotion()
    val turn = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "rings")
        val v by t.animateFloat(
            0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "rings"
        )
        v
    }
    val tint = if (mood == ArcReactorView.Mood.THINKING) ArcAmber else ArcCyan
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(turn)) { segmentedRing(tint, radius = this.size.minDimension / 2f) }
        Canvas(Modifier.fillMaxSize().rotate(-turn * 1.6f)) {
            tickScale(tint, radius = this.size.minDimension / 2f * 0.84f)
        }
        AndroidView(
            factory = { ArcReactorView(it).apply { start() } },
            update = { it.setMood(mood) },
            modifier = Modifier.size(size * 0.62f)
        )
    }
}

/** Twelve arcs with gaps, every third one brighter: reads as an instrument, not a loader. */
private fun DrawScope.segmentedRing(tint: Color, radius: Float) {
    val stroke = 2.dp.toPx()
    val r = radius - stroke
    val segments = 12
    val sweep = 360f / segments
    for (i in 0 until segments) {
        drawArc(
            color = tint.copy(alpha = if (i % 3 == 0) 0.75f else 0.22f),
            startAngle = i * sweep + 4f,
            sweepAngle = sweep - 8f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** A ring of 72 ticks, longer every 30°, like a bearing scale. */
private fun DrawScope.tickScale(tint: Color, radius: Float) {
    val ticks = 72
    for (i in 0 until ticks) {
        val a = (i.toFloat() / ticks) * 2f * PI.toFloat()
        val long = i % 6 == 0
        val inner = radius - (if (long) 9.dp else 4.dp).toPx()
        drawLine(
            color = tint.copy(alpha = if (long) 0.55f else 0.2f),
            start = Offset(center.x + inner * cos(a), center.y + inner * sin(a)),
            end = Offset(center.x + radius * cos(a), center.y + radius * sin(a)),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * The microphone as a small reactor: a lit ring with a glow. While
 * listening, the glow breathes and an arc sweeps the ring; the icon
 * becomes "stop".
 */
@Composable
fun ReactorMicButton(listening: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val t = rememberInfiniteTransition(label = "mic")
    val sweep by t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "sweep"
    )
    val breath by t.animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breath"
    )
    val tint = if (listening) ErrorColor else ArcCyan
    val glow = if (listening && !reduced) breath else 0.35f
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (listening) "Остановить" else "Говорить" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = glow * 0.5f), Color.Transparent), radius = r))
            drawCircle(ArcCyanDim.copy(alpha = 0.6f), radius = r * 0.78f, style = Stroke(1.dp.toPx()))
            drawCircle(tint.copy(alpha = 0.9f), radius = r * 0.66f, style = Stroke(2.dp.toPx()))
            if (listening && !reduced) {
                drawArc(
                    tint, sweep, 70f, false,
                    topLeft = Offset(center.x - r * 0.78f, center.y - r * 0.78f),
                    size = androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Icon(
            if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
