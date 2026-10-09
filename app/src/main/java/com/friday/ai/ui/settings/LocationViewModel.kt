package com.friday.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.service.WeatherHere
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The owner's city for weather, and whether Friday may use the phone's location. */
class LocationViewModel(private val weather: WeatherHere) : ViewModel() {

    private val _city = MutableStateFlow("")
    val city: StateFlow<String> = _city.asStateFlow()

    /** What the last save did, to show under the field; null before any. */
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    init {
        viewModelScope.launch { _city.value = weather.homeCity().orEmpty() }
    }

    /** Checks [typed] against the map and keeps it as the map spells it; blank clears it. */
    fun save(typed: String) {
        viewModelScope.launch {
            val saved = weather.setHomeCity(typed, russian = true)
            _result.value = when {
                saved == null -> "Город «${typed.trim()}» не найден"
                saved.isEmpty() -> "Город по умолчанию убран"
                else -> "Сохранено: $saved"
            }
            if (saved != null) _city.value = saved
        }
    }
}
