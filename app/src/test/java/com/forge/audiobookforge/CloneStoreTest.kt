package com.forge.audiobookforge

import com.forge.audiobookforge.tts.CloneStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cloned-voice library: a clip plus the exact words spoken in it. */
class CloneStoreTest {

    private fun tempDir(): File = File.createTempFile("clones", "").let { it.delete(); it.mkdirs(); it }

    // A real reference clip: the store refuses anything shorter than 2 seconds.
    private fun wav(rate: Int = 22_050, samples: Int = 66_150): ByteArray {
        val data = samples * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + data); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(data)
        repeat(samples) { buf.putShort(1000) }
        return buf.array()
    }

    @Test
    fun addListGetAndDelete() {
        val store = CloneStore(tempDir())
        assertNull(store.add("Morgan", wav(), "Hello, this is my voice."))
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("Morgan", all[0].name)
        assertEquals("Hello, this is my voice.", all[0].text)
        assertNotNull(store.get("Morgan"))

        store.delete("Morgan")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun refusesAClipWithoutTheWordsSpoken() {
        val store = CloneStore(tempDir())
        val err = store.add("Morgan", wav(), "   ")
        assertNotNull("a clone without its transcript degrades badly, so refuse it", err)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun refusesAudioThatIsNotAWav() {
        val store = CloneStore(tempDir())
        assertNotNull(store.add("Morgan", ByteArray(2048) { 7 }, "words"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun importsTheClipsAnEngineBundleShips() {
        val bundle = tempDir()
        val wavs = File(bundle, "test_wavs").apply { mkdirs() }
        File(wavs, "news-female.wav").writeBytes(wav())
        File(wavs, "leijun-1.wav").writeBytes(wav())
        // prompt.txt maps each clip to its exact transcript
        File(wavs, "prompt.txt").writeText(
            "news-female.wav Good evening, and welcome to the news.\n" +
                "leijun-1.wav 那还是三十六年前, 一九八七年.\n"
        )
        val store = CloneStore(tempDir())
        assertEquals(2, store.importBundled(bundle))
        assertEquals("Good evening, and welcome to the news.", store.get("news-female")!!.text)
        assertEquals("那还是三十六年前, 一九八七年.", store.get("leijun-1")!!.text)
        // idempotent: importing twice does not duplicate
        store.importBundled(bundle)
        assertEquals(2, store.list().size)
    }
}

/**
 * An imported clip is checked the moment it is chosen: a file that is not audio, or
 * is audio but far too short to learn a voice from, must be refused rather than
 * silently producing a clone made of noise.
 */
class CloneStoreValidationTest {

    private fun tempDir(): File = File.createTempFile("clones", "").let { it.delete(); it.mkdirs(); it }

    private fun wav(seconds: Double, rate: Int = 22_050): ByteArray {
        val samples = (rate * seconds).toInt()
        val data = samples * 2
        val buf = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + data); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(data)
        repeat(samples) { buf.putShort(1000) }
        return buf.array()
    }

    @Test
    fun goodClipIsAccepted() {
        val store = CloneStore(tempDir())
        assertTrue(store.canUse(wav(8.0)))
        assertNull(store.problemWith(wav(8.0)))
    }

    @Test
    fun nonAudioIsRefusedWithAnExplanation() {
        val store = CloneStore(tempDir())
        val problem = store.problemWith("just some words in a file".toByteArray())
        assertNotNull(problem)
        assertTrue(problem!!.contains("not audio"))
    }

    @Test
    fun emptyAndTruncatedClipsAreRefused() {
        val store = CloneStore(tempDir())
        assertNotNull(store.problemWith(ByteArray(0)))
        // A valid header with almost no audio: parses, but useless to clone from.
        val truncated = wav(8.0).copyOf(44 + 500)
        val problem = store.problemWith(truncated)
        assertNotNull("a 0.01s clip must not be accepted as a voice", problem)
        assertTrue(problem!!.contains("at least"))
    }

    @Test
    fun theLengthLimitMatchesTheRecorder() {
        assertEquals(2.0, CloneStore.MIN_REFERENCE_SECONDS, 0.001)
        assertTrue("the recorder's floor must not be lower", com.forge.audiobookforge.audio.VoiceRecorder.MIN_SECONDS >= 2.0)
    }
}
