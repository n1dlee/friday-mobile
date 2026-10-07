package com.friday.ai.core.messages

/**
 * Whether a chat is a company talking, not a person: shops, banks,
 * operators, delivery services, confirmation codes. Those are never kept,
 * listed or read out — the owner asked for people only.
 *
 * Someone in the phone book always gets through. Everyone else is judged by
 * who they look like (a short code, a sender name in capitals, "Bank" in the
 * name) and by what they write (a discount with a link, a one-time code).
 */
object PromoFilter {

    private val I = RegexOption.IGNORE_CASE

    /** "900", "1234", "+7 900": operators and services texting from short numbers. */
    private val shortCode = Regex("""^[\d\s+-]{2,7}$""")

    private val companyName = Regex(
        """(?<!\p{L})(?:bank|банк\p{L}*|market|маркет|shop|store|магазин|ооо|llc|ltd|inc|official|info|""" +
            """support|поддержк\p{L}*|service|сервис|delivery|доставк\p{L}*|express|pay|telecom|mobile|""" +
            """bot|бот|news|новости|promo)(?!\p{L})""",
        I
    )

    private val promoWords = Regex(
        """скидк|акци[яиюейо]|промокод|распродаж|к[еэ]шб[эе]к|бонус|выгодн|спецпредложени|розыгрыш|""" +
            """подарок|отпис|только\s+сегодня|успейте|\bsale\b|discount|promo|offer|unsubscribe|cashback""",
        I
    )

    private val oneTimeCode = Regex(
        """код\s+(?:подтверждения|для\s+входа|верификации)|никому\s+не\s+(?:сообщайте|говорите|передавайте)|""" +
            """verification\s+code|one-time|your\s+code\s+is""",
        I
    )

    private val link = Regex("""https?://|www\.""", I)

    /** Promotion needs two signs — a friend's "скидка в Заре" alone is not spam. */
    private const val PROMO_SIGNS = 2

    /** Sender names shorter than this in capitals are initials, not companies. */
    private const val MIN_CAPS_NAME = 3

    /** @param contacts the phone book's names, lower-cased */
    fun isPromotional(chat: Conversation, contacts: Set<String>): Boolean {
        val title = chat.title.trim()
        val text = chat.messages.joinToString(" ") { it.text }
        val company = !chat.isGroup && (
            shortCode.matches(title) || isSenderId(title) || companyName.containsMatchIn(title) ||
                promoSigns(text) >= PROMO_SIGNS
            )
        return title.lowercase() !in contacts && (oneTimeCode.containsMatchIn(text) || company)
    }

    /** "BEELINE", "UZUM": SMS sender names come in capitals; people's names do not. */
    private fun isSenderId(title: String): Boolean =
        title.count { it.isLetter() } >= MIN_CAPS_NAME && title.none { it.isLowerCase() }

    private fun promoSigns(text: String): Int =
        promoWords.findAll(text).count() + (if (link.containsMatchIn(text)) 1 else 0) + (if ('%' in text) 1 else 0)
}
