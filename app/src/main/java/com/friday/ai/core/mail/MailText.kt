package com.friday.ai.core.mail

/** Turns mail bodies into text worth reading aloud. */
object MailText {

    fun htmlToText(html: String): String =
        decodeEntities(
            html.replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), " ")
                .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>"""), "\n")
                .replace(Regex("""<[^>]+>"""), " ")
        ).lines().joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .replace(Regex("""\n{3,}"""), "\n\n").trim()

    fun decodeEntities(s: String): String =
        s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * Cuts the quoted history off a reply. Reading out the whole thread every
     * time would bury the one new line under everything already seen.
     */
    fun stripQuoted(text: String): String {
        val kept = mutableListOf<String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith(">")) break
            if (quoteIntro.containsMatchIn(t)) break
            kept += line
        }
        return kept.joinToString("\n").trim()
    }

    private val quoteIntro = Regex(
        listOf(
            """On .+ wrote:""",
            """.+ (?:пишет|написал\p{L}*|написала)\s*:""",
            """-{2,}\s*(?:Original Message|Исходное сообщение)\s*-{2,}"""
        ).joinToString("|", prefix = "^(?:", postfix = ")$"),
        RegexOption.IGNORE_CASE
    )
}
