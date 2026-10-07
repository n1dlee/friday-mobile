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
        ESSENTIALS("Основное"),
        VOICE("Голос"),
        PHONE("Телефон и люди"),
        INTEGRATIONS("Сервисы"),
        DEVICE("Это устройство")
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
        Check("groq", "Ключ Groq API", "Задан", "Не задан: Пятница не понимает речь и не отвечает", Fix.FRIDAY_SETTINGS)
            .row(Group.ESSENTIALS, c.integrations.groqKey),
        Check("microphone", "Микрофон", "Разрешён", "Запрещён: Пятница вас не слышит", Fix.MICROPHONE)
            .row(Group.ESSENTIALS, c.permissions.microphone),
        Check(
            "overlay", "Поверх других приложений", "Разрешено",
            "Запрещено: панель ассистента не может появиться", Fix.OVERLAY
        ).row(Group.ESSENTIALS, c.permissions.overlay),
        Check(
            "post_notifications", "Уведомления", "Разрешены",
            "Запрещены: Android может не дать слушать в фоне", Fix.POST_NOTIFICATIONS
        ).row(Group.ESSENTIALS, c.permissions.postNotifications)
    )

    private fun voice(c: FridayCapabilities): List<Row> {
        val v = c.voice
        val wakeWord = Check(
            "wake_word", "Слово «Пятница»", "Включено",
            "Выключено: Пятница слушает, только когда открыто приложение", Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.VOICE, v.wakeWordEnabled)
        if (!v.wakeWordEnabled) return listOf(wakeWord)
        return listOf(
            wakeWord,
            Check(
                "wake_model", "Модель слова", "Скачана",
                "Не скачана: слово не может работать", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.wakeModelReady),
            Check(
                "listening", "Слушает в фоне", "Работает",
                "Остановлено: «Пятница» сейчас ничего не делает", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.listening),
            Check(
                "battery", "Ограничения батареи", "Нет",
                "Есть: Android может усыпить прослушивание", Fix.BATTERY
            ).row(Group.VOICE, c.permissions.batteryExempt),
            Check(
                "voice_profile", "Голосовой профиль", "Записан: слушается только ваш голос",
                "Не записан: командовать может любой голос", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.voiceProfile)
        )
    }

    private fun phone(c: FridayCapabilities) = listOf(
        Check(
            "notification_access", "Доступ к уведомлениям", "Включён",
            "Выключен: нет чтения и ответов в чатах, управления музыкой, объявления звонков",
            Fix.NOTIFICATION_ACCESS
        ).row(Group.PHONE, c.permissions.notificationListener),
        Check(
            "contacts", "Контакты", "Разрешены",
            "Запрещены: «позвони маме» и «напиши папе» никого не найдут", Fix.CONTACTS
        ).row(Group.PHONE, c.permissions.contacts),
        Check(
            "phone", "Звонки", "Разрешены: звонок начинается сразу",
            "Запрещены: звонок только открывает набор номера", Fix.PHONE, optional = true
        ).row(Group.PHONE, c.permissions.phone),
        Check(
            "calendar", "Календарь", "Разрешён",
            "Запрещён: событие придётся сохранять вручную, перенос не работает", Fix.CALENDAR
        ).row(Group.PHONE, c.permissions.calendar),
        Check(
            "location", "Геопозиция", "Разрешена",
            "Запрещена: нет погоды «здесь» и напоминаний у магазинов", Fix.LOCATION
        ).row(Group.PHONE, c.permissions.location)
    )

    private fun integrations(c: FridayCapabilities) = listOf(
        Check(
            "gmail", "Gmail", "Подключён", "Не подключён: почтовые команды выключены",
            Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.INTEGRATIONS, c.integrations.gmail)
        // Lazuri is deferred: Friday lives on one phone, and moves with settings export.
    )

    private fun device(c: FridayCapabilities): List<Row> {
        val d = c.device
        fun info(id: String, title: String, detail: String) = Row(id, Group.DEVICE, title, Status.INFO, detail)
        fun yesNo(on: Boolean) = if (on) "Есть" else "Нет"
        return listOf(
            info("model", "Устройство", "${d.manufacturer} ${d.model} · Android API ${d.sdk}".trim()),
            info(
                "assistant", "Цифровой ассистент",
                if (c.assistant.active) "Пятница" else "Другое приложение"
            ),
            info("nfc", "NFC", yesNo(d.nfc)),
            info("uwb", "UWB", yesNo(d.uwb)),
            info("stylus", "Стилус", yesNo(d.stylus)),
            info("external_display", "Внешний экран", if (d.externalDisplay) "Подключён" else "Нет"),
            info(
                "shizuku", "Shizuku",
                if (c.privileged.shizukuInstalled) "Установлен (пока не используется)"
                else "Не установлен (необязательно)"
            )
        )
    }
}
