package com.forge.audiobookforge

import com.forge.audiobookforge.data.parser.EpubParser
import com.forge.audiobookforge.data.parser.MAX_TXT_BYTES
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every parser of user-supplied input must bound what it holds. A review found TXT and PDF
 * still unbounded after EPUB and the voice clip had been fixed — the same bug class, in two
 * places the first pass missed. These pin the TXT ceiling; the PDF cap and the EPUB path are
 * covered by EpubBombTest and BoundedReadTest.
 */
class ParsedInputBoundsTest {

    @Test
    fun aTextFileCeilingExistsAndIsSane() {
        assertTrue("must allow a genuinely enormous book", MAX_TXT_BYTES >= 32L * 1024 * 1024)
        assertTrue("must not be a memory risk", MAX_TXT_BYTES <= 256L * 1024 * 1024)
    }

    @Test
    fun aTextFileUnderTheCeilingReadsFully() {
        val bytes = ByteArray(1024) { 'a'.code.toByte() }
        assertEquals(1024, EpubParser.readBounded(ByteArrayInputStream(bytes), MAX_TXT_BYTES).size)
    }

    @Test
    fun anOversizedTextFileIsRefusedRatherThanBuffered() {
        // Stands in for the multi-gigabyte file the picker can offer.
        val limit = 4096L
        val payload = ByteArray(4097) { 'x'.code.toByte() }
        try {
            EpubParser.readBounded(ByteArrayInputStream(payload), limit)
            fail("an oversized text file must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }
}
