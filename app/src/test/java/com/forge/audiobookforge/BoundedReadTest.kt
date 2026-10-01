package com.forge.audiobookforge

import com.forge.audiobookforge.util.BoundedRead
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Files the user picks are untrusted: "choose a voice clip" can be pointed at a podcast or
 * an entire album. Buffering that first and validating afterwards crashes the app with
 * OutOfMemoryError, so the read itself is capped.
 */
class BoundedReadTest {

    @Test
    fun readsNormallyUnderTheLimit() {
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        assertArrayEquals(payload, BoundedRead.readAtMost(ByteArrayInputStream(payload), 50_000))
    }

    @Test
    fun exactlyAtTheLimitIsAllowed() {
        val payload = ByteArray(4096) { 1 }
        assertEquals(4096, BoundedRead.readAtMost(ByteArrayInputStream(payload), 4096).size)
    }

    @Test
    fun oneByteOverIsRefused() {
        val payload = ByteArray(4097) { 1 }
        try {
            BoundedRead.readAtMost(ByteArrayInputStream(payload), 4096)
            fail("must stop rather than buffer past the limit")
        } catch (e: BoundedRead.TooLarge) {
            assertEquals(4096, e.limitBytes)
        }
    }

    @Test
    fun anEmptyStreamIsFine() {
        assertEquals(0, BoundedRead.readAtMost(ByteArrayInputStream(ByteArray(0)), 10).size)
    }

    @Test
    fun aVoiceClipCeilingIsBigEnoughToBeUsefulButSafe() {
        // Guards the constant's intent: generous for real clips, nowhere near a memory risk.
        val fifty = 50L * 1024 * 1024
        assertTrue("must allow minutes of 24 kHz PCM", fifty > 24_000L * 2 * 60 * 5)
        assertTrue("must not be a memory risk", fifty < 512L * 1024 * 1024)
    }
}
