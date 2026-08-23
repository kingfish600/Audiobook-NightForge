package com.forge.audiobookforge.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.forge.audiobookforge.data.model.Book
import java.io.File

/**
 * Copies rendered chapters somewhere other players can reach them.
 *  - Default: public music collection via MediaStore
 *    (Music/AudiobookForge/<Book Title>/NNN - <Chapter>.m4a|.ogg)
 *  - Optional: a user-chosen folder (SAF document tree) picked in Settings.
 * No storage permission needed on API 29+ for either path.
 */
object BookExporter {

    fun export(book: Book, audioDir: File, context: Context, treeUriString: String? = null): Int =
        if (treeUriString != null) {
            runCatching { exportToTree(book, audioDir, context, Uri.parse(treeUriString)) }
                .getOrDefault(0)
        } else {
            exportToMediaStore(book, audioDir, context)
        }

    // ---------- default MediaStore path ----------

    private fun exportToMediaStore(book: Book, audioDir: File, context: Context): Int {
        val resolver = context.contentResolver
        val relDir = "Music/AudiobookForge/${sanitize(book.title)}"
        var exported = 0
        for (chapter in book.chapters) {
            val src = chapter.audioFile?.let { File(audioDir, it) } ?: continue
            if (!src.isFile) continue
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, docName(chapter.index, chapter.title, src))
                put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(src.name))
                put(MediaStore.Audio.Media.RELATIVE_PATH, relDir)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                exported++
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        return exported
    }

    // ---------- user-chosen SAF folder ----------

    private fun exportToTree(book: Book, audioDir: File, context: Context, treeUri: Uri): Int {
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val bookTitle = sanitize(book.title)
        val bookDir = childByName(context, root, bookTitle)
            ?: DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, bookTitle)
            ?: return 0

        var exported = 0
        for (chapter in book.chapters) {
            val src = chapter.audioFile?.let { File(audioDir, it) } ?: continue
            if (!src.isFile) continue
            val name = docName(chapter.index, chapter.title, src)
            // Replace an earlier copy of the same chapter instead of duplicating.
            childByName(context, bookDir, name)?.let { resolver.delete(it, null, null) }
            val doc = DocumentsContract.createDocument(resolver, bookDir, mimeFor(src.name), name) ?: continue
            try {
                resolver.openOutputStream(doc)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                exported++
            } catch (t: Throwable) {
                runCatching { resolver.delete(doc, null, null) }
            }
        }
        return exported
    }

    private fun childByName(context: Context, parentDocUri: Uri, displayName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDocUri, DocumentsContract.getDocumentId(parentDocUri),
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(parentDocUri, c.getString(0))
                }
            }
        }
        return null
    }

    // ---------- helpers ----------

    private fun docName(index: Int, title: String, src: File): String =
        "%03d - %s.%s".format(index + 1, sanitize(title), src.extension)

    private fun mimeFor(fileName: String): String =
        when { fileName.endsWith(".ogg", true) -> "audio/ogg"; fileName.endsWith(".wav", true) -> "audio/wav"; else -> "audio/mp4" }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ()'._-]"), "_").trim().take(64).ifEmpty { "book" }
}
