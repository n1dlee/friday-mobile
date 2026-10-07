package com.friday.ai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.LazuriApiService
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.FridayWakeWordService
import com.friday.ai.service.FridayNotificationListener
import com.friday.ai.service.FridayWakeWordService.Companion.PREF_VOICE_PROFILE
import com.friday.ai.core.GroqModels
import com.friday.ai.core.VoiceProfile
import com.friday.ai.service.SpeakerEmbedder
import com.friday.ai.service.VoiceCalibrator
import com.friday.ai.service.VoiceEnroller
import com.friday.ai.service.mail.GmailConnector
import com.friday.ai.service.VoskModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// One screen, one collaborator per section of it; grouping them would only hide that.
@Suppress("LongParameterList")
class SettingsViewModel(
    private val prefDao: UserPreferenceDao,
    private val appContext: Context,
    private val gmailConnector: GmailConnector,
    private val catalog: com.friday.ai.service.ModelCatalog,
    private val lazuriApi: LazuriApiService,
    private val memory: FridayMemory,
    private val learned: com.friday.ai.agent.LearnedCommands
) : ViewModel() {

    // --- Learned commands ------------------------------------------------------

    /** Phrase → what it does, for the settings list. */
    private val _learnedCommands = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val learnedCommands: StateFlow<List<Pair<String, String>>> = _learnedCommands.asStateFlow()

    fun refreshLearned() {
        _learnedCommands.value = learned.all().entries
            .sortedBy { it.key }
            .map { (phrase, s) ->
                phrase to (s.tool + " " + s.args.values.joinToString(", ") { it.toString().trim('"') })
            }
    }

    fun forgetLearned(phrase: String) {
        learned.forget(phrase)
        refreshLearned()
    }

    fun forgetAllLearned() {
        learned.forgetAll()
        refreshLearned()
    }

    // --- Gmail ---------------------------------------------------------------

    /**
     * @param account the connected address, or null
     * @param consent set when Google needs the user to approve access; the
     *   screen launches it and hands the result back to [onGmailConsent]
     */
    data class Gmail(
        val account: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val consent: android.app.PendingIntent? = null
    )

    private val _gmail = MutableStateFlow(Gmail())
    val gmail: StateFlow<Gmail> = _gmail.asStateFlow()

    fun connectGmail() {
        if (_gmail.value.busy) return
        _gmail.value = _gmail.value.copy(busy = true, message = null)
        viewModelScope.launch { show(gmailConnector.start()) }
    }

    /** Called with whatever the consent screen returned. */
    fun onGmailConsent(data: android.content.Intent?, completed: Boolean) {
        _gmail.value = _gmail.value.copy(consent = null)
        viewModelScope.launch { show(gmailConnector.finish(data, completed)) }
    }

    fun disconnectGmail() {
        viewModelScope.launch {
            gmailConnector.disconnect()
            _gmail.value = Gmail(message = "Отключено")
        }
    }

    private fun show(step: GmailConnector.Step) {
        _gmail.value = when (step) {
            is GmailConnector.Step.NeedsConsent -> _gmail.value.copy(consent = step.intent)
            is GmailConnector.Step.Failed -> _gmail.value.copy(busy = false, message = step.reason)
            is GmailConnector.Step.Connected -> Gmail(account = step.account, message = "Подключено")
        }
    }

    private fun loadGmail() {
        viewModelScope.launch {
            _gmail.value = Gmail(account = gmailConnector.account())
        }
    }

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(GroqModels.fallback(GroqModels.Role.CHAT))
    val model: StateFlow<String> = _model.asStateFlow()

    /** Conversation models the key can use right now, best first. */
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    // --- voice profile -------------------------------------------------

    /**
     * Enrolment progress. `total == 0` means idle; otherwise the UI shows
     * which sample is being recorded.
     */
    data class Enrolment(
        val recorded: Int = 0,
        val total: Int = 0,
        val recording: Boolean = false,
        val message: String? = null
    )

    private val _enrolment = MutableStateFlow(Enrolment())
    val enrolment: StateFlow<Enrolment> = _enrolment.asStateFlow()

    private val _voiceProfile = MutableStateFlow<VoiceProfile.Profile?>(null)
    val voiceProfile: StateFlow<VoiceProfile.Profile?> = _voiceProfile.asStateFlow()

    private val enroller = VoiceEnroller()

    /**
     * Walks the user through [VoiceProfile.ENROLMENT_PHRASES] and saves a
     * profile.
     *
     * The whole set is embedded before anything is written, so a run that is
     * abandoned half way leaves the previous profile intact rather than a
     * half-built one that rejects its owner.
     */
    fun enrollVoice() {
        if (_enrolment.value.recording) return
        viewModelScope.launch {
            val embedder = SpeakerEmbedder(appContext)
            if (!embedder.isAvailable()) {
                _enrolment.value = Enrolment(message = "Модель распознавания голоса не загрузилась")
                return@launch
            }
            val phrases = VoiceProfile.ENROLMENT_PHRASES
            val embeddings = mutableListOf<FloatArray>()
            var attempts = 0
            // Why the last try failed, shown with the next prompt rather than
            // in place of it — a separate message would be overwritten at once.
            var problem: String? = null
            try {
                while (embeddings.size < phrases.size && attempts < phrases.size * 3) {
                    attempts++
                    val phrase = phrases[embeddings.size]
                    val prompt = "Скажите: «$phrase»"
                    _enrolment.value = Enrolment(
                        embeddings.size, phrases.size, recording = true,
                        message = problem?.let { "$it $prompt" } ?: prompt
                    )
                    val audio = enroller.recordOne()
                    val e = audio?.let { embedder.embed(it) }
                    problem = when {
                        audio == null -> "Не расслышал."
                        e == null -> "Слишком коротко."
                        else -> null
                    }
                    if (e != null) embeddings.add(e)
                }

                val profile = VoiceProfile.enroll(embeddings)
                if (profile == null) {
                    _enrolment.value = Enrolment(message = "Не хватило чистых записей — попробуйте в тишине")
                    return@launch
                }
                prefDao.set(UserPreferenceEntity(PREF_VOICE_PROFILE, VoiceProfile.serialise(profile)))
                _voiceProfile.value = profile
                _enrolment.value = Enrolment(
                    recorded = profile.sampleCount, total = phrases.size,
                    message = "Готово. Похожесть записей: %.2f".format(profile.cohesion)
                )
            } finally {
                embedder.close()
            }
        }
    }

    fun clearVoiceProfile() {
        viewModelScope.launch {
            prefDao.set(UserPreferenceEntity(PREF_VOICE_PROFILE, ""))
            _voiceProfile.value = null
            _enrolment.value = Enrolment()
        }
    }

    private fun loadVoiceProfile() {
        viewModelScope.launch {
            _voiceProfile.value = VoiceProfile.deserialise(prefDao.get(PREF_VOICE_PROFILE))
        }
    }

    private val _wakeWordEnabled = MutableStateFlow(false)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    private val modelManager = VoskModelManager(appContext)
    val modelDownloadState = modelManager.downloadState

    private val _calibrationPhase = MutableStateFlow<VoiceCalibrator.Phase?>(null)
    val calibrationPhase: StateFlow<VoiceCalibrator.Phase?> = _calibrationPhase.asStateFlow()

    private val _calibrationEnergy = MutableStateFlow(0.0)
    val calibrationEnergy: StateFlow<Double> = _calibrationEnergy.asStateFlow()

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated.asStateFlow()

    private val _calibrationResult = MutableStateFlow<VoiceCalibrator.CalibrationResult?>(null)
    val calibrationResult: StateFlow<VoiceCalibrator.CalibrationResult?> = _calibrationResult.asStateFlow()

    private val _lazuriUrl = MutableStateFlow("")
    val lazuriUrl: StateFlow<String> = _lazuriUrl.asStateFlow()

    private val _lazuriApiKey = MutableStateFlow("")
    val lazuriApiKey: StateFlow<String> = _lazuriApiKey.asStateFlow()

    private val _lazuriStatus = MutableStateFlow(LazuriStatus.DISCONNECTED)
    val lazuriStatus: StateFlow<LazuriStatus> = _lazuriStatus.asStateFlow()

    private val _lazuriError = MutableStateFlow<String?>(null)
    val lazuriError: StateFlow<String?> = _lazuriError.asStateFlow()

    private val _announceCalls = MutableStateFlow(true)
    val announceCalls: StateFlow<Boolean> = _announceCalls.asStateFlow()


    private val _mapsProvider = MutableStateFlow("auto")
    val mapsProvider: StateFlow<String> = _mapsProvider.asStateFlow()

    enum class LazuriStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    init {
        loadPreferences()
        loadVoiceProfile()
        loadGmail()
        modelManager.checkState()
    }


    private fun loadPreferences() {
        viewModelScope.launch {
            _apiKey.value = prefDao.get("groq_api_key") ?: ""
            catalog.refresh()
            _model.value = catalog.model(com.friday.ai.core.GroqModels.Role.CHAT)
            _models.value = GroqModels.choicesForChat(catalog.available())
            _wakeWordEnabled.value = prefDao.get("wake_word_enabled") == "true"
            _isCalibrated.value = prefDao.get(VoiceCalibrator.PREF_WAKE) != null
            _lazuriUrl.value = prefDao.get("lazuri_base_url") ?: ""
            _lazuriApiKey.value = prefDao.get("lazuri_api_key") ?: ""
            _lazuriStatus.value = if (memory.isLazuriConfigured()) LazuriStatus.CONNECTED else LazuriStatus.DISCONNECTED
            _mapsProvider.value = prefDao.get("maps_provider") ?: "auto"
            _announceCalls.value = prefDao.get(FridayNotificationListener.PREF_ANNOUNCE_CALLS) != "false"
        }
    }

    fun onAnnounceCallsChange(on: Boolean) {
        _announceCalls.value = on
        viewModelScope.launch {
            prefDao.set(UserPreferenceEntity(FridayNotificationListener.PREF_ANNOUNCE_CALLS, on.toString()))
        }
    }

    fun onMapsProviderChange(provider: String) {
        _mapsProvider.value = provider
        viewModelScope.launch {
            prefDao.set(UserPreferenceEntity("maps_provider", provider))
        }
    }

    fun onLazuriUrlChange(url: String) {
        _lazuriUrl.value = url
        _lazuriStatus.value = LazuriStatus.DISCONNECTED
    }

    fun onLazuriApiKeyChange(key: String) {
        _lazuriApiKey.value = key
        _lazuriStatus.value = LazuriStatus.DISCONNECTED
    }

    fun connectToLazuri() {
        viewModelScope.launch {
            _lazuriStatus.value = LazuriStatus.CONNECTING
            _lazuriError.value = null

            val url = _lazuriUrl.value.trim()
            val key = _lazuriApiKey.value.trim()

            if (url.isBlank() || key.isBlank()) {
                _lazuriStatus.value = LazuriStatus.ERROR
                _lazuriError.value = "Enter both server address and API key"
                return@launch
            }

            val reachable = lazuriApi.checkHealth(url)
            if (!reachable) {
                _lazuriStatus.value = LazuriStatus.ERROR
                _lazuriError.value = "Can't reach Lazuri at $url"
                return@launch
            }

            val result = memory.registerWithLazuri(url, key)
            result.fold(
                onSuccess = { _lazuriStatus.value = LazuriStatus.CONNECTED },
                onFailure = { e ->
                    _lazuriStatus.value = LazuriStatus.ERROR
                    _lazuriError.value = e.message ?: "Registration failed"
                }
            )
        }
    }

    fun disconnectLazuri() {
        viewModelScope.launch {
            prefDao.set(UserPreferenceEntity("lazuri_enabled", "false"))
            _lazuriStatus.value = LazuriStatus.DISCONNECTED
        }
    }

    fun onApiKeyChange(key: String) {
        _apiKey.value = key
        _isSaved.value = false
    }

    fun onModelChange(model: String) {
        _model.value = model
        _isSaved.value = false
    }

    fun onWakeWordToggle(enabled: Boolean) {
        _wakeWordEnabled.value = enabled
        viewModelScope.launch {
            // Saved first: the service reads it when it starts.
            prefDao.set(UserPreferenceEntity("wake_word_enabled", enabled.toString()))
            if (enabled) {
                FridayWakeWordService.start(appContext)
            } else {
                FridayWakeWordService.stop(appContext)
            }
        }
    }

    fun startCalibration() {
        val calibrator = VoiceCalibrator()
        calibrator.listener = object : VoiceCalibrator.Listener {
            override fun onPhase(phase: VoiceCalibrator.Phase) {
                _calibrationPhase.value = phase
            }
            override fun onEnergyUpdate(energy: Double) {
                _calibrationEnergy.value = energy
            }
        }

        viewModelScope.launch {
            _calibrationPhase.value = VoiceCalibrator.Phase.MEASURING_SILENCE
            val result = calibrator.calibrate()
            _calibrationResult.value = result

            prefDao.set(UserPreferenceEntity(VoiceCalibrator.PREF_WAKE, result.wakeWordThreshold.toString()))
            prefDao.set(UserPreferenceEntity(VoiceCalibrator.PREF_WHISPER, result.whisperThreshold.toString()))
            prefDao.set(UserPreferenceEntity("ambient_noise", result.ambientNoise.toString()))
            prefDao.set(UserPreferenceEntity("speech_energy", result.speechEnergy.toString()))

            _isCalibrated.value = true
            _calibrationPhase.value = null

            if (_wakeWordEnabled.value) {
                FridayWakeWordService.stop(appContext)
                FridayWakeWordService.start(appContext)
            }
        }
    }

    fun downloadVoiceModel() {
        viewModelScope.launch {
            modelManager.downloadModel()
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            prefDao.set(UserPreferenceEntity("groq_api_key", _apiKey.value))
            prefDao.set(UserPreferenceEntity("groq_model", _model.value))
            _isSaved.value = true
        }
    }
}
