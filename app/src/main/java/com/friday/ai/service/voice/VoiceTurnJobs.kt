package com.friday.ai.service.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Cancels and joins the previous exchange before another can acquire its devices. */
internal class VoiceTurnJobs(
    private val scope: CoroutineScope,
    private val interrupt: () -> Unit,
    private val blocked: () -> Unit
) {
    private val transitions = Mutex()
    @Volatile private var owner: Job? = null
    @Volatile private var generation = 0
    @Volatile private var destroyed = false

    fun current(id: Int): Boolean = !destroyed && id == generation && owner?.isActive == true

    fun replace(work: suspend (Int) -> Unit) {
        scope.launch {
            transitions.withLock {
                if (destroyed) return@withLock
                val id = ++generation
                if (!cancelPrevious()) {
                    blocked()
                    return@withLock
                }
                val job = SupervisorJob(scope.coroutineContext[Job])
                owner = job
                scope.launch(job) { work(id) }
            }
        }
    }

    fun launch(id: Int, work: suspend () -> Unit) {
        val job = owner ?: return
        if (current(id)) scope.launch(job) { if (current(id)) work() }
    }

    fun close(after: () -> Unit) {
        scope.launch {
            transitions.withLock {
                generation++
                cancelPrevious(after)
            }
        }
    }

    fun destroy(): Job? {
        destroyed = true
        generation++
        owner?.cancel()
        interrupt()
        return owner
    }

    private suspend fun cancelPrevious(afterInterrupt: () -> Unit = {}): Boolean {
        val previous = owner
        previous?.cancel()
        interrupt()
        afterInterrupt()
        val ended = previous == null || withTimeoutOrNull(RELEASE_TIMEOUT_MS) { previous.join() } != null
        if (ended) owner = null
        return ended
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 2_000L
    }
}
