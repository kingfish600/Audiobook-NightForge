package com.forge.audiobookforge

import com.forge.audiobookforge.data.parser.EpubParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An EPUB is untrusted input, and a zip entry can claim to expand to far more than it
 * compresses to. The parser used to read each entry whole and check the running total
 * afterwards, so a hostile or corrupt EPUB crashed the app with OutOfMemoryError before
 * any guard ran. These pin the bounded reader that replaced it.
 */
class EpubBombTest {

    @Test
    fun readsUpToTheLimitAndStops() {
        val payload = ByteArray(100_000) { 7 }
        val read = EpubParser.readBounded(ByteArrayInputStream(payload), 100_000)
        assertEquals("exactly at the limit is allowed", payload.size, read.size)
    }

    @Test
    fun refusesToBufferPastTheLimit() {
        val payload = ByteArray(100_000) { 7 }
        try {
            EpubParser.readBounded(ByteArrayInputStream(payload), 64 * 1024)
            fail("a stream larger than the limit must be refused, not buffered")
        } catch (e: IllegalArgumentException) {
            assertTrue("message should be user-facing", e.message!!.contains("too large"))
        }
    }

    @Test
    fun aZeroLimitReadsNothingButDoesNotCrash() {
        assertEquals(0, EpubParser.readBounded(ByteArrayInputStream(ByteArray(0)), 0).size)
        val payload = ByteArray(1024)
        try {
            EpubParser.readBounded(ByteArrayInputStream(payload), 0)
            fail("no budget means nothing may be read")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    /** A real, minimal EPUB must still parse — the guard must not reject valid books. */
    @Test
    fun aSmallValidEpubStillParses() {
        val chapter = ("<html><body><h1>Chapter One</h1><p>" +
            "This is a perfectly ordinary chapter with enough text to be kept. </p></body></html>")
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>A Tiny Book</dc:title><dc:creator>A Person</dc:creator>
              </metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf"
                media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()

        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/ch1.xhtml", chapter)
        }
        val parsed = EpubParser.parse(ByteArrayInputStream(zip.toByteArray()))
        assertEquals("A Tiny Book", parsed.title)
        assertTrue("the chapter should survive parsing", parsed.chapters.isNotEmpty())
        assertTrue(
            "chapter text should contain the body",
            parsed.chapters.any { it.text.contains("perfectly ordinary") },
        )
    }
}
