package com.friday.ai.core

import android.content.Context
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.service.WhisperTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceInputManager(
    private val context: Context,
    private val groqApi: GroqApiService,
    private val prefDao: UserPreferenceDao,
    private val speechHints: com.friday.ai.service.SpeechHints
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transcriber = WhisperTranscriber(groqApi, context.cacheDir).apply { hints = speechHints::prompt }

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        transcriber.listener = object : WhisperTranscriber.Listener {
            override fun onRecordingStarted() {
                _isListening.value = true
                _error.value = null
            }

            override fun onTranscriptionResult(text: String) {
                _recognizedText.value = text
                _isListening.value = false
            }

            override fun onError(message: String) {
                _error.value = message
                _isListening.value = false
            }
        }
    }

    fun startListening() {
        if (_isListening.value) return
        _isListening.value = true
        _error.value = null

        scope.launch {
            val apiKey = prefDao.get("groq_api_key") ?: ""
            if (apiKey.isBlank()) {
                _error.value = "API key not set. Go to Settings."
                _isListening.value = false
                return@launch
            }

            // No speaker check here: pressing the mic button in the open app
            // is already proof of who is asking.
            val result = transcriber.recordAndTranscribe(apiKey)
            if (result !is VoiceTurn.Outcome.Heard && _error.value == null) {
                _error.value = "No speech detected. Try again."
            }
            _isListening.value = false
        }
    }

    fun stopListening() {
        _isListening.value = false
    }

    fun destroy() {
        scope.cancel()
    }

    fun clearResult() {
        _recognizedText.value = null
    }
}
