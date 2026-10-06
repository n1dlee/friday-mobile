package com.friday.ai.core

/**
 * Which music app the user meant, by name.
 *
 * Kept separate from the media control itself so the naming can be tested
 * without a device — the package list is the part that goes stale, and the
 * part most likely to be typed wrong.
 */
object MusicApps {

    data class Player(val packageName: String, val label: String, val spoken: List<String>)

    val KNOWN = listOf(
        Player(
            "com.spotify.music", "Spotify",
            listOf("spotify", "спотифай", "спотифаи", "спотифае", "спотифая", "спотик")
        ),
        Player(
            "com.sec.android.app.music", "Samsung Music",
            listOf("samsung music", "самсунг мьюзик", "самсунг музыка", "самсунг")
        ),
        Player(
            "com.google.android.apps.youtube.music", "YouTube Music",
            listOf("youtube music", "ютуб мьюзик", "ютуб музыка", "ютуб музыке", "ютюб музыка")
        ),
        Player(
            "ru.yandex.music", "Яндекс Музыка",
            listOf("яндекс музыка", "яндекс музыке", "яндекс мьюзик", "яндекс")
        ),
        // Deliberately no plain YouTube and no VK. They hold media sessions,
        // so "следующий трек" still reaches them — but naming them means "open
        // the app", not "play music", and listing them turned "запусти ютуб"
        // into a playback command.
        Player("com.soundcloud.android", "SoundCloud", listOf("soundcloud", "саундклауд")),
        Player("deezer.android.app", "Deezer", listOf("deezer", "дизер")),
        Player("com.apple.android.music", "Apple Music", listOf("apple music", "эпл мьюзик")),
        Player("com.maxmpz.audioplayer", "Poweramp", listOf("poweramp", "поверамп"))
    )

    /**
     * The player named in [phrase], or null when the user just said "музыку"
     * and means whatever is already loaded.
     */
    fun match(phrase: String): Player? {
        val text = SpokenText.normalise(phrase)
        return KNOWN
            .flatMap { player -> player.spoken.map { player to SpokenText.normalise(it) } }
            // Longest first: "яндекс музыка" must beat the bare "яндекс".
            .sortedByDescending { it.second.length }
            .firstOrNull { (_, name) -> " $text ".contains(" $name ") }
            ?.first
    }
}
