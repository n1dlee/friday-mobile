package com.friday.ai.core.capabilities

/**
 * Everything Friday can or can't do right now, in one snapshot.
 *
 * Availability is dynamic: a feature can be missing because of the
 * hardware, a permission, a login nobody made yet, or a voice model not yet
 * downloaded. One state answers all of it, and three things read it — the
 * system prompt (what to tell the model), the tool kits (what to offer it)
 * and the diagnostics screen (what to tell the user). If they each checked
 * for themselves they would disagree.
 *
 * Data only: [CapabilityProbe] does the system calls, [com.friday.ai.core.DeviceContext]
 * owns the current snapshot.
 */
data class FridayCapabilities(
    val device: Device = Device(),
    val permissions: Permissions = Permissions(),
    val integrations: Integrations = Integrations(),
    val voice: Voice = Voice(),
    val assistant: Assistant = Assistant(),
    val privileged: Privileged = Privileged(),
    /** Names of the owner's modes, for the model to run with run_mode. */
    val modes: List<String> = emptyList()
) {

    data class Device(
        val manufacturer: String = "",
        val model: String = "",
        val sdk: Int = 0,
        /** A hint for wording and Samsung-only screens; never a reason to assume a feature. */
        val isSamsung: Boolean = false,
        val nfc: Boolean = false,
        val uwb: Boolean = false,
        val stylus: Boolean = false,
        val externalDisplay: Boolean = false
    )

    data class Permissions(
        val microphone: Boolean = false,
        val overlay: Boolean = false,
        val notificationListener: Boolean = false,
        /** Posting notifications; always true below Android 13, where it isn't a permission. */
        val postNotifications: Boolean = false,
        val contacts: Boolean = false,
        val phone: Boolean = false,
        val calendar: Boolean = false,
        val location: Boolean = false,
        val batteryExempt: Boolean = false,
        /** "Do Not Disturb access": DND and fully silent mode. */
        val dndAccess: Boolean = false,
        /** "Modify system settings": brightness. */
        val writeSettings: Boolean = false
    )

    data class Integrations(
        val groqKey: Boolean = false,
        val gmail: Boolean = false,
        val lazuri: Boolean = false
    )

    data class Voice(
        val wakeWordEnabled: Boolean = false,
        val wakeModelReady: Boolean = false,
        val voiceProfile: Boolean = false,
        /** The always-on listener is running right now. */
        val listening: Boolean = false
    )

    data class Assistant(
        /** Friday is the phone's selected digital assistant. */
        val active: Boolean = false
    )

    data class Privileged(
        /** The Shizuku app is installed; whether Friday may use it comes with SHIZUKU-P1S. */
        val shizukuInstalled: Boolean = false
    )
}
