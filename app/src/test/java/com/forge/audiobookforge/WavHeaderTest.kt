package com.forge.audiobookforge

import com.forge.audiobookforge.audio.Wav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the WAV header.
 *
 * The writer declared block-align 1 for 16-bit mono audio. Strict parsers
 * (media3/ExoPlayer among them) reject that with "Expected block size: 2; got: 1",
 * which made every exported WAV chapter unplayable.
 */
class WavHeaderTest {

    @Test
    fun headerIsConsistent16BitMonoPcm() {
        val f = File.createTempFile("wavtest", ".wav")
        try {
            Wav.write(f, FloatArray(1000) { 0.25f }, 24_000)
            val bytes = f.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
            assertEquals(1, buf.getShort(20).toInt())     // PCM
            assertEquals(1, buf.getShort(22).toInt())     // mono
            assertEquals(24_000, buf.getInt(24))          // sample rate
            assertEquals(48_000, buf.getInt(28))          // byte rate = rate x channels x bytes
            assertEquals(2, buf.getShort(32).toInt())     // block align  <-- the bug
            assertEquals(16, buf.getShort(34).toInt())    // bits per sample
            assertEquals(2000, buf.getInt(40))            // data size = 1000 samples x 2 bytes
            assertEquals(36 + 2000, buf.getInt(4))        // RIFF size
            assertEquals(44 + 2000, bytes.size)
        } finally {
            f.delete()
        }
    }
}
