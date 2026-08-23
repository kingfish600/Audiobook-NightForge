package com.forge.audiobookforge.util

/**
 * Pure-JVM text utilities shared by the ebook parsers and the TTS chunker.
 * No Android dependencies here so they are unit-testable.
 */
object TextOps {

    /** Split raw text into paragraphs on blank-line or single-newline boundaries. */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n|\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Split into sentences, keeping terminal punctuation attached. */
    fun sentences(text: String): List<String> {
        val parts = Regex("(?<=[.!?…。！？])\\s+").split(text.trim())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Pack sentences into pieces of at most [maxLen] characters.
     * Oversized sentences are hard-split at word boundaries.
     */
    fun packSentences(sentences: List<String>, maxLen: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (s.length > maxLen) {
                if (cur.isNotBlank()) { out += cur.toString().trim(); cur.setLength(0) }
                out += hardSplit(s, maxLen)
            } else if (cur.length + s.length + 1 > maxLen) {
                if (cur.isNotBlank()) { out += cur.toString().trim() }
                cur.setLength(0); cur.append(s)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    /** Sentence-aware chunking used before TTS synthesis.
     *  Packs across paragraph boundaries — dialogue-heavy books have hundreds
     *  of one-line paragraphs, and per-paragraph chunking turns them into
     *  hundreds of tiny engine calls that dominate render time. */
    fun splitIntoChunks(text: String, maxLen: Int = 280): List<String> =
        packSentences(sentences(text), maxLen)

    private fun hardSplit(s: String, maxLen: Int): List<String> {
        val words = s.split(Regex("\\s+"))
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (w in words) {
            if (cur.length + w.length + 1 > maxLen && cur.isNotEmpty()) {
                out += cur.toString().trim(); cur.setLength(0)
            }
            // single monstrous token: chop it
            if (w.length > maxLen) {
                var i = 0
                while (i < w.length) {
                    out += w.substring(i, minOf(i + maxLen, w.length))
                    i += maxLen
                }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    private fun StringBuilder.isNotBlank(): Boolean = this.any { !it.isWhitespace() }
}
