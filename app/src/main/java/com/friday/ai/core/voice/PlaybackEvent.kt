package com.friday.ai.core.voice

enum class VoiceEngine { EDGE, SYSTEM, CLOUD_ASR, LOCAL_ASR }

enum class PlaybackPhase { QUEUED, SYNTHESIZING, READY, PLAYING, WAITING, FINISHED, CANCELLED, FAILED }

data class PlaybackEvent(val phase: PlaybackPhase, val engine: VoiceEngine)
