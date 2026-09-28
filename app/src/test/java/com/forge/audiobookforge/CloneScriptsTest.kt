package com.forge.audiobookforge

import com.forge.audiobookforge.tts.CloneScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-aloud scripts are the transcript too, so a script that is too short to
 * give the model anything, or too long to read in one go, is a real defect.
 */
class CloneScriptsTest {

    @Test
    fun everyScriptIsReadableInASingleGoodTake() {
        assertTrue(CloneScripts.ALL.size >= 3)
        CloneScripts.ALL.forEach { s ->
            assertTrue(
                "${s.title} is too short to clone from (${s.units} ${s.unitLabel})",
                s.units >= if (s.isCjk) 40 else 15,
            )
            assertTrue(
                "${s.title} takes ~${s.estimatedSeconds}s — outside the 5-20s window",
                s.estimatedSeconds in 5.0..20.0,
            )
            assertTrue("${s.title} needs a note", s.note.isNotBlank())
            assertTrue("${s.title} needs a language", s.language.isNotBlank())
            assertTrue("${s.title} should be real sentences", s.text.count { it == '.' || it == '?' || it == '。' } >= 2)
        }
    }

    @Test
    fun scriptsAreGroupedByLanguageAndIncludeBothEngineLanguages() {
        val languages = CloneScripts.languages
        assertTrue("ZipVoice is zh+en, so offer both", languages.contains("English"))
        assertTrue(languages.any { it == "中文" })
        languages.forEach { lang ->
            assertTrue(CloneScripts.forLanguage(lang).isNotEmpty())
            assertTrue(CloneScripts.forLanguage(lang).all { it.language == lang })
        }
        assertTrue(CloneScripts.GUIDANCE.size >= 3)
    }

    @Test
    fun cyclingThroughSuggestionsWrapsAround() {
        val pool = CloneScripts.forLanguage("English")
        var current = pool.first()
        repeat(pool.size * 2) {
            current = pool[(pool.indexOf(current) + 1).mod(pool.size)]
            assertTrue(pool.contains(current))
        }
        assertEquals(pool.first(), current)
    }
}
