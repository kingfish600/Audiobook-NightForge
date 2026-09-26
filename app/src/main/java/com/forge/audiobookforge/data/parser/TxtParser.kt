package com.forge.audiobookforge.data.parser

import com.forge.audiobookforge.util.TextOps
import java.io.File

/** Plain-text book parser with chapter-heading heuristics. */
object TxtParser {

    /**
     * Decodes a text file the way a reader would. Reading everything as UTF-8
     * silently turned every byte of a GBK/UTF-16 book into U+FFFD, which then
     * produced a single garbage "Part 1" chapter with no warning.
     */
    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        runCatching { return strict.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        // GB18030 is a superset of GBK and ships with Android.
        runCatching { return String(bytes, charset("GB18030")) }
        return String(bytes, Charsets.UTF_8) // last resort: readable-ish, never a crash
    }

    private val headingPatterns = listOf(
        Regex("""^\s{0,4}(chapter|CHAPTER|Chapter)\s+([0-9]{1,3}|[IVXLCDM]+|[a-z]+)\b.*$"""),
        Regex("""^\s{0,4}(prologue|epilogue|PROLOGUE|EPILOGUE|foreword|afterword)\s*$"""),
        Regex("""^\s{0,4}第[一二三四五六七八九十百千0-9两]+章.*$"""),
    )

    fun parse(file: File): EpubParser.ParsedBook {
        val text = decodeText(file.readBytes())
        val chapters = detectChapters(text)
        // Internal storage names are meaningless ("source"); let the caller fall
        // back to whatever the file picker reported instead.
        val derivedTitle = sequenceOf(file.nameWithoutExtension, file.name)
            .firstOrNull { it.isNotBlank() && it != "source" && it != "download" && !it.startsWith("source.") }
        return EpubParser.ParsedBook(derivedTitle, null, chapters)
    }

    /** Shared with [PdfParser]: heading heuristics, falling back to ~4k-char parts. */
    fun detectChapters(text: String): List<EpubParser.ParsedChapter> {
        val lines = text.lines()
        val headingIdx = ArrayList<Pair<Int, String>>() // line index, title
        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.length > 80) return@forEachIndexed
            if (headingPatterns.any { it.matches(line) }) headingIdx += i to trimmed
        }

        val chapters = if (headingIdx.size >= 2) {
            val out = ArrayList<EpubParser.ParsedChapter>()
            for ((n, h) in headingIdx.withIndex()) {
                val start = h.first + 1
                val end = headingIdx.getOrNull(n + 1)?.first ?: lines.size
                val body = lines.subList(start, end).joinToString("\n").trim()
                if (body.isNotEmpty()) out += EpubParser.ParsedChapter(h.second, body)
            }
            out
        } else {
            // No reliable headings: split the whole story into ~4k-char parts at
            // sentence boundaries (packSentences merges across paragraphs, so
            // dialogue-heavy stories don't explode into one part per spoken line).
            TextOps.packSentences(TextOps.sentences(text), maxLen = 4000)
                .mapIndexed { i, c -> EpubParser.ParsedChapter("Part ${i + 1}", c) }
        }.filter { it.text.isNotBlank() }

        require(chapters.isNotEmpty()) { "Text produced no readable content" }
        return chapters
    }
}
