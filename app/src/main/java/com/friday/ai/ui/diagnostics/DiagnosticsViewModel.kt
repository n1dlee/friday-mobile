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

/** The diagnostics screen reads the same snapshot that decides what the model is offered. */
class DiagnosticsViewModel(private val device: DeviceContext) : ViewModel() {

    val rows: StateFlow<List<Diagnostics.Row>?> = device.capabilities
        .map { caps -> caps?.let(Diagnostics::rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Re-reads everything; called on every return to the screen, e.g. from a settings page. */
    fun refresh() {
        viewModelScope.launch { device.refresh() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
