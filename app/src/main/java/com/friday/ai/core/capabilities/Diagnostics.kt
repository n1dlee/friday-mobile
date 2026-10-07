package com.friday.ai.core.capabilities

/**
 * The diagnostics screen as data: one row per thing that decides whether a
 * Friday feature works, what it means for the user, and how to fix it.
 *
 * Before this, a missing permission surfaced in the middle of a spoken
 * exchange as "нужен доступ к…", if at all. Here it is all in one place,
 * read from the same snapshot that decides what the model is offered — so
 * the screen and Friday's behaviour can't disagree.
 */
object Diagnostics {

    enum class Group(val title: String) {
        ESSENTIALS("Essentials"),
        VOICE("Voice"),
        PHONE("Phone & people"),
        INTEGRATIONS("Integrations"),
        DEVICE("This device")
    }

    enum class Status {
        /** Works. */
        OK,

        /** Something Friday should do doesn't work until this is fixed. */
        PROBLEM,

        /** Off by choice or not set up; nothing is broken. */
        OFF,

        /** A fact about the device, nothing to fix. */
        INFO
    }

    /** What tapping "fix" does; the screen maps each to the right system action. */
    enum class Fix {
        MICROPHONE, POST_NOTIFICATIONS, OVERLAY, NOTIFICATION_ACCESS, BATTERY,
        CONTACTS, PHONE, CALENDAR, LOCATION,

        /** Friday's own Settings screen: API key, wake word, voice model and profile, Gmail, export. */
        FRIDAY_SETTINGS
    }

    data class Row(
        val id: String,
        val group: Group,
        val title: String,
        val status: Status,
        val detail: String,
        val fix: Fix? = null
    )

    fun rows(c: FridayCapabilities): List<Row> = essentials(c) + voice(c) + phone(c) + integrations(c) + device(c)

    /** How many rows need the user's attention. */
    fun problems(rows: List<Row>): Int = rows.count { it.status == Status.PROBLEM }

    /** One thing to check: what it is called, what each outcome means, how to fix it. */
    private class Check(
        val id: String,
        val title: String,
        val okDetail: String,
        val badDetail: String,
        val fix: Fix?,
        /** Off by choice rather than broken: shown as OFF, not counted as a problem. */
        val optional: Boolean = false
    ) {
        fun row(group: Group, ok: Boolean) = Row(
            id, group, title,
            status = when {
                ok -> Status.OK
                optional -> Status.OFF
                else -> Status.PROBLEM
            },
            detail = if (ok) okDetail else badDetail,
            fix = if (ok) null else fix
        )
    }

    private fun essentials(c: FridayCapabilities) = listOf(
        Check("groq", "Groq API key", "Set", "Not set: Friday can't understand speech or answer", Fix.FRIDAY_SETTINGS)
            .row(Group.ESSENTIALS, c.integrations.groqKey),
        Check("microphone", "Microphone", "Allowed", "Not allowed: Friday can't hear you", Fix.MICROPHONE)
            .row(Group.ESSENTIALS, c.permissions.microphone),
        Check(
            "overlay", "Display over other apps", "Allowed",
            "Not allowed: the listening panel can't appear", Fix.OVERLAY
        ).row(Group.ESSENTIALS, c.permissions.overlay),
        Check(
            "post_notifications", "Notifications", "Allowed",
            "Not allowed: Android may refuse to keep the listener running", Fix.POST_NOTIFICATIONS
        ).row(Group.ESSENTIALS, c.permissions.postNotifications)
    )

    private fun voice(c: FridayCapabilities): List<Row> {
        val v = c.voice
        val wakeWord = Check(
            "wake_word", "Wake word \"Friday\"", "On",
            "Off: Friday only listens when you open the app", Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.VOICE, v.wakeWordEnabled)
        if (!v.wakeWordEnabled) return listOf(wakeWord)
        return listOf(
            wakeWord,
            Check(
                "wake_model", "Wake-word model", "Downloaded",
                "Not downloaded: the wake word can't work", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.wakeModelReady),
            Check(
                "listening", "Listening in the background", "Running",
                "Stopped: saying \"Friday\" does nothing right now", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.listening),
            Check(
                "battery", "Battery restrictions", "Unrestricted",
                "Restricted: Android may put the listener to sleep", Fix.BATTERY
            ).row(Group.VOICE, c.permissions.batteryExempt),
            Check(
                "voice_profile", "Voice profile", "Recorded: only your voice is obeyed",
                "Not recorded: any voice can give Friday commands", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.voiceProfile)
        )
    }

    private fun phone(c: FridayCapabilities) = listOf(
        Check(
            "notification_access", "Notification access", "On",
            "Off: no reading or replying to chats, no music control, no call announcements",
            Fix.NOTIFICATION_ACCESS
        ).row(Group.PHONE, c.permissions.notificationListener),
        Check(
            "contacts", "Contacts", "Allowed",
            "Not allowed: \"call mum\" and \"text dad\" can't find anyone", Fix.CONTACTS
        ).row(Group.PHONE, c.permissions.contacts),
        Check(
            "phone", "Phone calls", "Allowed: calls start directly",
            "Not allowed: calls only open the dialer", Fix.PHONE, optional = true
        ).row(Group.PHONE, c.permissions.phone),
        Check(
            "calendar", "Calendar", "Allowed",
            "Not allowed: events open the calendar to save by hand; moving them doesn't work", Fix.CALENDAR
        ).row(Group.PHONE, c.permissions.calendar),
        Check(
            "location", "Location", "Allowed",
            "Not allowed: no weather \"here\", no reminders near shops", Fix.LOCATION
        ).row(Group.PHONE, c.permissions.location)
    )

    private fun integrations(c: FridayCapabilities) = listOf(
        Check(
            "gmail", "Gmail", "Connected", "Not connected: mail commands are off",
            Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.INTEGRATIONS, c.integrations.gmail)
        // Lazuri is deferred: Friday lives on one phone, and moves with settings export.
    )

    private fun device(c: FridayCapabilities): List<Row> {
        val d = c.device
        fun info(id: String, title: String, detail: String) = Row(id, Group.DEVICE, title, Status.INFO, detail)
        fun yesNo(on: Boolean) = if (on) "Yes" else "No"
        return listOf(
            info("model", "Device", "${d.manufacturer} ${d.model} · Android API ${d.sdk}".trim()),
            info("assistant", "Default digital assistant", if (c.assistant.active) "Friday" else "Another app"),
            info("nfc", "NFC", yesNo(d.nfc)),
            info("uwb", "UWB", yesNo(d.uwb)),
            info("stylus", "Stylus", yesNo(d.stylus)),
            info("external_display", "External display", if (d.externalDisplay) "Connected" else "None"),
            info(
                "shizuku", "Shizuku",
                if (c.privileged.shizukuInstalled) "Installed (not used yet)" else "Not installed (optional)"
            )
        )
    }
}
