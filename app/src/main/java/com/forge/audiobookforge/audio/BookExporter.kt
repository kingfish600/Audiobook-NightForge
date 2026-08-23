package com.forge.audiobookforge.audio

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.forge.audiobookforge.data.model.Book
import java.io.File

/**
 * Copies rendered chapters into the device's public music collection so any
 * player can find them: Music/AudiobookForge/<Book Title>/NNN - <Chapter>.m4a
 * Uses MediaStore (no storage permission needed on API 29+).
 */
object BookExporter {

    fun export(book: Book, audioDir: File, context: Context): Int {
        val resolver = context.contentResolver
        val safeTitle = sanitize(book.title)
        val relDir = "Music/AudiobookForge/$safeTitle"
        var exported = 0
        for (ch in book.chapters) {
            val src = ch.audioFile?.let { File(audioDir, it) } ?: continue
            if (!src.isFile) continue
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "%03d - %s.m4a".format(ch.index + 1, sanitize(ch.title)))
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
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

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ()'._-]"), "_").trim().take(64).ifEmpty { "book" }
}
