package com.forge.audiobookforge

import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.data.model.Chapter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A book records the engine it was first forged with, so that switching engines and
 * resuming cannot silently change the narrator part-way through. The field must also be
 * backward compatible: books saved before it existed have no such key.
 */
class BookEngineMemoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun book(engineId: String?) = Book(
        id = "b1",
        title = "A Book",
        sourceFileName = "a.txt",
        importedAtEpochMs = 1L,
        chapters = listOf(Chapter(index = 0, title = "One", text = "Hello there.", charCount = 12)),
        engineId = engineId,
    )

    @Test
    fun aBookSavedBeforeThisFieldExistedStillLoads() {
        val old = """
            {"id":"b1","title":"Old Book","sourceFileName":"o.txt","importedAtEpochMs":1,
             "chapters":[{"index":0,"title":"One","text":"Hi.","charCount":3}]}
        """.trimIndent()
        val loaded = json.decodeFromString<Book>(old)
        assertNull("an older book simply has no recorded engine", loaded.engineId)
        assertEquals("Old Book", loaded.title)
    }

    @Test
    fun theEngineIdSurvivesARoundTrip() {
        val saved = json.encodeToString(Book.serializer(), book("kokoro-int8"))
        assertEquals("kokoro-int8", json.decodeFromString<Book>(saved).engineId)
    }

    @Test
    fun theVoiceChoiceAndEngineAreIndependent() {
        // A book can be forged with one engine and re-voiced with a cloned voice later;
        // recording one must not disturb the other.
        val b = book("piper-lessac").apply { cloneName = "My voice"; voiceSid = 7 }
        val back = json.decodeFromString<Book>(json.encodeToString(Book.serializer(), b))
        assertEquals("piper-lessac", back.engineId)
        assertEquals("My voice", back.cloneName)
        assertEquals(7, back.voiceSid)
    }
}
