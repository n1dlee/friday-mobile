# Jarvis-Test: voice reliability upgrades

Date: 2026-10-29. Branch: `Jarvis-Test`. Base: `292b5e56161666571881aa4917fc45c53d87a955`.

## Implemented scope

The first three phases of the supplied Friday/Jarvis plan are implemented together. Existing voice panels, lock-screen privacy, speech endpointing, command routing and action confirmations remain the underlying implementation.

### Turn cancellation

`VoiceTurnJobs` serializes replacement and close. It invalidates the old generation, cancels its child work, interrupts blocking command capture and waits for cleanup before starting a replacement. After a two-second cleanup timeout it refuses to start another recorder; a later invocation can try again after cleanup finishes. The controller itself outlives each exchange.

Streaming answers now participate in wake-word interruption. They expose playback events to the conversation and check ownership before updating the panel. Closing the panel aborts/discards recording; the separate chat-microphone stop action still finishes and transcribes capture. Old playback callbacks cannot reopen capture. The overlay also invalidates queued updates and timers from an earlier generation.

`SpeechPipeline` owns synthesis and playback for one speech session. Cancellation does not invoke another synthesis fallback. MediaPlayer cleanup checks the owning speech session, and system TTS checks its utterance ID before stopping or completing playback. The service defers Vosk destruction until the active command's word follower/recognizer has released the shared model. The agent checks cancellation between tool calls. A completed external action is not undone or retried.

### Voice diagnostics

The existing diagnostics screen and share report now include a bounded, process-local snapshot: capture/transcription/answer stage, cloud/local ASR origin, owner-check result and duration, playback queue/synthesis/ready/start/finish/cancel/failure events, categorized failure, counters and completed stage durations.

These APIs accept no audio, transcript, contact, message or credential fields. Timings use a monotonic clock. A new turn clears transient playback state, and old turn events are ignored. Playback start means the engine reported a start or MediaPlayer accepted `start()`; it does not prove that a speaker was audible at the current volume or route.

### Recovery from the same capture

`AsrRecovery` can use locally decoded words after eligible network, timeout, service or missing-key failures. It waits for the owner verdict before returning a result. Authentication/configuration failures remain explicit. Cancellation returns no fallback and starts no new request. The captured wake-word lead-in is also fed to the local decoder.

Recovered cloud-failure transcripts are marked with origin and failure provenance. They may execute only the existing supported offline phone commands; messages and unrestricted agent input are refused, and a pending phone-send confirmation is dropped. The existing local short-word fallback for a successful but empty Whisper response is retained, including its ordinary confirmation behavior. A missing key permits local capture only when a local model is available. The offline recognizer reuses the wake model when one is already loaded.

## Verification

The integration gate is:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat testDebugUnitTest detekt assembleDebug assembleRelease verifyReleaseKeepRules --continue --console=plain
```

The final integration run passed: 1,033 tests passed, 19 skipped, zero failures/errors; Detekt, Debug APK, Release APK and `verifyReleaseKeepRules` passed. R8 verified 16 name-kept classes. The generated APKs are under `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

Focused regressions cover held generation and capture cancellation, cleanup-before-replacement, stalled cleanup, late callbacks, queued synthesis and system TTS cancellation, old utterance IDs, eligible/ineligible ASR failures, owner rejection and cancellation during owner checking, missing keys, unsupported degraded requests, cancellation between agent tools and report privacy. A Compose test checks diagnostics labels at 1.6× font scale; its rendered screenshot was inspected.

`aislop 0.18.1` was run. It reports Kotlin as unsupported, zero supported files and no score; this is not a clean Kotlin quality score. Detekt and Gradle checks provide the code gates. No rule configuration or baseline was changed.

## Device validation still required

ADB reported no connected device. The following checks have not been claimed as passed: actual microphone handover on Samsung, interruption during real Edge/system TTS output, speaker/headset/Bluetooth routing, audio focus after cancellation, keyguard/AOD transitions, live network loss and One UI lifecycle behavior. Automated tests cannot establish acoustic latency, false wake rates or battery consumption.

Natural interruption without the wake word (phase 4) is not enabled. It requires shared microphone capture, echo rejection and device measurements before an opt-in release. No desktop bridge, model subscription migration, new action policy or main-branch publication is part of this change.
