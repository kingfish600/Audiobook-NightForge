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
 *    (Music/Audiobook NightForge/<Book Title>/NNN - <Chapter>.m4a|.ogg)
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
        val relDir = "Music/Audiobook NightForge/${sanitize(book.title)}"
        var exported = 0
        for (chapter in book.chapters) {
            val src = chapter.audioFile?.let { File(audioDir, it) } ?: continue
            if (!src.isFile) continue
            val exportName = docName(chapter.index, chapter.title, src)
            // Written under a temporary name first: the previous export must survive until
            // the replacement is complete, or a failed insert/copy (no space, I/O error)
            // leaves the user with nothing where they had a working file.
            val tempName = exportName.substringBeforeLast('.') + ".tmp." + exportName.substringAfterLast('.')
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, tempName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(src.name))
                put(MediaStore.Audio.Media.RELATIVE_PATH, relDir)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: error("Could not open the export destination")
                // Publish the replacement FIRST (still under its temporary name), so the
                // user has a usable file even if everything after this line fails. Only
                // then retire the earlier copy, and rename last of all.
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                runCatching {
                    resolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND (" +
                            "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\')",
                        arrayOf("$relDir/", exportName, legacyCopyPattern(exportName)),
                    )
                }
                val renamed = runCatching {
                    val rename = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, exportName)
                    }
                    resolver.update(uri, rename, null, null)
                }.getOrDefault(0)
                if (renamed > 0) {
                    exported++
                } else {
                    // The audio is on disk but still under its temporary name. Counted
                    // separately from a clean export so the reported number stays true.
                    android.util.Log.w(
                        "NightForge",
                        "export: $tempName written but not renamed to $exportName",
                    )
                }
            } catch (t: Throwable) {
                // Removes only the half-written new row; the old export is untouched.
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
            // Same discipline as the MediaStore path: write the replacement under a
            // temporary name, retire the old copy only once it is complete, then rename.
            // Deleting first (as this did) meant a failed copy destroyed the previous
            // export — the last real data-loss window in the app.
            val tempName = name.substringBeforeLast('.') + ".tmp." + name.substringAfterLast('.')
            val doc = DocumentsContract.createDocument(resolver, bookDir, mimeFor(src.name), tempName)
                ?: continue
            try {
                resolver.openOutputStream(doc)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: error("Could not open the export destination")
                childByName(context, bookDir, name)?.let { resolver.delete(it, null, null) }
                runCatching { DocumentsContract.renameDocument(resolver, doc, name) }
                exported++
            } catch (t: Throwable) {
                // Removes only the half-written replacement; the old export is intact.
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

    /**
     * LIKE pattern matching the "(2)", "(3)" copies older builds left behind
     * ("name (2).m4a"). `_` is a LIKE wildcard, so it is escaped — a chapter titled
     * "Chapter X1" would otherwise over-match "Chapter X2".
     */
    internal fun legacyCopyPattern(exportName: String): String {
        val base = exportName.substringBeforeLast('.').replace("_", "\\_")
        val ext = exportName.substringAfterLast('.').replace("_", "\\_")
        return "$base (%$ext"
    }

    internal fun mimeFor(fileName: String): String =
        when { fileName.endsWith(".ogg", true) -> "audio/ogg"; fileName.endsWith(".wav", true) -> "audio/wav"; else -> "audio/mp4" }

    /**
     * Keeps Unicode letters/digits. The previous ASCII-only filter turned every
     * non-Latin title into underscores, so 红楼梦 and 西游记 both became "___",
     * landed in the SAME export folder, and overwrote each other.
     */
    internal fun sanitize(name: String): String {
        val cleaned = name.map { c ->
            when {
                c.isLetterOrDigit() || c == ' ' || c in "()'._-" -> c
                else -> '_'
            }
        }.joinToString("").trim().take(64)
        // "." or ".." would resolve to a directory when joined onto a path.
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "book"
        return cleaned
    }
}
