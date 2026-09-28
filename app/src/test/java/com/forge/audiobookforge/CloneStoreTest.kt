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

    private fun wav(rate: Int = 22_050, samples: Int = 100): ByteArray {
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
