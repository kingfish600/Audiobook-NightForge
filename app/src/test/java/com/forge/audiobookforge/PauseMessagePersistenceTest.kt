package com.forge.audiobookforge

import com.forge.audiobookforge.conversion.ConversionController
import com.forge.audiobookforge.conversion.ConversionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery pause must leave a message the user can actually read.
 *
 * The v0.9.50 implementation posted a notification and then had it cancelled by the worker's
 * own `finally` milliseconds later, so an overnight render stopped for no visible reason —
 * indistinguishable from a crash. The fix routes the explanation through `fail()`, which
 * `endRun()` deliberately preserves. That preservation is now load-bearing for this feature,
 * so it is pinned here: a reasonable-looking "tidy up endRun" would otherwise silently
 * reintroduce the bug.
 */
class PauseMessagePersistenceTest {

    @Test
    fun aPauseMessageSurvivesTheRunEnding() {
        val c = ConversionController()
        val run = c.beginRun()
        val note = "Paused — battery at 15%. Plug in and press Continue; finished chapters are kept."

        c.fail(note, "book-123")
        c.endRun(run)

        val state = c.state.value
        assertTrue("the pause message must outlive endRun", state is ConversionState.Failed)
        state as ConversionState.Failed
        assertEquals(note, state.message)
        assertEquals("it must stay scoped to the book it stopped", "book-123", state.bookId)
    }

    @Test
    fun anOrdinaryRunStillEndsIdle() {
        val c = ConversionController()
        c.endRun(c.beginRun())
        assertTrue("a run that merely stopped shows nothing", c.state.value is ConversionState.Idle)
    }

    @Test
    fun theNextRunClearsAStalePause() {
        val c = ConversionController()
        c.fail("Paused — battery at 9%.", "book-123")
        c.endRun(1L)
        // Starting again (after the user plugs in and presses Continue) must not leave the
        // old stop reason on screen, or every later run inherits it.
        c.beginRun()
        assertTrue(c.state.value is ConversionState.Idle)
    }

    @Test
    fun aPauseIsScopedSoOnlyItsOwnBookShowsIt() {
        val c = ConversionController()
        c.beginRun()
        c.fail("Paused — battery at 15%.", "book-A")
        assertEquals("book-A", (c.state.value as ConversionState.Failed).bookId)
    }
}
