package com.forge.audiobookforge

import com.forge.audiobookforge.util.SafeWrite
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Crash-safety guard for small-file writes.
 *
 * `File.writeText` truncates the target before writing, so a process death in
 * between leaves a torn file — and the library reads an unparseable book.json as
 * "no book", i.e. a silently lost book.
 */
class SafeWriteTest {

    @Test
    fun writesContentAndLeavesNoTempFile() {
        val dir = File.createTempFile("safewrite", "").let { it.delete(); it.mkdirs(); it }
        val target = File(dir, "book.json")
        try {
            SafeWrite.text(target, """{"title":"Dune"}""")
            assertEquals("""{"title":"Dune"}""", target.readText())
            assertFalse("no stray temp file", File(dir, "book.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun replacesExistingContent() {
        val dir = File.createTempFile("safewrite", "").let { it.delete(); it.mkdirs(); it }
        val target = File(dir, "book.json")
        try {
            SafeWrite.text(target, "old-and-much-longer-content")
            SafeWrite.text(target, "new")
            assertEquals("new", target.readText())
            assertFalse(File(dir, "book.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
