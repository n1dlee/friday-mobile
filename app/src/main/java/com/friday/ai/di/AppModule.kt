package com.friday.ai.di

import androidx.room.Room
import com.friday.ai.core.AppLauncher
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.BriefComposer
import com.friday.ai.core.CalendarWriter
import com.friday.ai.core.DeviceController
import com.friday.ai.core.ContactsReader
import com.friday.ai.core.FileAnalyzer
import com.friday.ai.core.ScreenAnalyzer
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.core.VoiceInputManager
import com.friday.ai.data.local.FridayDatabase
import com.friday.ai.data.local.Migrations
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.LazuriApiService
import com.friday.ai.data.remote.NearbyPlacesService
import com.friday.ai.data.remote.WeatherApiService
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.ProactiveBriefService
import com.friday.ai.service.SessionSummarizer
import com.friday.ai.data.repository.AssistantRepositoryImpl
import com.friday.ai.domain.repository.AssistantRepository
import com.friday.ai.domain.usecase.AnalyzeContentUseCase
import com.friday.ai.domain.usecase.GetChatHistoryUseCase
import com.friday.ai.domain.usecase.SendMessageUseCase
import com.friday.ai.ui.chat.ChatViewModel
import com.friday.ai.ui.dashboard.LazuriDashboardViewModel
import com.friday.ai.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {

    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            FridayDatabase::class.java,
            "friday_db"
        )
            // No destructive fallback: a missing migration must fail loudly
            // rather than quietly wipe the user's memory and history.
            .addMigrations(*Migrations.ALL)
            .build()
    }
    single { get<FridayDatabase>().chatMessageDao() }
    // API keys are sealed with Android Keystore before they reach the table.
    single {
        com.friday.ai.data.local.secure.SecurePreferenceDao(
            get<FridayDatabase>().userPreferenceDao(), com.friday.ai.data.local.secure.KeystoreCipher()
        )
    } bind com.friday.ai.data.local.dao.UserPreferenceDao::class
    single { get<FridayDatabase>().memoryDao() }
    single { get<FridayDatabase>().interactionDao() }
    single { get<FridayDatabase>().notificationDao() }
    single { get<FridayDatabase>().sessionSummaryDao() }
    single { get<FridayDatabase>().errandDao() }

    // Network
    single { GroqApiService.create() }
    single { LazuriApiService.create() }
    single { WeatherApiService.create() }
    single { NearbyPlacesService.create() }

    // Core
    // Tool-carrying prompts get the phone's context: country, installed apps.
    single { SystemPromptBuilder(deviceContext = { get<com.friday.ai.core.DeviceContext>().describe() }) }
    single { VoiceInputManager(androidContext(), get(), get()) }
    single { CommandRouter() }
    single { ContactsReader(androidContext()) }
    single { CalendarWriter(androidContext()) }
    single { DeviceController(androidContext()) }
    single { com.friday.ai.core.MediaLauncher(androidContext()) }
    single { com.friday.ai.core.MediaSessions(androidContext()) }
    single { com.friday.ai.core.PlaybackStarter(androidContext(), get(), get(), get()) }
    single { com.friday.ai.core.MediaControls(androidContext(), get(), get()) }
    single { com.friday.ai.service.mail.GmailAuth(androidContext()) }
    single { com.friday.ai.data.remote.GmailApi.create() }
    single { com.friday.ai.service.mail.MailRetelling(get(), get(), get()) }
    single { com.friday.ai.service.mail.GmailConnector(get(), get(), get()) }
    single { com.friday.ai.service.mail.MailAssistant(get(), get(), get(), get(), get()) }

    // Commands: one implementation shared by the chat and the voice service.
    single { com.friday.ai.core.AlarmSetter(androidContext()) }
    single {
        com.friday.ai.core.DeviceContext(
            androidContext(), com.friday.ai.core.capabilities.CapabilityProbe(androidContext(), get())
        )
    }
    single { com.friday.ai.core.people.PeopleDirectory(get(), get()) }
    single { com.friday.ai.core.people.Messenger(androidContext(), get(), get(), get()) }
    single { com.friday.ai.core.MediaSearch(androidContext(), get(), get(), get()) }
    single { com.friday.ai.service.WeatherHere(androidContext(), get(), get()) }
    single { com.friday.ai.core.people.Caller(androidContext(), get(), get(), get(), get(), get()) }
    single { com.friday.ai.service.messages.MessengerInbox() }
    single { com.friday.ai.service.messages.Announcer() }
    single { com.friday.ai.service.messages.MessageAssistant(androidContext(), get(), get()) }
    single { com.friday.ai.command.PhoneActions(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single {
        com.friday.ai.command.PlannerActions(get(), get(), get(), watchErrands = {
            com.friday.ai.service.ErrandWatchWorker.schedule(androidContext())
        })
    }
    single { com.friday.ai.service.WebResearch(get(), get(), get()) }
    single { com.friday.ai.command.InfoActions(get(), get(), get(), get(), get()) }
    single {
        val writes = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val device = get<com.friday.ai.core.DeviceContext>()
        com.friday.ai.agent.LearnedCommands(
            get(), writes,
            available = { tool ->
                com.friday.ai.core.capabilities.ToolRequirements.met(tool, device.capabilities.value)
            }
        ).apply { load() }
    }
    single {
        com.friday.ai.command.CommandExecutor(get(), get(), get(), get(), get(), messages = get(), learned = get())
    }
    single {
        val device = get<com.friday.ai.core.DeviceContext>()
        com.friday.ai.agent.FridayAgent(get(), get(), learned = get(), capabilities = { device.refresh() })
    }
    single { ProactiveBriefService(androidContext(), get(), get(), get()) }
    single { com.friday.ai.service.ModelCatalog(get(), get()) }
    single { SessionSummarizer(get(), get(), get(), get(), get()) }
    single { AppLauncher(androidContext(), get()) }
    single { FridayMemory(get(), get(), get(), get(), get(), get()) }
    single { ScreenAnalyzer(androidContext()) }
    single { FileAnalyzer(androidContext()) }

    // Repository
    single<AssistantRepository> {
        AssistantRepositoryImpl(
            groqApi = get(),
            chatDao = get(),
            prefDao = get(),
            promptBuilder = get(),
            memory = get(),
            catalog = get(),
            agent = get()
        )
    }

    // Use Cases
    factory { SendMessageUseCase(repository = get()) }
    factory { GetChatHistoryUseCase(repository = get()) }
    factory { AnalyzeContentUseCase(repository = get()) }

    // ViewModels
    viewModel {
        ChatViewModel(
            sendMessage = get(),
            getChatHistory = get(),
            analyzeContent = get(),
            voiceInput = get(),
            commands = get(),
            memory = get()
        )
    }
    viewModel { com.friday.ai.ui.diagnostics.DiagnosticsViewModel(get()) }
    viewModel {
        LazuriDashboardViewModel(
            memoryDao = get(),
            chatDao = get(),
            summaryDao = get(),
            summarizer = get()
        )
    }
    viewModel {
        SettingsViewModel(
            prefDao = get(),
            appContext = androidContext(),
            gmailConnector = get(),
            catalog = get(),
            lazuriApi = get(),
            memory = get(),
            learned = get()
        )
    }
}
