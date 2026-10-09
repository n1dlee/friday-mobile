package com.friday.ai.agent

/**
 * How much of the conversation goes with each request.
 *
 * Everything sent is paid for on every round of every answer, and Groq's
 * free tier allows 8,000 tokens a minute. The latest exchange is what a
 * follow-up ("а на завтра?", "ответь ему…") leans on, so it goes whole;
 * older messages are there for the gist, and a long old answer is cut.
 */
object PromptBudget {

    /** Earlier chat messages sent along (they were 20). */
    const val CHAT_HISTORY = 10

    /** The newest messages are kept whole: the last exchange. */
    const val KEPT_WHOLE = 2

    /** Older messages are cut to this many characters (about 150 tokens). */
    const val OLDER_CHARS = 600

    /**
     * [text] as sent, given its place counted from the end of the
     * conversation (1 = the latest message).
     */
    fun older(text: String, fromEnd: Int): String =
        if (fromEnd <= KEPT_WHOLE || text.length <= OLDER_CHARS) text
        else text.take(OLDER_CHARS).trimEnd() + "…"
}
