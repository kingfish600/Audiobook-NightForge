package com.forge.audiobookforge

import com.forge.audiobookforge.data.parser.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading every text file as UTF-8 silently replaced each byte of a GBK or
 * UTF-16 book with U+FFFD, producing one garbage chapter and no warning.
 */
class TextEncodingTest {

    private val chinese = "第一章 红楼梦\n这是正文内容。"

    @Test
    fun utf8IsDecodedAsIs() {
        assertEquals(chinese, TxtParser.decodeText(chinese.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun utf8BomIsStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + chinese.toByteArray(Charsets.UTF_8)
        assertEquals(chinese, TxtParser.decodeText(bytes))
    }

    @Test
    fun utf16LeWithBomIsDecoded() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals(chinese, TxtParser.decodeText(bom + chinese.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun gb18030IsDecodedInsteadOfBecomingReplacementCharacters() {
        val bytes = chinese.toByteArray(charset("GB18030"))
        val decoded = TxtParser.decodeText(bytes)
        assertEquals(chinese, decoded)
        assertTrue("no replacement characters", !decoded.contains('\uFFFD'))
    }
}
