package com.forge.audiobookforge

import com.forge.audiobookforge.util.TextOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOpsTest {

    @Test
    fun `chunks never exceed max length and keep sentences whole`() {
        val text = "This is the first sentence. Here comes another one! " +
            "And a third, with a comma, just to be safe. A question follows? Yes."
        val chunks = TextOps.splitIntoChunks(text, maxLen = 60)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue("too long: $it", it.length <= 60) }
        assertEquals(text.replace(Regex("\\s+"), " ").trim(),
            chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim())
    }

    @Test
    fun `oversized sentence is hard split`() {
        val monster = (1..200).joinToString(" ") { "word$it" }
        val chunks = TextOps.splitIntoChunks(monster, maxLen = 80)
        assertTrue(chunks.size > 2)
        chunks.forEach { assertTrue(it.length <= 80) }
    }
}
