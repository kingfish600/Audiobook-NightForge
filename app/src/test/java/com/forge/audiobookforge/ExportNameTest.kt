package com.forge.audiobookforge

import com.forge.audiobookforge.audio.BookExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The export name filter used to strip everything outside [A-Za-z0-9], so every
 * non-Latin title collapsed to underscores: 红楼梦 and 西游记 both became "___"
 * and were written into the SAME folder, overwriting each other.
 */
class ExportNameTest {

    @Test
    fun distinctNonLatinTitlesStayDistinct() {
        val a = BookExporter.sanitize("红楼梦")
        val b = BookExporter.sanitize("西游记")
        assertNotEquals("different books must not share an export name", a, b)
    }

    @Test
    fun pathSeparatorsAreRemoved() {
        val s = BookExporter.sanitize("../../etc/passwd")
        assert(!s.contains('/')) { "no path separators may survive: $s" }
        assert(!s.contains('\\')) { "no windows separators may survive: $s" }
    }

    @Test
    fun dotOnlyNamesDoNotResolveToADirectory() {
        // File(dir, "..") is the PARENT directory, not a file.
        assertNotEquals("..", BookExporter.sanitize(".."))
        assertNotEquals(".", BookExporter.sanitize("."))
        assertEquals("book", BookExporter.sanitize("..."))
    }

    @Test
    fun emptyTitleFallsBack() {
        assertEquals("book", BookExporter.sanitize("   "))
    }
}
