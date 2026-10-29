package com.friday.ai.core.voice

import com.friday.ai.core.VoiceTurn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceStage { IDLE, GREETING, LISTENING, TRANSCRIBING, THINKING, PLAYBACK, FINISHED, CANCELLED, FAILED }
enum class OwnerCheck { NOT_CHECKED, CHECKING, ACCEPTED, REJECTED }
enum class VoiceIssue { NO_SPEECH, OWNER_REJECTED, NETWORK, TIMEOUT, SERVICE, AUTH, CONFIG, NO_KEY, CAPTURE, PLAYBACK }

data class VoiceCounts(
    val accepted: Int = 0,
    val rejected: Int = 0,
    val cancelled: Int = 0,
    val failures: Int = 0,
    val fallbacks: Int = 0,
    val restarts: Int = 0
)

data class VoiceDiagnosticSnapshot(
    val turn: Int = 0,
    val stage: VoiceStage = VoiceStage.IDLE,
    val asrEngine: VoiceEngine? = null,
    val owner: OwnerCheck = OwnerCheck.NOT_CHECKED,
    val playback: PlaybackEvent? = null,
    val issue: VoiceIssue? = null,
    val counts: VoiceCounts = VoiceCounts(),
    val durationsMs: Map<VoiceStage, Long> = emptyMap(),
    val ownerCheckMs: Long? = null
)

/** Bounded metadata only. No API accepts audio, transcripts, message text or credentials. */
class VoiceDiagnostics(private val clock: () -> Long) {
    private val mutable = MutableStateFlow(VoiceDiagnosticSnapshot())
    val state = mutable.asStateFlow()
    private var stageAt = 0L
    private var ownerAt = 0L

    @Synchronized
    fun begin(turn: Int) {
        mutable.value = VoiceDiagnosticSnapshot(turn = turn, stage = VoiceStage.GREETING, counts = mutable.value.counts)
        stageAt = clock()
    }

    @Synchronized
    fun stage(turn: Int, stage: VoiceStage) {
        val previous = mutable.value
        if (previous.turn != turn || previous.stage == stage || previous.stage == VoiceStage.CANCELLED) return
        val elapsed = (clock() - stageAt).coerceAtLeast(0)
        val duration = previous.durationsMs.getOrDefault(previous.stage, 0) + elapsed
        val times = previous.durationsMs + (previous.stage to duration)
        mutable.value = previous.copy(stage = stage, durationsMs = times)
        stageAt = clock()
    }

    @Synchronized
    fun owner(turn: Int, result: OwnerCheck) {
        if (!accepts(turn)) return
        if (result == OwnerCheck.CHECKING) ownerAt = clock()
        val elapsed = if (result == OwnerCheck.ACCEPTED || result == OwnerCheck.REJECTED) clock() - ownerAt else null
        mutable.value = mutable.value.copy(owner = result, ownerCheckMs = elapsed?.coerceAtLeast(0))
    }

    @Synchronized
    fun heard(turn: Int, result: VoiceTurn.Outcome) {
        val previous = mutable.value
        if (!accepts(turn)) return
        mutable.value = when (result) {
            is VoiceTurn.Outcome.Heard -> previous.copy(
                asrEngine = if (result.origin == VoiceTurn.Origin.CLOUD) {
                    VoiceEngine.CLOUD_ASR
                } else {
                    VoiceEngine.LOCAL_ASR
                },
                issue = result.degradedBy?.let { VoiceIssue.valueOf(it.name) },
                counts = previous.counts.copy(
                    accepted = increment(previous.counts.accepted),
                    fallbacks = if (result.origin == VoiceTurn.Origin.LOCAL) {
                        increment(previous.counts.fallbacks)
                    } else {
                        previous.counts.fallbacks
                    }
                )
            )
            is VoiceTurn.Outcome.Unavailable -> previous.copy(
                issue = VoiceIssue.valueOf(result.reason.name),
                counts = previous.counts.copy(failures = increment(previous.counts.failures))
            )
            VoiceTurn.Outcome.Stranger -> previous.copy(
                issue = VoiceIssue.OWNER_REJECTED,
                counts = previous.counts.copy(rejected = increment(previous.counts.rejected))
            )
            VoiceTurn.Outcome.Nothing -> previous.copy(issue = VoiceIssue.NO_SPEECH)
        }
    }

    @Synchronized
    fun playback(turn: Int, event: PlaybackEvent) {
        val previous = mutable.value
        if (!accepts(turn)) return
        // Synthesis of the next sentence can finish while the current sentence is playing.
        if (previous.playback?.phase == PlaybackPhase.PLAYING && event.phase in preparing) return
        mutable.value = previous.copy(playback = event)
        if (event.engine == VoiceEngine.SYSTEM && previous.playback?.engine != VoiceEngine.SYSTEM) {
            val counts = previous.counts.copy(fallbacks = increment(previous.counts.fallbacks))
            mutable.value = mutable.value.copy(counts = counts)
        }
        when (event.phase) {
            PlaybackPhase.PLAYING -> stage(turn, VoiceStage.PLAYBACK)
            PlaybackPhase.WAITING -> stage(turn, VoiceStage.THINKING)
            PlaybackPhase.FAILED -> {
                mutable.value = mutable.value.copy(
                    issue = VoiceIssue.PLAYBACK,
                    counts = previous.counts.copy(failures = increment(previous.counts.failures))
                )
                stage(turn, VoiceStage.FAILED)
            }
            else -> Unit
        }
    }

    @Synchronized
    fun cancel() {
        val previous = mutable.value
        if (previous.stage in terminal) return
        stage(previous.turn, VoiceStage.CANCELLED)
        mutable.value = mutable.value.copy(
            playback = previous.playback?.copy(phase = PlaybackPhase.CANCELLED),
            counts = previous.counts.copy(cancelled = increment(previous.counts.cancelled))
        )
    }

    @Synchronized
    fun reset() {
        mutable.value = VoiceDiagnosticSnapshot()
        stageAt = clock()
        ownerAt = 0
    }

    @Synchronized
    fun restarted() {
        val counts = mutable.value.counts.copy(restarts = increment(mutable.value.counts.restarts))
        mutable.value = mutable.value.copy(counts = counts)
    }

    private fun increment(value: Int) = (value + 1).coerceAtMost(MAX_COUNT)

    private fun accepts(turn: Int) = mutable.value.turn == turn && mutable.value.stage != VoiceStage.CANCELLED

    private companion object {
        const val MAX_COUNT = 1_000_000
        val preparing = setOf(PlaybackPhase.QUEUED, PlaybackPhase.SYNTHESIZING, PlaybackPhase.READY)
        val terminal = setOf(VoiceStage.IDLE, VoiceStage.FINISHED, VoiceStage.CANCELLED, VoiceStage.FAILED)
    }
}
