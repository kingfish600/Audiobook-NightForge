package com.forge.audiobookforge

import com.forge.audiobookforge.data.parser.EPUB_TOO_LARGE
import com.forge.audiobookforge.data.parser.MAX_TXT_BYTES
import com.forge.audiobookforge.data.parser.oversizedTextMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rejection the user can cause must say what happened and what to do about it.
 *
 * The bounded reader's exception reads "Input exceeds 67108864 bytes" and reached the user
 * verbatim through the import result — a crash-looking message on a path that is completely
 * recoverable. This is the same "feedback must communicate" class the whole project kept
 * tripping over, so it is pinned rather than trusted.
 */
class RejectionMessageTest {

    private fun looksLikeCrashSpeak(m: String) =
        Regex("\\b\\d{6,}\\b").containsMatchIn(m) || m.contains("Exception") || m.contains("exceeds")

    @Test
    fun theTextFileMessageIsHuman() {
        val m = oversizedTextMessage(MAX_TXT_BYTES)
        assertFalse("must not read like a stack trace: $m", looksLikeCrashSpeak(m))
        assertTrue("should say how big is too big: $m", m.contains("64 MB"))
        assertTrue("should tell the user what to do: $m", m.contains("splitting") || m.contains("convert"))
    }

    @Test
    fun theEpubMessageIsHuman() {
        assertFalse(looksLikeCrashSpeak(EPUB_TOO_LARGE))
        assertTrue(EPUB_TOO_LARGE.contains("300 MB"))
        assertTrue("should suggest a remedy", EPUB_TOO_LARGE.contains("Try"))
    }

    @Test
    fun theLimitsQuotedAreTheRealOnes() {
        // A message that names a different ceiling than the code enforces is worse than none.
        assertTrue(oversizedTextMessage(MAX_TXT_BYTES).contains("${MAX_TXT_BYTES / (1024 * 1024)} MB"))
        assertTrue(EPUB_TOO_LARGE.contains("300 MB"))
    }
}
