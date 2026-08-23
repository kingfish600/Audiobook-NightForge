package com.forge.audiobookforge.data.parser

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Minimal dependency-free EPUB parser:
 * ZIP -> META-INF/container.xml -> OPF (metadata + manifest + spine)
 * -> XHTML docs in spine order -> plain text chapters.
 */
object EpubParser {

    data class ParsedChapter(val title: String, val text: String)
    data class ParsedBook(val title: String?, val author: String?, val chapters: List<ParsedChapter>)

    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024 // safety valve

    fun parse(input: InputStream): ParsedBook {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e: ZipEntry = zip.nextEntry ?: break
                if (!e.isDirectory) {
                    val bytes = zip.readBytes()
                    total += bytes.size
                    check(total <= MAX_TOTAL_BYTES) { "EPUB too large" }
                    entries[e.name] = bytes
                }
                zip.closeEntry()
            }
        }

        val containerXml = entries["META-INF/container.xml"]
            ?: error("Not a valid EPUB: META-INF/container.xml missing")
        val opfPath = findRootfilePath(containerXml.decodeToString())
        val opfBytes = entries[normalize(opfPath)]
            ?: error("EPUB OPF not found at $opfPath")
        val opf = opfBytes.decodeToString()
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.let(::stripTags)
        val author = Regex("<dc:creator[^>]*>(.*?)</dc:creator>", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.let(::stripTags)

        // manifest id -> (href, mediaType)
        val manifest = HashMap<String, Pair<String, String>>()
        for (m in Regex("<item\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            val type = attr(tag, "media-type") ?: ""
            manifest[id] = href to type
        }

        val spineOrder = ArrayList<String>()
        for (m in Regex("<itemref\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(opf)) {
            attr(m.value, "idref")?.let(spineOrder::add)
        }

        val chapters = ArrayList<ParsedChapter>()
        for (idref in spineOrder) {
            val (hrefRaw, mediaType) = manifest[idref] ?: continue
            if (!mediaType.contains("html")) continue
            val href = URLDecoder.decode(hrefRaw.replace("./", ""), "UTF-8")
            val path = normalize(if (opfDir.isEmpty()) href else "$opfDir/$href")
            val docBytes = entries[path] ?: continue
            val html = docBytes.toString(Charsets.UTF_8)
            val bodyText = HtmlText.extractText(html)
            // Drop cover/title/nav noise unconditionally — never waste synthesis on it.
            if (bodyText.length < 40) continue
            val heading = HtmlText.extractTitle(html)
                ?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
            chapters += ParsedChapter(
                title = heading ?: "Chapter ${chapters.size + 1}",
                text = bodyText,
            )
        }

        require(chapters.isNotEmpty()) { "No readable chapters found in EPUB" }
        return ParsedBook(title, author, chapters)
    }

    private fun findRootfilePath(containerXml: String): String =
        Regex("<rootfile\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(containerXml)
            .mapNotNull { attr(it.value, "full-path") }
            .firstOrNull()
            ?: error("container.xml has no rootfile entry")

    private fun attr(tag: String, name: String): String? =
        Regex("$name\\s*=\\s*\"([^\"]*)\"|$name\\s*=\\s*'([^']*)'")
            .find(tag)?.groupValues?.let { g -> g[1].ifEmpty { g.getOrNull(2) } }?.ifEmpty { null }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "").trim()

    private fun normalize(p: String): String {
        val out = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }
}

/** Very small HTML -> plain-text converter with entity decoding. */
object HtmlText {

    fun extractTitle(html: String): String? {
        val t = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1) ?: return null
        return decodeEntities(t.replace(Regex("<[^>]+>"), "")).trim().take(120)
    }

    fun extractText(html: String): String {
        var s = html
        val body = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(s)
        if (body != null) s = body.groupValues[1]
        s = s.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("</(p|div|h[1-6]|li|blockquote|tr)>", RegexOption.IGNORE_CASE), "\n\n")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL), "")
        s = decodeEntities(s)
        // per-line whitespace collapse
        s = s.lines().joinToString("\n") { it.trim().replace(Regex("\\s+"), " ") }.trim()
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s
    }

    fun decodeEntities(s: String): String {
        var out = s
        out = out.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&mdash;", "—")
            .replace("&ndash;", "–").replace("&hellip;", "…").replace("&rsquo;", "'")
            .replace("&lsquo;", "'").replace("&rdquo;", "\"").replace("&ldquo;", "\"")
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toInt().toChar().toString()
        }
        return out
    }
}
