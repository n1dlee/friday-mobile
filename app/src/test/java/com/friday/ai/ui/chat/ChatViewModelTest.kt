package com.friday.ai.ui.chat

import com.friday.ai.command.CommandExecutor
import com.friday.ai.command.InfoActions
import com.friday.ai.command.PhoneActions
import com.friday.ai.command.PlannerActions
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.VoiceInputManager
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.mail.MailAssistant
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.domain.usecase.AnalyzeContentUseCase
import com.friday.ai.domain.usecase.GetChatHistoryUseCase
import com.friday.ai.domain.usecase.SendMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private companion object {
        const val ACTIVE_SESSION = "session-active"
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var getChatHistoryUseCase: GetChatHistoryUseCase
    private lateinit var analyzeContentUseCase: AnalyzeContentUseCase
    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var memory: FridayMemory
    private lateinit var router: CommandRouter
    private lateinit var phone: PhoneActions
    private lateinit var planner: PlannerActions
    private lateinit var info: InfoActions
    private lateinit var mail: MailAssistant
    private lateinit var commands: CommandExecutor
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sendMessageUseCase = mockk(relaxed = true)
        getChatHistoryUseCase = mockk(relaxed = true)
        analyzeContentUseCase = mockk(relaxed = true)
        voiceInputManager = mockk(relaxed = true)
        memory = mockk(relaxed = true)
        router = mockk(relaxed = true)
        phone = mockk(relaxed = true)
        planner = mockk(relaxed = true)
        info = mockk(relaxed = true)
        mail = mockk(relaxed = true)
        // Relaxed mocks answer "" for String?, which would read as a
        // confirmed send and swallow every message.
        coEvery { mail.answerPending(any(), any()) } returns null
        // The real executor, so these tests cover the chat and the command
        // path together; only the actions themselves are stubbed.
        commands = CommandExecutor(router, phone, planner, info, mail, io = testDispatcher)

        every { getChatHistoryUseCase() } returns emptyFlow()
        every { getChatHistoryUseCase.sessions() } returns flowOf(emptyList())
        every { getChatHistoryUseCase.session(any()) } returns emptyFlow()
        coEvery { memory.getOrCreateSessionId() } returns ACTIVE_SESSION
        coEvery { memory.startNewSession() } returns "new-session"
        every { voiceInputManager.recognizedText } returns MutableStateFlow(null)
        every { voiceInputManager.isListening } returns MutableStateFlow(false)
        every { voiceInputManager.error } returns MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ChatViewModel(
        sendMessage = sendMessageUseCase,
        getChatHistory = getChatHistoryUseCase,
        analyzeContent = analyzeContentUseCase,
        voiceInput = voiceInputManager,
        commands = commands,
        memory = memory
    )

    @Test
    fun `initial state has empty messages and no loading`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isLoading)
        assertEquals(null, state.error)
        assertEquals(AssistantMode.DEFAULT, state.currentMode)
    }

    @Test
    fun `onInputChange updates input text`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("Hello Friday")
        assertEquals("Hello Friday", viewModel.inputText.value)
    }

    @Test
    fun `onSend with blank text does nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("   ")
        viewModel.onSend()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `onSend routes chat message to Groq API`() = runTest {
        every { router.route(any()) } returns CommandResult.ChatMessage("Hello")
        coEvery { sendMessageUseCase(any(), any(), any()) } returns flowOf("Hi there!")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("Hello")
        viewModel.onSend()
        advanceUntilIdle()

        assertEquals("", viewModel.inputText.value)
        coVerify { sendMessageUseCase.saveMessage(match { it.role == MessageRole.USER }) }
        coVerify { sendMessageUseCase.saveMessage(match { it.role == MessageRole.ASSISTANT }) }
    }

    @Test
    fun `onSend routes open app command through the executor`() = runTest {
        every { router.route(any()) } returns CommandResult.OpenApp("камеру", "com.android.camera")
        coEvery { phone.run(any(), any()) } returns "Opening камеру"
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("открой камеру")
        viewModel.onSend()
        advanceUntilIdle()

        coVerify { phone.run(CommandResult.OpenApp("камеру", "com.android.camera"), any()) }
        coVerify {
            sendMessageUseCase.saveMessage(match { it.role == MessageRole.ASSISTANT && it.content == "Opening камеру" })
        }
    }

    @Test
    fun `onSend routes close app command through the executor`() = runTest {
        every { router.route(any()) } returns CommandResult.CloseApp("ютуб", "com.google.android.youtube")
        coEvery { phone.run(any(), any()) } returns "Closing ютуб"
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("закрой ютуб")
        viewModel.onSend()
        advanceUntilIdle()

        coVerify { phone.run(CommandResult.CloseApp("ютуб", "com.google.android.youtube"), any()) }
    }

    @Test
    fun `onModeChange updates current mode`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onModeChange(AssistantMode.CODING)
        assertEquals(AssistantMode.CODING, viewModel.uiState.value.currentMode)
    }

    @Test
    fun `onDismissError clears error state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDismissError()
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `observeHistory loads messages of the active session`() = runTest {
        val dbMessages = listOf(
            Message(content = "Hi", role = MessageRole.USER, sessionId = ACTIVE_SESSION),
            Message(content = "Hello!", role = MessageRole.ASSISTANT, sessionId = ACTIVE_SESSION)
        )
        every { getChatHistoryUseCase.session(ACTIVE_SESSION) } returns flowOf(dbMessages)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals(ACTIVE_SESSION, viewModel.currentSessionId.value)
    }

    @Test
    fun `opens the session the voice assistant is already using`() = runTest {
        // Talking to Friday and then opening the app should land in the same
        // thread, not a blank screen.
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(ACTIVE_SESSION, viewModel.currentSessionId.value)
    }

    @Test
    fun `new chat switches to a fresh session and clears the screen`() = runTest {
        every { getChatHistoryUseCase.session(ACTIVE_SESSION) } returns flowOf(
            listOf(Message(content = "old", role = MessageRole.USER, sessionId = ACTIVE_SESSION))
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.messages.size)

        viewModel.onNewChat()
        advanceUntilIdle()

        assertEquals("new-session", viewModel.currentSessionId.value)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun `opening a past session loads its messages and resumes it for voice`() = runTest {
        val past = "session-old"
        every { getChatHistoryUseCase.session(past) } returns flowOf(
            listOf(Message(content = "earlier", role = MessageRole.USER, sessionId = past))
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onOpenSession(past)
        advanceUntilIdle()

        assertEquals(past, viewModel.currentSessionId.value)
        assertEquals("earlier", viewModel.uiState.value.messages.single().content)
        coVerify { memory.resumeSession(past) }
    }

    @Test
    fun `deleting the active session starts a new one`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onDeleteSession(ACTIVE_SESSION)
        advanceUntilIdle()

        coVerify { getChatHistoryUseCase.deleteSession(ACTIVE_SESSION) }
        assertEquals("new-session", viewModel.currentSessionId.value)
    }

    @Test
    fun `saved messages are tagged with the active session`() = runTest {
        every { router.route(any()) } returns CommandResult.ChatMessage("Hello")
        coEvery { sendMessageUseCase(any(), any(), any()) } returns flowOf("Hi!")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("Hello")
        viewModel.onSend()
        advanceUntilIdle()

        coVerify { sendMessageUseCase.saveMessage(match { it.sessionId == ACTIVE_SESSION }) }
    }

    @Test
    fun `voice input text appears in input field`() = runTest {
        val voiceText = MutableStateFlow<String?>(null)
        every { voiceInputManager.recognizedText } returns voiceText

        viewModel = createViewModel()
        advanceUntilIdle()

        voiceText.value = "Hello from voice"
        advanceUntilIdle()

        assertEquals("Hello from voice", viewModel.inputText.value)
    }

    @Test
    fun `onMicClick starts voice listening`() = runTest {
        val listening = MutableStateFlow(false)
        every { voiceInputManager.isListening } returns listening

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onMicClick()
        verify { voiceInputManager.startListening() }
    }

    @Test
    fun `voice input is destroyed on cleanup`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val method = viewModel.javaClass.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)
        verify { voiceInputManager.destroy() }
    }

    @Test
    fun `analyze screen triggers callback`() = runTest {
        every { router.route(any()) } returns CommandResult.AnalyzeScreen
        var callbackTriggered = false

        viewModel = createViewModel()
        viewModel.onAnalyzeScreenRequested = { callbackTriggered = true }
        advanceUntilIdle()

        viewModel.onInputChange("проанализируй экран")
        viewModel.onSend()
        advanceUntilIdle()

        assertTrue(callbackTriggered)
    }

    @Test
    fun `analyze file triggers callback`() = runTest {
        every { router.route(any()) } returns CommandResult.AnalyzeFile("test.txt")
        var callbackFileHint: String? = null

        viewModel = createViewModel()
        viewModel.onAnalyzeFileRequested = { hint -> callbackFileHint = hint }
        advanceUntilIdle()

        viewModel.onInputChange("проанализируй файл test.txt")
        viewModel.onSend()
        advanceUntilIdle()

        assertEquals("test.txt", callbackFileHint)
    }

    @Test
    fun `onScreenCaptured streams analysis result`() = runTest {
        coEvery { analyzeContentUseCase.analyzeImage(any(), any()) } returns flowOf("This is a screenshot of...")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onScreenCaptured("base64data")
        advanceUntilIdle()

        coVerify { analyzeContentUseCase.analyzeImage("base64data", any()) }
        coVerify { sendMessageUseCase.saveMessage(match { it.role == MessageRole.ASSISTANT }) }
    }

    @Test
    fun `a failing command becomes a reply instead of an exception`() = runTest {
        // The voice loop used to die silently on this; the chat caught it.
        // With one executor both get the same, survivable behaviour.
        every { router.route(any()) } returns CommandResult.ToggleFlashlight
        coEvery { phone.run(any(), any()) } throws IllegalStateException("camera in use")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("включи фонарик")
        viewModel.onSend()
        advanceUntilIdle()

        coVerify {
            sendMessageUseCase.saveMessage(match {
                it.role == MessageRole.ASSISTANT && it.content.contains("camera in use")
            })
        }
    }

    @Test
    fun `an answer to a pending send is not routed as a new command`() = runTest {
        coEvery { mail.answerPending("да", true) } returns "Отправила."
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("да")
        viewModel.onSend()
        advanceUntilIdle()

        verify(exactly = 0) { router.route(any()) }
        coVerify { sendMessageUseCase.saveMessage(match { it.content == "Отправила." }) }
    }
}
