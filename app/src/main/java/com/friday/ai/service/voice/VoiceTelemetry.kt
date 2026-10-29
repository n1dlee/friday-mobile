package com.friday.ai.service.voice

import android.os.SystemClock
import com.friday.ai.core.voice.VoiceDiagnostics

object VoiceTelemetry {
    val diagnostics = VoiceDiagnostics(SystemClock::elapsedRealtime)
}
