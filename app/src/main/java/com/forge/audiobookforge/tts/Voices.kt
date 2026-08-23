package com.forge.audiobookforge.tts

/**
 * Curated Kokoro voice list. Index in this list == speaker id (sid) used by
 * kokoro-multi-lang-v1_0's voices.bin (voices are stored alphabetically).
 */
object Voices {
    data class Voice(val sid: Int, val name: String, val description: String)

    val ALL: List<Voice> = listOf(
        Voice(0, "af_alloy", "US female"),
        Voice(2, "af_bella", "US female ★"),
        Voice(4, "af_kore", "US female"),
        Voice(6, "af_nicole", "US female"),
        Voice(7, "af_nova", "US female"),
        Voice(8, "af_river", "US female"),
        Voice(9, "af_sarah", "US female ★"),
        Voice(10, "af_sky", "US female"),
        Voice(11, "am_adam", "US male"),
        Voice(14, "am_fenrir", "US male ★"),
        Voice(16, "am_michael", "US male ★"),
        Voice(17, "am_onyx", "US male"),
        Voice(18, "am_puck", "US male"),
        Voice(20, "bf_alice", "UK female"),
        Voice(21, "bf_emma", "UK female"),
        Voice(22, "bf_isabella", "UK female"),
        Voice(23, "bf_lily", "UK female"),
        Voice(24, "bm_daniel", "UK male"),
        Voice(25, "bm_fable", "UK male"),
        Voice(26, "bm_george", "UK male"),
        Voice(28, "bm_wyatt", "UK male"),
    )

    fun displayName(sid: Int): String =
        ALL.firstOrNull { it.sid == sid }?.name ?: "Speaker $sid"
}
