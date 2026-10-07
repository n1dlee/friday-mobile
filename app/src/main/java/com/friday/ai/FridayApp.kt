package com.friday.ai

import android.app.Application
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.di.appModule
import com.friday.ai.service.MorningBriefWorker
import com.friday.ai.service.SessionSummarizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class FridayApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@FridayApp)
            modules(appModule)
        }
        prefillDefaults()
        scheduleMorningBrief()
        wireSessionSummaries()
        learnOwnerFromDevice()
    }

    /**
     * Reads the phone's own contact card so "как меня зовут?" is answerable
     * without the user ever having said it. Runs on every start because
     * contacts permission may only have been granted after the first one.
     */
    private fun learnOwnerFromDevice() {
        appScope.launch {
            val owner = com.friday.ai.core.OwnerProfile.read(this@FridayApp) ?: return@launch
            val memory: com.friday.ai.service.FridayMemory =
                org.koin.java.KoinJavaComponent.get(com.friday.ai.service.FridayMemory::class.java)
            owner.givenName?.let { memory.saveOrUpdate("fact", "name", it, "device") }
            owner.displayName
                ?.takeIf { it != owner.givenName }
                ?.let { memory.saveOrUpdate("fact", "full_name", it, "device") }
        }
    }

    /** The briefing only counts as proactive if it arrives unprompted. */
    private fun scheduleMorningBrief() {
        appScope.launch {
            val prefDao: UserPreferenceDao =
                org.koin.java.KoinJavaComponent.get(UserPreferenceDao::class.java)
            if (prefDao.get(MorningBriefWorker.PREF_ENABLED) == "false") return@launch
            val hour = prefDao.get(MorningBriefWorker.PREF_HOUR)?.toIntOrNull()
                ?: MorningBriefWorker.DEFAULT_HOUR
            MorningBriefWorker.schedule(this@FridayApp, hour)
        }
    }

    /** A finished conversation gets a written summary for the dashboard. */
    private fun wireSessionSummaries() {
        val memory: com.friday.ai.service.FridayMemory =
            org.koin.java.KoinJavaComponent.get(com.friday.ai.service.FridayMemory::class.java)
        val summarizer: SessionSummarizer =
            org.koin.java.KoinJavaComponent.get(SessionSummarizer::class.java)
        memory.onSessionClosed = { sessionId -> summarizer.summarizeInBackground(sessionId) }
    }

    private fun prefillDefaults() {
        appScope.launch {
            // What works right now, before the first request needs to know.
            org.koin.java.KoinJavaComponent.get<com.friday.ai.core.DeviceContext>(
                com.friday.ai.core.DeviceContext::class.java
            ).refresh()
            // No key is built in: anything compiled into an APK can be read
            // back out of it. The user enters their own in Settings.
            // Groq retires models; picking from the live list every start
            // replaces a retired one before the user ever hits it.
            org.koin.java.KoinJavaComponent.get<com.friday.ai.service.ModelCatalog>(
                com.friday.ai.service.ModelCatalog::class.java
            ).refresh()
        }
    }
}
