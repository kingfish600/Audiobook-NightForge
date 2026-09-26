package com.forge.audiobookforge

import com.forge.audiobookforge.audio.M4bExporter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the Nero `chpl` chapter box.
 *
 * The layout must match what FFmpeg (mov_write_chpl_tag) and GPAC (chpl_box_read)
 * implement: 4B version+flags (0x01000000), 4B reserved, 1B chapter count, then
 * per chapter 8B start (100ns units) + 1B title length + title.
 *
 * The previous writer emitted a 32-bit count and 32-bit start/end pairs. That
 * shifted every field, so a compliant parser read count = 0 and saw NO chapters
 * at all (demonstrated with ffprobe before this fix), and any chapter starting
 * past 3.58 minutes overflowed a signed 32-bit value.
 */
class ChplChapterBoxTest {

    private fun payload(vararg pairs: Pair<String, Long>): ByteBuffer =
        ByteBuffer.wrap(
            M4bExporter.chplPayloadForTest(pairs.map { M4bExporter.Entry(it.first, it.second) })
        ).order(ByteOrder.BIG_ENDIAN)

    @Test
    fun headerIsVersion1WithEightBitCount() {
        val buf = payload("Opening" to 0L, "Chapter Two" to 600_000L)
        assertEquals(0x01000000, buf.int)          // version 1, flags 0
        assertEquals(0, buf.int)                   // reserved
        assertEquals(2, buf.get().toInt() and 0xFF) // 8-bit chapter count
    }

    @Test
    fun startTimesAre64BitAndSurvivePastTheOld32BitCeiling() {
        val startMs = 45 * 60_000L // 45 min; the old signed-32-bit ceiling was 3.58 min
        val buf = payload("Long Chapter" to startMs)
        buf.int; buf.int; buf.get()
        assertEquals(startMs * 10_000L, buf.long)  // 27,000,000,000 > Int.MAX_VALUE
    }

    @Test
    fun titleFollowsItsStartField() {
        val buf = payload("Opening" to 0L)
        buf.int; buf.int; buf.get()
        buf.long
        val len = buf.get().toInt() and 0xFF
        val bytes = ByteArray(len).also { buf.get(it) }
        assertEquals("Opening", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun payloadSizeIsHeaderPlusEntries() {
        val p = M4bExporter.chplPayloadForTest(
            listOf(M4bExporter.Entry("A", 0L), M4bExporter.Entry("BB", 1_000L))
        )
        // 9-byte header + per chapter (8 start + 1 length + title bytes)
        assertEquals(9 + (9 + 1) + (9 + 2), p.size)
    }

    @Test
    fun chapterCountSaturatesAt255() {
        val many = (0 until 300).map { M4bExporter.Entry("c$it", it * 1_000L) }
        val buf = ByteBuffer.wrap(M4bExporter.chplPayloadForTest(many)).order(ByteOrder.BIG_ENDIAN)
        buf.int; buf.int
        assertEquals(255, buf.get().toInt() and 0xFF)
    }

    @Test
    fun longTitlesAreTruncatedOnCodePointBoundaries() {
        val emoji = "\uD83D\uDE00" // 4 UTF-8 bytes
        val payload = M4bExporter.chplPayloadForTest(
            listOf(M4bExporter.Entry(emoji.repeat(100), 0L))
        )
        val len = payload[17].toInt() and 0xFF
        val title = payload.copyOfRange(18, 18 + len)
        assertEquals(0, title.size % 4)                                   // whole code points
        assertEquals(len, String(title, Charsets.UTF_8).toByteArray(Charsets.UTF_8).size)
        assertTrue(len <= 255)
    }

    @Test
    fun verifyChplParsesTheBoxAndRejectsACountMismatch() {
        fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(8 + payload.size)
            ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(out.size)
            type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
            payload.copyInto(out, 8)
            return out
        }
        val payload = M4bExporter.chplPayloadForTest(
            listOf(M4bExporter.Entry("A", 0L), M4bExporter.Entry("B", 1_000L))
        )
        val f = java.io.File.createTempFile("chpl", ".mp4")
        try {
            f.writeBytes(box("moov", box("udta", box("chpl", payload))))
            assertTrue("well-formed box must verify", M4bExporter.verifyChplForTest(f, 2))
            assertFalse("count mismatch must fail", M4bExporter.verifyChplForTest(f, 5))
        } finally {
            f.delete()
        }
    }
}
