package com.friday.ai.service.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last few voice checks, for Settings: when the owner is turned away,
 * the numbers say why ("0.41 against 0.49") instead of leaving it a mystery.
 */
object VoiceChecks {

    data class Check(val what: String, val score: Float, val threshold: Float, val passed: Boolean, val at: Long)

    private const val KEEP = 6
    private val _recent = MutableStateFlow<List<Check>>(emptyList())
    val recent: StateFlow<List<Check>> = _recent.asStateFlow()

    fun record(what: String, score: Float, threshold: Float, passed: Boolean) {
        val check = Check(what, score, threshold, passed, System.currentTimeMillis())
        _recent.value = (listOf(check) + _recent.value).take(KEEP)
    }
}
