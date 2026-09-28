package com.forge.audiobookforge

import com.forge.audiobookforge.audio.BookExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong MIME type makes MediaStore reject an export, and the codec is a user
 * setting — so the mapping between the written file and the type it is published
 * as is worth pinning down for all three formats.
 */
class ExportMimeTest {

    @Test
    fun eachFormatIsPublishedWithItsOwnType() {
        assertEquals("audio/ogg", BookExporter.mimeFor("001 - Chapter.ogg"))
        assertEquals("audio/wav", BookExporter.mimeFor("001 - Chapter.wav"))
        assertEquals("audio/mp4", BookExporter.mimeFor("001 - Chapter.m4a"))
    }

    @Test
    fun theMatchIgnoresCaseAndUnknownExtensionsFallBackToMp4() {
        assertEquals("audio/ogg", BookExporter.mimeFor("CHAPTER.OGG"))
        assertEquals("audio/wav", BookExporter.mimeFor("Chapter.WaV"))
        assertEquals("audio/mp4", BookExporter.mimeFor("something.bin"))
    }
}

/**
 * The LIKE pattern that retires older duplicate exports. It must match
 * "name (2).m4a" (what Android's MediaStore actually creates) and must not
 * over-match: `_` is a LIKE wildcard, which matters because sanitize() both
 * preserves and generates underscores in chapter titles.
 */
class LegacyCopyPatternTest {

    @Test
    fun matchesTheDuplicatesMediaStoreActuallyCreates() {
        val p = BookExporter.legacyCopyPattern("001 - Chapter One.m4a")
        assertTrue("must match the (2) copy: $p", "001 - Chapter One (2).m4a".matches(likeToRegex(p)))
        assertTrue("must match the (12) copy", "001 - Chapter One (12).m4a".matches(likeToRegex(p)))
    }

    @Test
    fun underscoresAreEscapedSoTitlesCannotOverMatch() {
        val p = BookExporter.legacyCopyPattern("001 - Chapter_X1.m4a")
        assertTrue("the escaped pattern is $p", p.contains("\\_"))
        assertTrue("its own copy matches", "001 - Chapter_X1 (2).m4a".matches(likeToRegex(p)))
        assertFalse(
            "a different title must not match",
            "001 - Chapter_X2 (2).m4a".matches(likeToRegex(p)),
        )
    }

    private fun likeToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> { sb.append(Regex.escape(pattern[i + 1].toString())); i += 1 }
                c == '%' -> sb.append(".*")
                c == '_' -> sb.append(".")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i += 1
        }
        return Regex(sb.toString())
    }
}
