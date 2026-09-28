package com.forge.audiobookforge

import com.forge.audiobookforge.audio.RefAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloning feeds this reader straight into native code, where a misread header
 * crashes the process instead of throwing. Every unusable case must return null.
 */
class RefAudioTest {

    private fun wav(samples: ShortArray, rate: Int, channels: Int = 1, bits: Int = 16, fmt: Int = 1): ByteArray {
        val bytesPerSample = bits / 8
        val dataSize = samples.size * bytesPerSample
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataSize); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(fmt.toShort())
        buf.putShort(channels.toShort()); buf.putInt(rate)
        buf.putInt(rate * channels * bytesPerSample)
        buf.putShort((channels * bytesPerSample).toShort()); buf.putShort(bits.toShort())
        buf.put("data".toByteArray()); buf.putInt(dataSize)
        if (bits == 16) samples.forEach { buf.putShort(it) } else repeat(dataSize) { buf.put(0) }
        return buf.array()
    }

    @Test
    fun readsPcm16Mono() {
        val samples = shortArrayOf(0, 16384, -16384, 32767)
        val result = RefAudio.readBytes(wav(samples, 22_050))
        assertEquals(22_050, result!!.second)
        assertEquals(4, result.first.size)
        assertEquals(0.5f, result.first[1], 0.001f)
        assertEquals(-0.5f, result.first[2], 0.001f)
    }

    @Test
    fun downmixesStereoToMono() {
        // frame 1: left 32767, right 0  ->  0.5
        val interleaved = shortArrayOf(32767, 0, 0, 32767)
        val result = RefAudio.readBytes(wav(interleaved, 44_100, channels = 2))
        assertEquals(44_100, result!!.second)
        assertEquals(2, result.first.size)
        assertEquals(0.5f, result.first[0], 0.01f)
        assertEquals(0.5f, result.first[1], 0.01f)
    }

    @Test
    fun readsFloat32() {
        val buf = ByteBuffer.allocate(44 + 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + 8); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(3); buf.putShort(1)
        buf.putInt(24_000); buf.putInt(24_000 * 4); buf.putShort(4); buf.putShort(32)
        buf.put("data".toByteArray()); buf.putInt(8)
        buf.putFloat(0.25f); buf.putFloat(-0.25f)
        val result = RefAudio.readBytes(buf.array())
        assertEquals(24_000, result!!.second)
        assertEquals(0.25f, result.first[0], 0.001f)
        assertEquals(-0.25f, result.first[1], 0.001f)
    }

    @Test
    fun refusesFormatsWeCannotFeedToNativeCode() {
        assertNull("8-bit", RefAudio.readBytes(wav(shortArrayOf(1, 2), 16_000, bits = 8)))
        assertNull("24-bit", RefAudio.readBytes(wav(shortArrayOf(1, 2), 16_000, bits = 24)))
        assertNull("absurd rate", RefAudio.readBytes(wav(shortArrayOf(1, 2), 4_000)))
        assertNull("not a wav", RefAudio.readBytes(ByteArray(64)))
        assertNull("truncated", RefAudio.readBytes(ByteArray(10)))
    }

    /**
     * A chunk size is read as a SIGNED int, so 0xFFFFFFF8 arrives as -8 and
     * "pos += 8 + sz" advances the cursor by zero — an endless loop, on the main
     * thread, reached straight from the file picker (a permanent ANR). Worse values
     * threw StringIndexOutOfBoundsException out of the callback. A timeout is used
     * deliberately: without the bound this test fails rather than hanging the suite.
     */
    @Test(timeout = 5_000)
    fun aHostileChunkSizeCannotStallOrThrow() {
        for (declared in listOf(0xFFFFFFF8L, 0xFFFFFFF7L, 0xFFFFFFF9L, 0x80000000L, 0xFFFFFFFFL, 0x7FFFFFFFL)) {
            val bytes = wavWithChunkDeclaring(declared)
            assertNull(
                "a chunk declaring 0x${declared.toString(16)} must be refused",
                RefAudio.readBytes(bytes),
            )
        }
    }

    /** RIFF + fmt + a chunk whose declared size is [declared] + a well-formed data chunk. */
    private fun wavWithChunkDeclaring(declared: Long): ByteArray {
        val buf = ByteBuffer.allocate(12 + 24 + 16 + 8 + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(0); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(22_050); buf.putInt(44_100); buf.putShort(2); buf.putShort(16)
        buf.put("junk".toByteArray()); buf.putInt(declared.toInt()); buf.put(ByteArray(16))
        buf.put("data".toByteArray()); buf.putInt(8); buf.put(ByteArray(8))
        return buf.array()
    }

    /**
     * A zero-length ancillary chunk is legal (and common). Rejecting it refused valid
     * files: tightening the walk to stop a hostile size made `step == 8` look hostile,
     * when it simply means "empty chunk, advance 8".
     */
    @Test
    fun acceptsAFileWithAnEmptyAncillaryChunk() {
        val buf = ByteBuffer.allocate(12 + 24 + 8 + 8 + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(0); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(22_050); buf.putInt(44_100); buf.putShort(2); buf.putShort(16)
        buf.put("junk".toByteArray()); buf.putInt(0)                 // empty chunk
        buf.put("data".toByteArray()); buf.putInt(8); buf.put(ByteArray(8))
        val r = RefAudio.readBytes(buf.array())
        assertNotNull("an empty ancillary chunk must not disqualify the file", r)
        assertEquals(22_050, r!!.second)
        assertEquals(4, r.first.size)
    }

    @Test
    fun resampleKeepsDurationAndStaysInRange() {
        val src = FloatArray(1000) { 0.5f }
        val out = RefAudio.resample(src, 16_000, 24_000)
        assertEquals(1500, out.size)
        assertTrue(out.all { it > -1.1f && it < 1.1f })
    }
}
