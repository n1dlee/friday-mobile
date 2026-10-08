package com.friday.ai.core.audio

/**
 * Text Whisper makes up when there is no speech to hear.
 *
 * Trained on subtitled video, Whisper fills music and noise with the
 * credits it saw most: "Субтитры создавал DimaTorzok", "Продолжение
 * следует…", "Thank you. Thank you." On the owner's own voice notes it did
 * exactly that for a song. Taken as a command, that is Friday answering a
 * radio; removed, what remains (usually nothing) is what was said.
 */
object WhisperArtifacts {

    private val I = RegexOption.IGNORE_CASE

    /** Credits and sign-offs, wherever they appear. */
    private val credits = listOf(
        Regex(
            """субтитр\p{L}*\s+(?:создавал|сделал|делал|подготовил|подогнал|предоставил|редактировал)\p{L}*""" +
                """[^.!?\n]*[.!?…]*""",
            I
        ),
        Regex("""(?:редактор|корректор)\s+субтитров[^.!?\n]*[.!?…]*""", I),
        Regex("""продолжение\s+следует[.!?…]*""", I),
        Regex("""спасибо\s+за\s+просмотр[^.!?\n]*[.!?…]*""", I),
        Regex("""подписывайтесь\s+на\s+(?:мой\s+|наш\s+)?канал[^.!?\n]*[.!?…]*""", I),
        Regex("""ставьте\s+лайки[^.!?\n]*[.!?…]*""", I),
        Regex("""teksting\s+av[^.!?\n]*[.!?…]*""", I),
        Regex("""(?:subtitles|captions)\s+by[^.!?\n]*[.!?…]*""", I),
        Regex("""\S*amara\.org\S*[^.!?\n]*[.!?…]*""", I),
        Regex("""dimatorzok""", I)
    )

    /** Whole answers that are filler when repeated: "Thank you. Thank you." */
    private val repeatedThanks = Regex("""^(?:\s*thank\s+you[.!…]*){2,}\s*$""", I)

    /** [text] without the made-up parts; blank when nothing real was said. */
    fun clean(text: String): String {
        if (repeatedThanks.matches(text)) return ""
        val stripped = credits.fold(text) { acc, r -> r.replace(acc, " ") }
        return stripped.replace(Regex("""\s{2,}"""), " ").trim().trim(',', ';', '—', '-').trim()
    }
}
