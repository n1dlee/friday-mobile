package com.friday.ai.core.voice

object VoiceDiagnosticReport {
    fun format(snapshot: VoiceDiagnosticSnapshot): String = buildString {
        appendLine("phase=${snapshot.stage}; asr=${snapshot.asrEngine ?: "none"}; owner=${snapshot.owner}")
        appendLine("playback=${snapshot.playback?.phase ?: "none"}; engine=${snapshot.playback?.engine ?: "none"}")
        appendLine("reason=${snapshot.issue ?: "none"}; ownerCheckMs=${snapshot.ownerCheckMs ?: "none"}")
        val counts = snapshot.counts
        appendLine(
            "accepted=${counts.accepted}; rejected=${counts.rejected}; cancelled=${counts.cancelled}; " +
                "failures=${counts.failures}; fallbacks=${counts.fallbacks}; restarts=${counts.restarts}"
        )
        snapshot.durationsMs.forEach { (stage, ms) -> appendLine("${stage.name}Ms=$ms") }
    }
}
