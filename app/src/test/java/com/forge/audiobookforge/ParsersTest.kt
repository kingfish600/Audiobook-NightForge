package com.forge.audiobookforge

import com.forge.audiobookforge.data.parser.HtmlText
import com.forge.audiobookforge.data.parser.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ParsersTest {

    @Test
    fun `html is stripped to clean text`() {
        val html = """
            <html><head><title>Chapter One</title><style>p{color:red}</style></head>
            <body><h1>Chapter One</h1><p>Hello &amp; welcome &#8212; friend.</p>
            <script>evil()</script><p>Second&nbsp;para</p></body></html>
        """.trimIndent()
        val title = HtmlText.extractTitle(html)
        val text = HtmlText.extractText(html)
        assertEquals("Chapter One", title)
        assertTrue(text.contains("Hello & welcome"))
        assertTrue(!text.contains("evil()") && !text.contains("color:red"))
    }

    @Test
    fun `txt chapters are detected by headings`() {
        val f = Files.createTempFile("book", ".txt").toFile()
        f.writeText(
            """
            Chapter 1
            Once upon a time there was a little engine that could, and she kept saying I think I can.
            
            Chapter 2
            The winter of the deep snow arrived early that year and stayed longer than anyone remembered.
            """.trimIndent()
        )
        val parsed = TxtParser.parse(f)
        assertEquals(listOf("Chapter 1", "Chapter 2"), parsed.chapters.map { it.title })
        assertTrue(parsed.chapters[0].text.startsWith("Once upon"))
        f.delete()
    }

    @Test
    fun `txt without headings falls back to parts`() {
        val f = Files.createTempFile("book", ".txt").toFile()
        val body = (1..400).joinToString(" ") { "Sentence number $it says something." }
        f.writeText(body)
        val parsed = TxtParser.parse(f)
        assertTrue(parsed.chapters.size > 1)
        assertTrue(parsed.chapters.first().title.startsWith("Part "))
        f.delete()
    }
}
