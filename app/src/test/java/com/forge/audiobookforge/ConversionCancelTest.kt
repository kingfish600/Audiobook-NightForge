package com.forge.audiobookforge

import com.forge.audiobookforge.conversion.ConversionController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for stop/cancel ownership.
 *
 * Cancellation used to be a single boolean that every new run cleared. A worker
 * still inside a long native render would therefore read "not cancelled" and
 * keep writing the same chapter file as its replacement. Cancellation is now
 * per run.
 */
class ConversionCancelTest {

    @Test
    fun stopAppliesToItsOwnRunOnlyAndSurvivesANewRun() {
        val c = ConversionController()
        val run1 = c.beginRun()
        assertFalse("a fresh run is not cancelled", c.isCancelled(run1))

        c.requestStop()
        assertTrue("stop applies to the run that requested it", c.isCancelled(run1))

        val run2 = c.beginRun()
        assertFalse("a newer run must not inherit the old stop", c.isCancelled(run2))
        assertTrue("the old run stays cancelled while it drains", c.isCancelled(run1))

        c.requestStop()
        assertTrue(c.isCancelled(run2))
        c.endRun(run2)
        assertTrue("ending a run does not resurrect it", c.isCancelled(run2))
    }

    @Test
    fun idleClearsPendingCancellation() {
        val c = ConversionController()
        val run = c.beginRun()
        c.requestStop()
        c.idle()
        assertFalse(c.isCancelled(run))
    }
}
