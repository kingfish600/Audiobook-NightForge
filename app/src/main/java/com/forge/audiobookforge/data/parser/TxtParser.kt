package com.forge.audiobookforge.data.parser

import com.forge.audiobookforge.util.TextOps
import java.io.File

/** Plain-text book parser with chapter-heading heuristics. */
object TxtParser {

    private val headingPatterns = listOf(
        Regex("""^\s{0,4}(chapter|CHAPTER|Chapter)\s+([0-9]{1,3}|[IVXLCDM]+|[a-z]+)\b.*$"""),
        Regex("""^\s{0,4}(prologue|epilogue|PROLOGUE|EPILOGUE|foreword|afterword)\s*$"""),
        Regex("""^\s{0,4}第[一二三四五六七八九十百千0-9两]+章.*$"""),
    )

    fun parse(file: File): EpubParser.ParsedBook {
        var text = file.readText(Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.removePrefix("\uFEFF")

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
            // No reliable headings: chunk by size at sentence boundaries.
            TextOps.splitIntoChunks(text, maxLen = 3500)
                .mapIndexed { i, c -> EpubParser.ParsedChapter("Part ${i + 1}", c) }
        }.filter { it.text.isNotBlank() }

        require(chapters.isNotEmpty()) { "Text file produced no readable content" }
        return EpubParser.ParsedBook(file.nameWithoutExtension.takeIf { it.isNotBlank() }, null, chapters)
    }
}
