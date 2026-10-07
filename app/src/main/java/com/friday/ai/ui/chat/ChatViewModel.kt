package com.friday.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.FileAnalyzer
import com.friday.ai.core.VoiceInputManager
import com.friday.ai.core.CorrectionDetector
import com.friday.ai.data.local.dao.ChatSessionSummary
import com.friday.ai.service.FridayMemory
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.ChatUiState
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.domain.usecase.AnalyzeContentUseCase
import com.friday.ai.domain.usecase.GetChatHistoryUseCase
import com.friday.ai.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val sendMessage: SendMessageUseCase,
    private val getChatHistory: GetChatHistoryUseCase,
    private val analyzeContent: AnalyzeContentUseCase,
    private val voiceInput: VoiceInputManager,
    private val commands: CommandExecutor,
    private val memory: FridayMemory
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    var onAnalyzeScreenRequested: (() -> Unit)? = null
    var onAnalyzeFileRequested: ((String?) -> Unit)? = null

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /** Past conversations for the history drawer. */
    val sessions: StateFlow<List<ChatSessionSummary>> = getChatHistory.sessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        observeHistory()
        observeVoiceInput()
        resumeActiveSession()
    }

    /**
     * Opens whatever conversation is currently active — the same one the
     * voice assistant is using — so talking to Friday and then opening the
     * app lands you in the same thread rather than a blank screen.
     */
    private fun resumeActiveSession() {
        viewModelScope.launch {
            _currentSessionId.value = memory.getOrCreateSessionId()
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            _currentSessionId
                .filterNotNull()
                .flatMapLatest { sessionId -> getChatHistory.session(sessionId) }
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }
    }

    /** Starts a fresh conversation, leaving the previous one in history. */
    fun onNewChat() {
        viewModelScope.launch {
            _uiState.update { it.copy(messages = emptyList(), error = null) }
            _currentSessionId.value = memory.startNewSession()
        }
    }

    /** Reopens a past conversation and makes it active for voice too. */
    fun onOpenSession(sessionId: String) {
        if (sessionId == _currentSessionId.value) return
        viewModelScope.launch {
            memory.resumeSession(sessionId)
            _currentSessionId.value = sessionId
        }
    }

    fun onDeleteSession(sessionId: String) {
        viewModelScope.launch {
            getChatHistory.deleteSession(sessionId)
            if (sessionId == _currentSessionId.value) {
                _currentSessionId.value = memory.startNewSession()
            }
        }
    }

    private fun observeVoiceInput() {
        viewModelScope.launch {
            voiceInput.recognizedText.filterNotNull().collect { text ->
                _inputText.value = text
                voiceInput.clearResult()
            }
        }
        viewModelScope.launch {
            voiceInput.isListening.collect { listening ->
                _uiState.update { it.copy(isVoiceListening = listening) }
            }
        }
        viewModelScope.launch {
            voiceInput.error.filterNotNull().collect { err ->
                _uiState.update { it.copy(error = err) }
            }
        }
    }

    /**
     * Saves a message into the conversation currently open, so every turn —
     * typed, spoken or a command result — lands in the same thread.
     */
    private suspend fun persist(content: String, role: MessageRole) {
        val sessionId = _currentSessionId.value ?: memory.getOrCreateSessionId()
            .also { _currentSessionId.value = it }
        sendMessage.saveMessage(Message(content = content, role = role, sessionId = sessionId))
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun onSend() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        _inputText.value = ""

        viewModelScope.launch {
            // An answer to "Отправить?" is not a new request.
            val confirmed = commands.answerPending(text, isRussian(text))
            if (confirmed != null) {
                persist(text, MessageRole.USER)
                persist(confirmed, MessageRole.ASSISTANT)
            } else {
                dispatch(text)
            }
        }
    }

    /**
     * Carries out what was typed. Commands run through the same executor as
     * the voice service; plain conversation streams from the model here,
     * where the reply can be drawn as it arrives.
     */
    private fun dispatch(text: String) {
        val command = commands.route(text)
        if (command is CommandResult.ChatMessage) {
            handleChatMessage(command.text)
            return
        }
        viewModelScope.launch {
            persist(text, MessageRole.USER)
            when (val outcome = commands.execute(command, isRussian(text))) {
                is CommandExecutor.Outcome.Reply -> persist(outcome.text, MessageRole.ASSISTANT)
                is CommandExecutor.Outcome.Conversation -> streamReply(outcome.text)
                CommandExecutor.Outcome.NeedsScreen -> onAnalyzeScreenRequested?.invoke()
                is CommandExecutor.Outcome.NeedsFile -> onAnalyzeFileRequested?.invoke(outcome.hint)
            }
        }
    }

    private fun isRussian(text: String): Boolean =
        text.any { it in 'а'..'я' || it in 'А'..'Я' }

    fun onScreenCaptured(imageBase64: String) {
        streamAssistantResponse { analyzeContent.analyzeImage(imageBase64) }
    }

    /**
     * A page from the notebook: the question (or a note that there is none)
     * goes into the conversation, the page to the vision model.
     */
    fun onNotebook(page: com.friday.ai.ui.notebook.NotebookInbox.Page) {
        viewModelScope.launch {
            persist("[Блокнот] " + page.question.ifBlank { "что здесь написано?" }, MessageRole.USER)
            streamAssistantResponse { analyzeContent.analyzeImage(page.imageBase64, notebookPrompt(page.question)) }
        }
    }

    private fun notebookPrompt(question: String): String {
        val task = if (question.isBlank()) {
            "If it asks something or is a problem, answer or solve it; otherwise say what it says. "
        } else {
            "Then do what the user asks about it: \"$question\". "
        }
        return "The image is a page the user wrote or drew by hand: text, a formula, a sum or a sketch. " +
            "Read it carefully first. " + task +
            "Show the working for maths. Answer in the language of the writing or the question."
    }

    fun onFileSelected(fileContent: FileAnalyzer.FileContent) {
        if (fileContent.isImage && fileContent.base64Content != null) {
            streamAssistantResponse {
                analyzeContent.analyzeImage(fileContent.base64Content, "Analyze this image (${fileContent.name}):")
            }
        } else if (fileContent.textContent != null) {
            streamAssistantResponse {
                analyzeContent.analyzeText(fileContent.textContent, "Analyze this file (${fileContent.name}):")
            }
        } else {
            _uiState.update { it.copy(error = "Cannot read file content") }
        }
    }

    private fun handleChatMessage(text: String) {
        viewModelScope.launch {
            persist(text, MessageRole.USER)
            streamReply(text)
        }
    }

    private suspend fun streamReply(text: String) {
        // Catch a correction before the answer, so what the user just put
        // right is stored even if the reply goes wrong.
        CorrectionDetector.detect(
            text,
            assistantSaidSomething = _uiState.value.messages.any { it.role == MessageRole.ASSISTANT }
        )?.let { memory.recordCorrection(it) }

        streamAssistantResponseInline {
            sendMessage(prompt = text, history = _uiState.value.messages, mode = _uiState.value.currentMode)
        }
    }

    private fun streamAssistantResponse(flowProvider: suspend () -> kotlinx.coroutines.flow.Flow<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val assistantId = java.util.UUID.randomUUID().toString()
            val responseBuilder = StringBuilder()
            var hasContent = false

            try {
                flowProvider()
                    .catch { e ->
                        _uiState.update { it.copy(isLoading = false, error = e.message) }
                        removeStreamingMessage(assistantId)
                    }
                    .collect { token ->
                        responseBuilder.append(token)
                        hasContent = true
                        updateStreamingMessage(assistantId, responseBuilder.toString())
                    }

                if (hasContent) {
                    removeStreamingMessage(assistantId)
                    persist(responseBuilder.toString(), MessageRole.ASSISTANT)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
                removeStreamingMessage(assistantId)
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun streamAssistantResponseInline(flowProvider: suspend () -> kotlinx.coroutines.flow.Flow<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val assistantId = java.util.UUID.randomUUID().toString()
            val responseBuilder = StringBuilder()
            var hasContent = false

            try {
                flowProvider()
                    .catch { e ->
                        _uiState.update { it.copy(isLoading = false, error = e.message) }
                        removeStreamingMessage(assistantId)
                    }
                    .collect { token ->
                        responseBuilder.append(token)
                        hasContent = true
                        updateStreamingMessage(assistantId, responseBuilder.toString())
                    }

                if (hasContent) {
                    removeStreamingMessage(assistantId)
                    persist(responseBuilder.toString(), MessageRole.ASSISTANT)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
                removeStreamingMessage(assistantId)
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun updateStreamingMessage(id: String, content: String) {
        _uiState.update { state ->
            val msgs = state.messages.toMutableList()
            val existingIdx = msgs.indexOfFirst { it.id == id }
            val streamMsg = Message(id = id, content = content, role = MessageRole.ASSISTANT, isStreaming = true)
            if (existingIdx >= 0) {
                msgs[existingIdx] = streamMsg
            } else {
                msgs.add(streamMsg)
            }
            state.copy(messages = msgs)
        }
    }

    private fun removeStreamingMessage(id: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.filter { it.id != id || !it.isStreaming })
        }
    }

    fun onModeChange(mode: AssistantMode) {
        _uiState.update { it.copy(currentMode = mode) }
    }

    fun onMicClick() {
        if (voiceInput.isListening.value) voiceInput.stopListening()
        else voiceInput.startListening()
    }


    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        voiceInput.destroy()
    }
}
