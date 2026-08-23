package com.forge.audiobookforge.tts

/**
 * Complete Kokoro v1.0 multi-lang voice catalog.
 *
 * sid == index into kokoro-multi-lang-v1_0's voices.bin, verified against
 * k2-fsa/sherpa-onnx scripts/kokoro/v1.0/generate_voices_bin.py (53 voices,
 * ids 0..52). Do not edit sids by hand.
 */
object Voices {
    data class Voice(val sid: Int, val name: String, val description: String)

    val ALL: List<Voice> = listOf(
        // ---- American English ----
        Voice(0, "af_alloy", "🇺🇸 US female"),
        Voice(1, "af_aoede", "🇺🇸 US female"),
        Voice(2, "af_bella", "🇺🇸 US female · expressive ★"),
        Voice(3, "af_heart", "🇺🇸 US female · flagship ❤ ★"),
        Voice(4, "af_jessica", "🇺🇸 US female"),
        Voice(5, "af_kore", "🇺🇸 US female"),
        Voice(6, "af_nicole", "🇺🇸 US female · soft ★"),
        Voice(7, "af_nova", "🇺🇸 US female"),
        Voice(8, "af_river", "🇺🇸 US female"),
        Voice(9, "af_sarah", "🇺🇸 US female ★"),
        Voice(10, "af_sky", "🇺🇸 US female"),
        Voice(11, "am_adam", "🇺🇸 US male"),
        Voice(12, "am_echo", "🇺🇸 US male"),
        Voice(13, "am_eric", "🇺🇸 US male"),
        Voice(14, "am_fenrir", "🇺🇸 US male · strong ★"),
        Voice(15, "am_liam", "🇺🇸 US male"),
        Voice(16, "am_michael", "🇺🇸 US male · warm ★"),
        Voice(17, "am_onyx", "🇺🇸 US male · deep"),
        Voice(18, "am_puck", "🇺🇸 US male · playful"),
        Voice(19, "am_santa", "🇺🇸 US male · festive 🎅"),

        // ---- British English ----
        Voice(20, "bf_alice", "🇬🇧 UK female"),
        Voice(21, "bf_emma", "🇬🇧 UK female ★"),
        Voice(22, "bf_isabella", "🇬🇧 UK female"),
        Voice(23, "bf_lily", "🇬🇧 UK female"),
        Voice(24, "bm_daniel", "🇬🇧 UK male"),
        Voice(25, "bm_fable", "🇬🇧 UK male · storyteller"),
        Voice(26, "bm_george", "🇬🇧 UK male"),
        Voice(27, "bm_lewis", "🇬🇧 UK male"),

        // ---- Spanish ----
        Voice(28, "ef_dora", "🇪🇸 Español · femenina"),
        Voice(29, "em_alex", "🇪🇸 Español · masculino"),

        // ---- French ----
        Voice(30, "ff_siwis", "🇫🇷 Français · féminine"),

        // ---- Hindi ----
        Voice(31, "hf_alpha", "🇮🇳 हिन्दी · female"),
        Voice(32, "hf_beta", "🇮🇳 हिन्दी · female"),
        Voice(33, "hm_omega", "🇮🇳 हिन्दी · male"),
        Voice(34, "hm_psi", "🇮🇳 हिन्दी · male"),

        // ---- Italian ----
        Voice(35, "if_sara", "🇮🇹 Italiano · femminile"),
        Voice(36, "im_nicola", "🇮🇹 Italiano · maschile"),

        // ---- Japanese ----
        Voice(37, "jf_alpha", "🇯🇵 日本語 · 女性 ★"),
        Voice(38, "jf_gongitsune", "🇯🇵 日本語 · 女性"),
        Voice(39, "jf_nezumi", "🇯🇵 日本語 · 女性"),
        Voice(40, "jf_tebukuro", "🇯🇵 日本語 · 女性"),
        Voice(41, "jm_kumo", "🇯🇵 日本語 · 男性"),

        // ---- Brazilian Portuguese ----
        Voice(42, "pf_dora", "🇧🇷 Português · feminina"),
        Voice(43, "pm_alex", "🇧🇷 Português · masculino"),

        // ---- Mandarin Chinese ----
        Voice(44, "zf_xiaobei", "🇨🇳 中文 · 女声"),
        Voice(45, "zf_xiaoni", "🇨🇳 中文 · 女声"),
        Voice(46, "zf_xiaoxiao", "🇨🇳 中文 · 女声 ★"),
        Voice(47, "zf_xiaoyi", "🇨🇳 中文 · 女声"),
        Voice(48, "zm_yunjian", "🇨🇳 中文 · 男声"),
        Voice(49, "zm_yunxi", "🇨🇳 中文 · 男声 ★"),
        Voice(50, "zm_yunxia", "🇨🇳 中文 · 男声"),
        Voice(51, "zm_yunyang", "🇨🇳 中文 · 男声"),
    )

    fun displayName(sid: Int): String =
        ALL.firstOrNull { it.sid == sid }?.name ?: "Speaker $sid"

    fun description(sid: Int): String =
        ALL.firstOrNull { it.sid == sid }?.description ?: ""
}
