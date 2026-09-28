package com.forge.audiobookforge.tts

/**
 * Sentences to read aloud when recording a cloned voice.
 *
 * A cloning model learns a speaker only from the sounds present in the reference
 * clip. Read a script missing a language's rarer consonants and the clone will
 * mangle exactly those sounds forever. These scripts are written to hit the
 * awkward English phonemes — /θ/ *th*ink, /ð/ *th*e, /ʃ/ *sh*, /tʃ/ *ch*,
 * /dʒ/ *j*, /ŋ/ *-ng*, /v/, /w/, and the /r/–/l/ pair — inside sentences that
 * still sound like a person talking, at a length that reads naturally in one go.
 *
 * They double as the transcript: because the user reads a known script, the
 * words spoken are known exactly, and a clone never needs typing.
 */
object CloneScripts {

    data class Script(
        val language: String,
        val title: String,
        val text: String,
        val note: String,
    ) {
        val words: Int get() = text.split(' ').count { it.isNotBlank() }

        /**
         * Chinese has no spaces, so counting words reports "1 word, 0 seconds" for a
         * perfectly good Mandarin script. Latin text is measured in words (~2.6/s),
         * Chinese in characters (~4.5/s, a normal Mandarin speaking rate).
         */
        val isCjk: Boolean get() = text.any { it.code in 0x4E00..0x9FFF }

        private val cjkChars: Int get() = text.count { it.code in 0x4E00..0x9FFF }

        /** What the on-screen hint should count: words, or characters for Chinese. */
        val units: Int get() = if (isCjk) cjkChars else words

        val unitLabel: String get() = if (isCjk) "characters" else "words"

        val estimatedSeconds: Double get() = if (isCjk) cjkChars / 4.5 else words / 2.6
    }

    val ALL: List<Script> = listOf(
        Script(
            language = "English",
            title = "Short · everyday",
            text = "Bright winter mornings make the whole valley shine, and the cold air " +
                "feels clean against my face. I walk quickly past the river, thinking about " +
                "the day ahead.",
            note = "Covers th, sh, v, w, ng and the r–l pair.",
        ),
        Script(
            language = "English",
            title = "Short · questions",
            text = "Do you remember the small theatre beside the bridge? We watched three " +
                "shows there, and afterwards we argued about them for hours. Isn't it strange " +
                "how a single voice can change a whole story?",
            note = "Questions and exclamations give the clone some pitch range.",
        ),
        Script(
            language = "English",
            title = "Longer · expressive",
            text = "The weather changed suddenly that afternoon, and the wind pushed sheets " +
                "of rain against the windows. I made tea, turned on the radio, and listened to " +
                "a journalist describe the flooding downstream. Later, when the sky cleared, " +
                "everything smelled of wet earth and the streets were shining.",
            note = "Best coverage if you have the patience — a little over ten seconds.",
        ),
        Script(
            language = "中文",
            title = "普通 · 日常",
            text = "今天早上的天气特别好，阳光穿过窗户照在地板上。我喜欢在这样的日子里慢慢地喝茶，" +
                "听窗外的鸟叫声，想一想接下来要做的事情。",
            note = "包含不同声调的组合，读的时候不必刻意放慢。",
        ),
    )

    /** How to get a clip that clones well, shown before recording. */
    val GUIDANCE: List<String> = listOf(
        "Find a quiet room — fans, traffic and music all end up in the clone.",
        "Hold the phone about 20 cm away and speak the way you normally would.",
        "Read at your own pace; don't perform or slow down unnaturally.",
        "5–15 seconds is the sweet spot. The app stops by itself at 30.",
    )

    fun forLanguage(language: String): List<Script> =
        ALL.filter { it.language == language }

    val languages: List<String> = ALL.map { it.language }.distinct()
}
