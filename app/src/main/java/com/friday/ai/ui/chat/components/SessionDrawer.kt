package com.friday.ai.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.friday.ai.data.local.dao.ChatSessionSummary
import java.util.Calendar

/**
 * Conversation history: a list of past chats with a "New chat" action,
 * grouped by recency. Voice conversations appear here too, since spoken and
 * typed turns share the same session ids.
 */
@Composable
fun SessionDrawer(
    sessions: List<ChatSessionSummary>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Friday AI",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onNewChat() }
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "New chat",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (sessions.isEmpty()) {
            Text(
                "No conversations yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(12.dp)
            )
            return@Column
        }

        Text(
            "Recents",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(sessions, key = { it.sessionId }) { session ->
                SessionRow(
                    session = session,
                    isActive = session.sessionId == currentSessionId,
                    onOpen = { onOpenSession(session.sessionId) },
                    onDelete = { onDeleteSession(session.sessionId) }
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionSummary,
    isActive: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val background =
        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable { onOpen() }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Icon(
            Icons.Filled.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title?.takeIf { it.isNotBlank() } ?: "New conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = relativeDay(session.lastActivityAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "Delete conversation",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

/** "Today" / "Yesterday" / a date — enough context without a full timestamp. */
private fun relativeDay(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)

    return when {
        sameYear && dayDiff == 0 -> "Today"
        sameYear && dayDiff == 1 -> "Yesterday"
        sameYear && dayDiff in 2..6 -> "$dayDiff days ago"
        else -> android.text.format.DateFormat.format("d MMM yyyy", timestamp).toString()
    }
}
