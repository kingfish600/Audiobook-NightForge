package com.forge.audiobookforge

import com.forge.audiobookforge.audio.ChapterBox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locating the `moov` atom is what decides whether chapter markers can be injected at
 * all, and getting it wrong turned a whole M4B export into an opaque framework error.
 * These are the layouts the device actually produced.
 */
class M4bMoovTest {

    /** A `moov` as a real file has it: a movie header child plus some bulk. */
    private fun moovBox(sizeZero: Boolean = false): ByteArray {
        val payload = box("mvhd", ByteArray(112))
        return if (sizeZero) box("moov", payload, declaredSize = 0) else box("moov", payload)
    }

    /** A top-level ISO-BMFF box: 4-byte big-endian size, 4-byte type, payload. */
    private fun box(type: String, payload: ByteArray, declaredSize: Int = payload.size + 8): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = (declaredSize ushr 24).toByte()
        out[1] = (declaredSize ushr 16).toByte()
        out[2] = (declaredSize ushr 8).toByte()
        out[3] = declaredSize.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun fileOf(vararg parts: ByteArray): File {
        val f = File.createTempFile("m4b", ".mp4")
        f.writeBytes(parts.reduce { a, b -> a + b })
        return f
    }

    @Test
    fun findsMoovWhenItEndsAtTheFileEnd() {
        val moov = moovBox()
        val f = fileOf(box("ftyp", ByteArray(16)), box("mdat", ByteArray(500)), moov)
        try {
            val (off, len) = ChapterBox.locateMoovRobust(f)
            assertEquals((f.length() - moov.size).toLong(), off)
            assertEquals(moov.size, len)
        } finally {
            f.delete()
        }
    }

    /**
     * ISO-BMFF lets a box declare size 0 meaning "runs to the end of the file".
     * MediaMuxer does this on some devices — and rejecting it used to fail the export.
     */
    @Test
    fun acceptsMoovDeclaredWithSizeZero() {
        val moov = moovBox(sizeZero = true)
        val f = fileOf(box("ftyp", ByteArray(16)), moov)
        try {
            val (off, len) = ChapterBox.locateMoovRobust(f)
            assertEquals(24L, off)
            assertEquals("size 0 means 'to EOF'", (f.length() - off).toInt(), len)
        } finally {
            f.delete()
        }
    }

    /**
     * The layout that broke the export: ftyp + moov + mdat. The moov is real but not
     * last, and injecting a larger one would shift every chunk offset — so this must
     * be refused rather than edited.
     */
    @Test
    fun refusesAStreamingLayoutWhereMoovIsNotLast() {
        val f = fileOf(
            box("ftyp", ByteArray(16)),
            moovBox(),
            box("mdat", ByteArray(900)),
        )
        try {
            var threw = false
            try {
                ChapterBox.locateMoovRobust(f)
            } catch (e: IllegalStateException) {
                threw = true
                assertTrue("the message must say what was seen", e.message!!.contains("len="))
            }
            assertTrue("a mid-file moov must be refused, not edited", threw)
        } finally {
            f.delete()
        }
    }

    @Test
    fun reportsTheActualSizeWhenNoMoovResolves() {
        // "moov" appearing inside media data, with no real moov box anywhere.
        val payload = ByteArray(400)
        "moov".toByteArray().copyInto(payload, 100)
        val f = fileOf(box("ftyp", ByteArray(16)), box("mdat", payload))
        try {
            ChapterBox.locateMoovRobust(f)
            assertTrue("should not have found a moov", false)
        } catch (e: IllegalStateException) {
            assertTrue("should report the candidate it saw", e.message!!.contains("size="))
        } finally {
            f.delete()
        }
    }
}
