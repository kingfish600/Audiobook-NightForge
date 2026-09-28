package com.forge.audiobookforge

import com.forge.audiobookforge.audio.BookExporter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A wrong MIME type makes MediaStore reject an export, and the codec is a user
 * setting — so the mapping between the written file and the type it is published
 * as is worth pinning down for all three formats.
 */
class ExportMimeTest {

    @Test
    fun eachFormatIsPublishedWithItsOwnType() {
        assertEquals("audio/ogg", BookExporter.mimeFor("001 - Chapter.ogg"))
        assertEquals("audio/wav", BookExporter.mimeFor("001 - Chapter.wav"))
        assertEquals("audio/mp4", BookExporter.mimeFor("001 - Chapter.m4a"))
    }

    @Test
    fun theMatchIgnoresCaseAndUnknownExtensionsFallBackToMp4() {
        assertEquals("audio/ogg", BookExporter.mimeFor("CHAPTER.OGG"))
        assertEquals("audio/wav", BookExporter.mimeFor("Chapter.WaV"))
        assertEquals("audio/mp4", BookExporter.mimeFor("something.bin"))
    }
}
