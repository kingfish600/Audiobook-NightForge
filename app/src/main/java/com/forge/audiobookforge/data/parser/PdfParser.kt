package com.forge.audiobookforge.data.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Born-digital PDF parser: extracts selectable text page-by-page, then reuses
 * the TXT chapter heuristics. Scanned/image-only PDFs contain no text and are
 * rejected with a clear message (OCR is out of scope).
 */
/**
 * Ceiling on extracted text. Well beyond any real book (about 12 million characters, or
 * several million words) while keeping the builder far from a memory-exhaustion path.
 */
private const val MAX_PDF_CHARS = 12_000_000

object PdfParser {

    fun parse(file: File): EpubParser.ParsedBook {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val sb = StringBuilder()
            for (page in 1..doc.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                sb.append(stripper.getText(doc)).append('\n')
                // Stop accumulating once there is more text than any real book holds: a
                // malformed or enormous document would otherwise grow this builder without
                // limit and the low-memory killer can abort the process outright.
                if (sb.length > MAX_PDF_CHARS) {
                    throw IllegalArgumentException(
                        "That PDF holds more text than NightForge can import " +
                            "(over ${MAX_PDF_CHARS / 1_000_000} million characters). " +
                            "Try splitting it, or convert it to EPUB.",
                    )
                }
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
