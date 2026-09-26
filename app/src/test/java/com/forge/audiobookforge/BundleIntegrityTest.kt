package com.forge.audiobookforge

import com.forge.audiobookforge.tts.ModelManager
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for bundle integrity.
 *
 * Existence checks alone accepted a truncated download (right filenames, wrong
 * bytes). The native engine then died inside its own code, where no Java
 * exception can be caught — the user sees a bare force close.
 */
class BundleIntegrityTest {

    private fun bundle(modelBytes: Int, tokenBytes: Int = 200): File {
        val dir = File.createTempFile("bundle", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "model.int8.onnx").writeBytes(ByteArray(modelBytes))
        File(dir, "tokens.txt").writeBytes(ByteArray(tokenBytes))
        return dir
    }

    @Test
    fun truncatedModelIsRejected() {
        val dir = bundle(modelBytes = 4096) // a "model" of 4 KB is certainly wreckage
        try {
            assertFalse("tiny model must not validate", ModelManager.isValidBundleForTest(dir))
            assertFalse(ModelManager.bundleSizeSane(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun plausibleBundleIsAccepted() {
        val dir = bundle(modelBytes = 1_200_000)
        try {
            assertTrue(ModelManager.bundleSizeSane(dir))
            assertTrue(ModelManager.isValidBundleForTest(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyTokensFileIsRejected() {
        val dir = bundle(modelBytes = 1_200_000, tokenBytes = 3)
        try {
            assertFalse("an empty/garbage tokens.txt is not a working bundle", ModelManager.bundleSizeSane(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
