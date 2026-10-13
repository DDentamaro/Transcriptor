package it.donato.trascrittore

/**
 * Whisper, nei tratti di silenzio o di sola musica, a volte "inventa" frasi
 * imparate dai sottotitoli con cui e stato addestrato, oppure ripete in loop
 * la stessa frase. Qui scartiamo solo i casi certi, per non perdere parlato vero.
 */
object TextFilter {
    private val knownHallucinations = listOf(
        Regex("amara\\.org", RegexOption.IGNORE_CASE),
        Regex("\\bQTSS\\b", RegexOption.IGNORE_CASE),
        Regex("^sottotitoli (creati|e revisione|a cura|realizzati)", RegexOption.IGNORE_CASE),
        Regex("^(subtitles|subtitulado|sous-titres|untertitel) (by|por|par|der|von|im)", RegexOption.IGNORE_CASE),
    )

    /** Testo troppo breve per giudicare una ripetizione: "Sì." ripetuto puo essere vero. */
    private const val MIN_REPEAT_LEN = 20

    fun isHallucination(text: String, previous: String?): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (knownHallucinations.any { it.containsMatchIn(t) }) return true
        return t.length >= MIN_REPEAT_LEN && previous != null && t.equals(previous.trim(), ignoreCase = true)
    }
}
