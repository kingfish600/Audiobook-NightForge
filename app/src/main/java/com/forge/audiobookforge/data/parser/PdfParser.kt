package com.forge.audiobookforge.data.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Born-digital PDF parser: extracts selectable text page-by-page, then reuses
 * the TXT chapter heuristics. Scanned/image-only PDFs contain no text and are
 * rejected with a clear message (OCR is out of scope).
 */
object PdfParser {

    fun parse(file: File): EpubParser.ParsedBook {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val sb = StringBuilder()
            for (page in 1..doc.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                sb.append(stripper.getText(doc)).append('\n')
            }
            val text = sb.toString().trim()
            require(text.length > 40) {
                "No selectable text in this PDF — it is probably scanned images (OCR not supported)"
            }

            val metaTitle = runCatching { doc.documentInformation?.title }.getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
            val derived = sequenceOf(file.nameWithoutExtension, file.name)
                .firstOrNull { it.isNotBlank() && it != "source" && it != "download" && !it.startsWith("source.") }
            return EpubParser.ParsedBook(metaTitle ?: derived, null, TxtParser.detectChapters(text))
        }
    }
}
