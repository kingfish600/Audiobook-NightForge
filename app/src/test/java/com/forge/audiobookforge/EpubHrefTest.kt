package com.forge.audiobookforge

import com.forge.audiobookforge.data.parser.EpubParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for EPUB manifest href resolution.
 *
 * Percent-escapes and XML entities must be decoded, '+' is a literal character
 * (not a space), and `..` segments must resolve — otherwise real chapters are
 * silently skipped during import.
 */
class EpubHrefTest {

    private fun epub(vararg files: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val container =
        """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""

    private val opf =
        """<?xml version="1.0"?><package><metadata><dc:title>Href Book</dc:title><dc:creator>Someone</dc:creator></metadata><manifest>
        <item id="a" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
        <item id="b" href="../Text/ch2.xhtml" media-type="application/xhtml+xml"/>
        <item id="c" href="Text/ch%20three.xhtml" media-type="application/xhtml+xml"/>
        <item id="d" href="Text/ch+four.xhtml" media-type="application/xhtml+xml"/>
        <item id="e" href="Text/ch&amp;5.xhtml" media-type="application/xhtml+xml"/>
        </manifest><spine>
        <itemref idref="a"/><itemref idref="b"/><itemref idref="c"/><itemref idref="d"/><itemref idref="e"/>
        </spine></package>"""

    private val body =
        "<html><head><title>Chapter</title></head><body><p>" +
            "This chapter body is comfortably longer than the noise filter threshold. ".repeat(3) +
            "</p></body></html>"

    @Test
    fun relativePercentPlusAndEntityHrefsAllImport() {
        val bytes = epub(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
            "OEBPS/Text/ch1.xhtml" to body,
            "Text/ch2.xhtml" to body,
            "OEBPS/Text/ch three.xhtml" to body,
            "OEBPS/Text/ch+four.xhtml" to body,
            "OEBPS/Text/ch&5.xhtml" to body,
        )
        val parsed = EpubParser.parse(ByteArrayInputStream(bytes))
        assertEquals("all five referenced chapters must import", 5, parsed.chapters.size)
    }
}
