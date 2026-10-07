package com.friday.ai.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ArcCyanDim
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.OnSurfaceMuted
import com.friday.ai.ui.theme.UserBubble
import com.friday.ai.ui.theme.rememberReducedMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One line of the conversation.
 *
 * The owner speaks from a lit glass card on the right. Friday answers
 * from the dark on the left, without a bubble: a cyan rail, her name and
 * the time in monospace, then the text, the way a HUD prints a log line.
 */
@Composable
fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    if (message.role == MessageRole.USER) UserLine(message, modifier) else FridayLine(message, modifier)
}

@Composable
private fun UserLine(message: Message, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    Brush.linearGradient(listOf(UserBubble.copy(alpha = 0.85f), UserBubble.copy(alpha = 0.45f))),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                )
                .border(
                    1.dp, ArcCyan.copy(alpha = 0.28f),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(message.content, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            }
            Text(
                time(message.timestamp),
                style = HudLabelStyle,
                color = OnSurfaceMuted,
                modifier = Modifier.align(androidx.compose.ui.Alignment.End).padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun FridayLine(message: Message, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 28.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(ArcCyan, ArcCyanDim.copy(alpha = 0.1f))))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.animateContentSize()) {
            Text(
                "ПЯТНИЦА · ${time(message.timestamp)}",
                style = HudLabelStyle,
                color = ArcCyan.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(4.dp))
            // The cursor is part of the text, so it sits after the last word however the text wraps.
            val cursor = if (message.isStreaming) cursorAlpha() else 0f
            SelectionContainer {
                Text(
                    buildAnnotatedString {
                        append(message.content)
                        if (message.isStreaming) {
                            withStyle(SpanStyle(color = ArcCyan.copy(alpha = cursor))) { append(" ▍") }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
            }
        }
    }
}

/** How lit the block cursor is right now: it blinks while the answer is still arriving. */
@Composable
private fun cursorAlpha(): Float {
    if (rememberReducedMotion()) return 1f
    val t = rememberInfiniteTransition(label = "cursor")
    val v by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "cursor")
    return v
}

private fun time(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
