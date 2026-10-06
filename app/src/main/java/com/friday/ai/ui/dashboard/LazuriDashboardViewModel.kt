package com.friday.ai.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.core.LazuriGraph
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.dao.MemoryDao
import com.friday.ai.data.local.dao.SessionSummaryDao
import com.friday.ai.service.SessionSummarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Backs the map of everything Friday has learned: facts it extracted, and the
 * conversations they came from.
 */
class LazuriDashboardViewModel(
    private val memoryDao: MemoryDao,
    private val chatDao: ChatMessageDao,
    private val summaryDao: SessionSummaryDao,
    private val summarizer: SessionSummarizer
) : ViewModel() {

    data class UiState(
        val root: LazuriGraph.Node? = null,
        /** Ids of nodes the user has opened. */
        val expanded: Set<String> = setOf("root"),
        val selected: LazuriGraph.Node? = null,
        val isLoading: Boolean = true,
        val factCount: Int = 0,
        val sessionCount: Int = 0
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val facts = withContext(Dispatchers.IO) {
                runCatching { memoryDao.getRecent(200) }.getOrDefault(emptyList())
            }
            val sessionSummaries = withContext(Dispatchers.IO) {
                runCatching { chatDao.observeSessions().first() }.getOrDefault(emptyList())
            }

            val centre = facts
                .firstOrNull { it.key.equals("name", ignoreCase = true) }
                ?.value
                ?.replaceFirstChar { c -> c.uppercase() }
                ?: "Me"

            val graphFacts = facts.map {
                LazuriGraph.Fact(category = it.category, key = it.key, value = it.value)
            }

            val written = withContext(Dispatchers.IO) {
                runCatching { summaryDao.all().associateBy { it.sessionId } }
                    .getOrDefault(emptyMap())
            }

            val graphSessions = sessionSummaries.map { s ->
                LazuriGraph.Session(
                    id = s.sessionId,
                    title = s.title?.takeIf { it.isNotBlank() } ?: "Conversation",
                    dayLabel = dayLabel(s.lastActivityAt),
                    startedAt = s.startedAt,
                    // The written summary when there is one; the message
                    // count is only a placeholder until it's generated.
                    summary = written[s.sessionId]?.summary
                        ?: "${s.messageCount} messages"
                )
            }

            val root = LazuriGraph.build(centre, graphFacts, graphSessions)

            // Label any conversations that don't have a summary yet, then
            // refresh once they land.
            val missing = sessionSummaries.map { it.sessionId }
                .filter { written[it] == null }
            if (missing.isNotEmpty()) {
                viewModelScope.launch {
                    summarizer.backfill(missing)
                    reloadSummariesOnly()
                }
            }

            _uiState.update {
                it.copy(
                    root = root,
                    isLoading = false,
                    factCount = graphFacts.size,
                    sessionCount = graphSessions.size
                )
            }
        }
    }

    /** Re-reads summaries after a backfill without rebuilding everything. */
    private suspend fun reloadSummariesOnly() {
        val written = withContext(Dispatchers.IO) {
            runCatching { summaryDao.all().associateBy { it.sessionId } }
                .getOrDefault(emptyMap())
        }
        if (written.isEmpty()) return
        _uiState.update { state ->
            val root = state.root ?: return@update state
            state.copy(root = relabelSessions(root, written.mapValues { it.value.summary }))
        }
    }

    /** Swaps placeholder session labels for the generated summaries. */
    private fun relabelSessions(
        node: LazuriGraph.Node,
        summaries: Map<String, String>
    ): LazuriGraph.Node {
        val id = node.id.removePrefix("session:")
        val newDetail = if (node.kind == LazuriGraph.NodeKind.SESSION) {
            summaries[id] ?: node.detail
        } else {
            node.detail
        }
        return node.copy(
            detail = newDetail,
            children = node.children.map { relabelSessions(it, summaries) }
        )
    }

    /** Opens or closes a branch. Leaf nodes just get selected for detail. */
    fun onNodeTapped(node: LazuriGraph.Node) {
        _uiState.update { state ->
            if (!node.hasChildren) {
                state.copy(selected = node)
            } else {
                val expanded = state.expanded.toMutableSet()
                if (!expanded.add(node.id)) expanded.remove(node.id)
                state.copy(expanded = expanded, selected = node)
            }
        }
    }

    fun onDismissDetail() {
        _uiState.update { it.copy(selected = null) }
    }

    fun onCollapseAll() {
        _uiState.update { it.copy(expanded = setOf("root"), selected = null) }
    }

    private fun dayLabel(timestamp: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today"
            sameYear && dayDiff == 1 -> "Yesterday"
            sameYear && dayDiff in 2..6 -> "$dayDiff days ago"
            else -> android.text.format.DateFormat.format("d MMM", timestamp).toString()
        }
    }
}
