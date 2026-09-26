package com.forge.audiobookforge

import com.forge.audiobookforge.tts.Voices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the Kokoro voice table.
 *
 * The table previously held 52 entries and was shifted by one from sid 44 up
 * (pm_santa was missing), so from 44 onward the app played a different voice
 * than the one selected, and the last two speakers were unreachable. The
 * authoritative order comes from sherpa-onnx's
 * scripts/kokoro/v1.0/generate_voices_bin.py (54 voices, ids 0..53).
 */
class VoicesTableTest {

    @Test
    fun tableCoversEverySpeakerExactlyOnce() {
        assertEquals("kokoro v1.0 has 54 speakers", 54, Voices.ALL.size)
        assertEquals((0..53).toList(), Voices.ALL.map { it.sid })
        assertEquals(Voices.ALL.size, Voices.ALL.map { it.name }.toSet().size)
    }

    @Test
    fun sidsBeyond43MatchTheAuthoritativeOrder() {
        // pm_santa at 44 is precisely the entry that used to be missing.
        assertEquals("pm_alex", Voices.displayName(43))
        assertEquals("pm_santa", Voices.displayName(44))
        assertEquals("zf_xiaobei", Voices.displayName(45))
        assertEquals("zm_yunyang", Voices.displayName(52))
        assertEquals("em_santa", Voices.displayName(53))
        assertEquals("af_heart", Voices.displayName(3))
        assertEquals("am_santa", Voices.displayName(19))
    }

    @Test
    fun languageIsDerivedFromTheVoiceFamily() {
        assertEquals("en-us", Voices.langForSid(0))    // af_alloy
        assertEquals("en-gb", Voices.langForSid(20))   // bf_alice
        assertEquals("es", Voices.langForSid(28))      // ef_dora
        assertEquals("fr-fr", Voices.langForSid(30))   // ff_siwis
        assertEquals("hi", Voices.langForSid(31))      // hf_alpha
        assertEquals("it", Voices.langForSid(35))      // if_sara
        assertEquals("ja", Voices.langForSid(37))      // jf_alpha
        assertEquals("pt-br", Voices.langForSid(42))   // pf_dora
        assertTrue("Chinese keeps the default path", Voices.langForSid(45).isEmpty())
        assertEquals("es", Voices.langForSid(53))      // em_santa
        assertTrue(Voices.langForSid(-1).isEmpty())
    }
}
