package com.friday.ai.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.core.DeviceContext
import com.friday.ai.core.capabilities.Diagnostics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.os.Build
import com.friday.ai.core.capabilities.DiagnosticReport

/** The diagnostics screen reads the same snapshot that decides what the model is offered. */
class DiagnosticsViewModel(private val device: DeviceContext) : ViewModel() {

    val rows: StateFlow<List<Diagnostics.Row>?> = device.capabilities
        .map { caps -> caps?.let(Diagnostics::rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Re-reads everything; called on every return to the screen, e.g. from a settings page. */
    fun refresh() {
        viewModelScope.launch { device.refresh() }
    }

    /**
     * The redacted report, ready to share: what the diagnostics say and the
     * app's own recent log (an app may read its own log, nobody else's).
     */
    suspend fun report(): String = withContext(Dispatchers.IO) {
        val logcat = arrayOf(
            "logcat", "-d", "-v", "time", "-t", LOG_LINES.toString(), "--pid=${android.os.Process.myPid()}"
        )
        val log = runCatching {
            Runtime.getRuntime()
                .exec(logcat)
                .inputStream.bufferedReader().readLines()
        }.getOrDefault(listOf("(журнал недоступен)"))
        DiagnosticReport.build(
            version = com.friday.ai.BuildConfig.VERSION_NAME,
            device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})",
            rows = rows.value ?: Diagnostics.rows(device.refresh()),
            log = log
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val LOG_LINES = 400
    }
}
