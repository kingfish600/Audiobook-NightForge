package com.forge.audiobookforge

import com.forge.audiobookforge.audio.RefAudio
import com.forge.audiobookforge.audio.VoiceRecorder
import com.forge.audiobookforge.tts.CloneScripts
import com.forge.audiobookforge.tts.CloneStore
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app recording path, end to end minus the microphone: what the recorder
 * writes must be exactly what the cloning engine reads. A mismatch here (rate,
 * channel count, header) is the failure that used to make WAV chapters unplayable,
 * so it is worth pinning down.
 */
class RecordToCloneTest {

    private fun tempDir(): File = File.createTempFile("clones", "").let { it.delete(); it.mkdirs(); it }

    private fun tone(seconds: Double, amplitude: Float = 0.3f, rate: Int = VoiceRecorder.SAMPLE_RATE): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { (sin(it * 2 * PI * 220.0 / rate) * amplitude).toFloat() }
    }

    @Test
    fun aRecordingSurvivesTheRoundTripToAClone() {
        val rate = VoiceRecorder.SAMPLE_RATE
        val samples = tone(8.0, rate = rate)
        val store = CloneStore(tempDir())
        val script = CloneScripts.ALL.first()

        assertNull(store.addRecorded("Test voice", samples, rate, script.text))

        val clone = store.get("Test voice")!!
        val read = RefAudio.read(clone.wav)
        assertNotNull("the engine's reader must accept what the recorder produced", read)
        assertEquals("rate must survive", rate, read!!.second)
        assertEquals("length must survive", samples.size, read.first.size)
        assertEquals(samples[500], read.first[500], 0.001f)

        // They read a prepared script, so the transcript is the script.
        assertEquals(script.text, clone.text)
    }

    @Test
    fun aGoodTakePassesAndBadOnesAreExplained() {
        val good = VoiceRecorder().assess(tone(9.0))
        assertTrue("a 9s take at a normal level is usable", good.usable)
        assertNull(good.warning)

        val short = VoiceRecorder().assess(tone(1.5))
        assertTrue(!short.usable)
        assertTrue(short.warning!!.contains("Too short"))

        val silent = VoiceRecorder().assess(tone(9.0, amplitude = 0.0005f))
        assertTrue(!silent.usable)
        assertTrue(silent.warning!!.contains("silent"))

        val clipped = VoiceRecorder().assess(tone(9.0, amplitude = 1.4f))
        assertTrue(!clipped.usable)
        assertTrue(clipped.warning!!.contains("loud"))

        val empty = VoiceRecorder().assess(FloatArray(0))
        assertTrue(!empty.usable)

        // Audible but thin: usable, still flagged so the user can improve it.
        val quiet = VoiceRecorder().assess(tone(9.0, amplitude = 0.01f))
        assertTrue("a quiet take is still usable", quiet.usable)
        assertTrue(quiet.warning!!.contains("quiet"))

        // Longer than ideal still works — it is a note, not a rejection.
        val long = VoiceRecorder().assess(tone(22.0))
        assertTrue(long.usable)
        assertNotNull(long.warning)
    }
}
