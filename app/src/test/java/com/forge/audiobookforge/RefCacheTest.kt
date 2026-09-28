package com.forge.audiobookforge

import com.forge.audiobookforge.audio.RefCache
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A cloning engine reads the same reference clip for every chunk of a book, so
 * the decoded copy is cached — but never served stale after the file changes.
 */
class RefCacheTest {

    private fun wavFile(value: Short = 1000): File {
        val samples = 200
        val data = samples * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + data); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(24_000); buf.putInt(48_000); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(data)
        repeat(samples) { buf.putShort(value) }
        val f = File.createTempFile("ref", ".wav")
        f.writeBytes(buf.array())
        return f
    }

    @Test
    fun decodesOnceAndReusesTheResult() {
        val cache = RefCache()
        val f = wavFile()
        try {
            val first = cache.samples(f)
            val second = cache.samples(f)
            assertNotNull(first)
            assertEquals(24_000, first!!.second)
            assertEquals("the same decode is reused", first, second)
            assertEquals(1, cache.size())
        } finally {
            f.delete()
        }
    }

    @Test
    fun aReplacedClipIsNeverServedStale() {
        val cache = RefCache()
        val f = wavFile(value = 1000)
        try {
            val first = cache.samples(f)
            // change the content and make sure the timestamp/size differ
            f.writeBytes(wavFile(value = 9000).readBytes())
            f.setLastModified(f.lastModified() + 2000)
            val second = cache.samples(f)
            assertNotSame(first!!.first, second!!.first)
            assertEquals(9000.toShort() / 32768f, second.first[0], 0.001f)
        } finally {
            f.delete()
        }
    }

    @Test
    fun unusableAudioIsNotCached() {
        val cache = RefCache()
        val f = File.createTempFile("bad", ".wav")
        try {
            f.writeBytes(ByteArray(300))
            assertNull(cache.samples(f))
            assertEquals(0, cache.size())
        } finally {
            f.delete()
        }
    }
}
