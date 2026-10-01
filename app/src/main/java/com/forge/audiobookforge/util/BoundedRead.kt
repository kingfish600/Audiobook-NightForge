package com.forge.audiobookforge.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a stream into memory, refusing to buffer more than [limit] bytes.
 *
 * Files the user chooses are untrusted input: a "pick a voice clip" action can be pointed
 * at a podcast or a whole album, and reading that whole exhausts memory before any
 * validation gets a chance to reject it. Bounding the read turns an OutOfMemoryError crash
 * into a clean, reportable error.
 */
object BoundedRead {

    class TooLarge(val limitBytes: Long) : Exception("Input exceeds $limitBytes bytes")

    fun readAtMost(input: InputStream, limitBytes: Long): ByteArray {
        require(limitBytes >= 0) { "bad limit" }
        val out = ByteArrayOutputStream(minOf(limitBytes, 256L * 1024).toInt())
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limitBytes) throw TooLarge(limitBytes)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
