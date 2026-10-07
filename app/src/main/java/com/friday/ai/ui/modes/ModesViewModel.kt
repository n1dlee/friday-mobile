package com.friday.ai.ui.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.modes.Mode
import com.friday.ai.core.modes.ModeStore
import com.friday.ai.domain.model.CommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Modes screen. Buttons go through [CommandExecutor] exactly like the
 * spoken phrase, so tapping "Включить" and saying "режим грусти" do the
 * same thing and say the same thing.
 */
class ModesViewModel(
    store: ModeStore,
    private val commands: CommandExecutor,
    private val timetable: com.friday.ai.core.modes.ModeSchedules? = null
) : ViewModel() {

    val modes: StateFlow<List<Mode>?> = store.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Each mode's schedules, by mode id. */
    val schedules: StateFlow<Map<String, List<com.friday.ai.core.modes.Schedule>>> =
        (timetable?.observe() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .map { all -> all.groupBy { it.modeId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun unschedule(s: com.friday.ai.core.modes.Schedule) {
        viewModelScope.launch { timetable?.delete(s.id) }
    }

    private val _status = MutableStateFlow<String?>(null)
    /** What the last action reported, as Friday would have said it. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** The id of the mode being run or stopped right now. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    fun run(mode: Mode) = act(mode, CommandResult.Mode.Run(mode.name))

    fun stop(mode: Mode) = act(mode, CommandResult.Mode.Exit(mode.name))

    fun delete(mode: Mode) = act(mode, CommandResult.Mode.Delete(mode.name))

    private fun act(mode: Mode, request: CommandResult.Mode) {
        _busy.value = mode.id
        viewModelScope.launch {
            val outcome = commands.execute(request, russian = true)
            _status.value = (outcome as? CommandExecutor.Outcome.Reply)?.text
            _busy.value = null
        }
    }
}
