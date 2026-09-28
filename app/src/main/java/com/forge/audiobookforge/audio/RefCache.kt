package com.forge.audiobookforge.audio

import java.io.File

/**
 * Decoded reference clips, reused across synthesis calls.
 *
 * A cloning engine reads the same reference clip for every chunk, so a long book
 * decodes and parses that file hundreds of times. (Upstream sherpa-onnx confirms
 * caching the speaker conditioning is worthwhile — issue #3439 — and we can skip
 * at least the file read and WAV parse on our side.)
 *
 * Keyed by path + mtime + size, so replacing or editing a clip is never served
 * stale. Small and bounded: the library holds a handful of voices at most.
 */
class RefCache(private val maxEntries: Int = 8) {

    private val entries = LinkedHashMap<String, Pair<FloatArray, Int>>()

    /** Decoded mono samples and sample rate, or null when the clip is unusable. */
    fun samples(wav: File): Pair<FloatArray, Int>? {
        val key = "${wav.absolutePath}:${wav.lastModified()}:${wav.length()}"
        synchronized(this) { entries[key]?.let { return it } }
        // Decode outside the lock: a slow read must not block other callers.
        val decoded = RefAudio.read(wav) ?: return null
        synchronized(this) {
            if (entries.size >= maxEntries) entries.clear()
            entries[key] = decoded
        }
        return decoded
    }

    fun clear() = synchronized(this) { entries.clear() }

    internal fun size(): Int = synchronized(this) { entries.size }
}
