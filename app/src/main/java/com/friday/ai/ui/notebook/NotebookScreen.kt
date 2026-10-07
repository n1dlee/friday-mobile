package com.friday.ai.ui.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.hudFieldColors
import com.friday.ai.ui.theme.hudFrame
import org.koin.compose.koinInject

/**
 * Friday's notebook: write or draw — a question, a sum, a diagram — and
 * ask. The page goes to the vision model as a picture, so it reads
 * handwriting, formulas and sketches alike.
 *
 * With an S Pen (or any stylus) the finger is ignored while writing, so a
 * resting palm leaves no marks; the pen's eraser end, where it has one,
 * removes the strokes it touches.
 */
@Composable
fun NotebookScreen(onNavigateBack: () -> Unit, onAsk: () -> Unit, inbox: NotebookInbox = koinInject()) {
    val strokes = remember { mutableStateListOf<InkStroke>() }
    var current by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var question by remember { mutableStateOf("") }
    var penSeen by remember { mutableStateOf(false) }
    val eraserRadius = with(LocalDensity.current) { 14.dp.toPx() }

    HudBackground {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            HudTopBar(
                title = "БЛОКНОТ",
                status = HudStatus(if (penSeen) "Перо" else "Пишите пальцем или пером"),
                navigation = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = OnBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { strokes.removeLastOrNull() }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить штрих", tint = ArcCyan)
                    }
                    IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить", tint = ArcCyan)
                    }
                }
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(14.dp)
                    .hudFrame()
                    .ink(
                        eraserRadius = eraserRadius,
                        penSeen = { penSeen },
                        onPen = { penSeen = true },
                        onDrawing = { current = it },
                        onStroke = { strokes += it; current = emptyList() },
                        onErase = { x, y -> strokes.removeAll { it.touches(x, y, eraserRadius) } }
                    )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    (strokes.map { it.points } + listOf(current)).forEach { pts -> drawInk(pts) }
                }
                if (strokes.isEmpty() && current.isEmpty()) {
                    HudNote(
                        "Напишите вопрос или пример, нарисуйте схему. Потом — «Спросить».",
                        Modifier.align(Alignment.Center).padding(24.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("Вопрос (необязательно): «реши», «переведи»…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = hudFieldColors()
                )
                HudButton(
                    onClick = {
                        InkRenderer.render(strokes.toList())?.let { bitmap ->
                            inbox.put(NotebookInbox.Page(InkRenderer.base64(bitmap), question.trim()))
                            onAsk()
                        }
                    },
                    enabled = strokes.isNotEmpty()
                ) { Text("Спросить") }
            }
        }
    }
}

/**
 * Pen and finger input for the page. Once a pen has been seen, fingers
 * don't draw (a resting palm leaves no marks); the pen's eraser end removes
 * what it touches.
 */
@Suppress("LongParameterList") // one callback per thing a gesture can do
private fun Modifier.ink(
    eraserRadius: Float,
    penSeen: () -> Boolean,
    onPen: () -> Unit,
    onDrawing: (List<InkPoint>) -> Unit,
    onStroke: (InkStroke) -> Unit,
    onErase: (Float, Float) -> Unit
): Modifier = pointerInput(eraserRadius) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val type = down.type
        if (type == PointerType.Stylus || type == PointerType.Eraser) onPen()
        if (penSeen() && type == PointerType.Touch) return@awaitEachGesture
        val erasing = type == PointerType.Eraser
        var points = listOf(InkPoint(down.position.x, down.position.y, down.pressure))
        if (!erasing) onDrawing(points)
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) break
            change.consume()
            if (erasing) {
                onErase(change.position.x, change.position.y)
            } else {
                points = points + InkPoint(change.position.x, change.position.y, change.pressure)
                onDrawing(points)
            }
        }
        if (!erasing) onStroke(InkStroke(points))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInk(points: List<InkPoint>) {
    if (points.isEmpty()) return
    val base = 3.dp.toPx()
    if (points.size == 1) {
        val dot = points[0]
        drawCircle(ArcCyan, radius = base * dot.pressure.coerceIn(0.4f, 1.6f) / 2, center = Offset(dot.x, dot.y))
        return
    }
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        drawLine(
            ArcCyan, Offset(a.x, a.y), Offset(b.x, b.y),
            strokeWidth = base * ((a.pressure + b.pressure) / 2).coerceIn(0.4f, 1.6f),
            cap = StrokeCap.Round
        )
    }
}
