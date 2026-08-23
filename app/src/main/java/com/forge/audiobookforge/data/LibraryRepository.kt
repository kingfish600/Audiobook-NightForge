package com.forge.audiobookforge.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.data.model.Chapter
import com.forge.audiobookforge.data.parser.EpubParser
import com.forge.audiobookforge.data.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class LibraryRepository(private val context: Context) {

    @PublishedApi internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val root: File get() = File(context.filesDir, "library").apply { mkdirs() }

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    init { reload() }

    @Synchronized
    fun reload() {
        _books.value = root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                runCatching {
                    json.decodeFromString<Book>(File(dir, "book.json").readText())
                }.getOrNull()
            }
            ?.sortedByDescending { it.importedAtEpochMs }
            ?: emptyList()
    }

    fun book(id: String?): Book? = _books.value.firstOrNull { it.id == id }

    fun dirFor(bookId: String): File = File(root, bookId)

    fun audioDir(bookId: String): File = File(dirFor(bookId), "audio")

    /**
     * Removes the book entirely: source file, rendered chapters, and database
     * record. A tombstone marker blocks any late writes from a worker that is
     * still tearing down; call [deleteBook] again after ~2 s to sweep anything
     * the dying worker managed to recreate.
     */
    @Synchronized
    fun deleteBook(bookId: String) {
        runCatching { tombstoneFile(bookId).createNewFile() }
        dirFor(bookId).deleteRecursively()
        reload()
    }

    private fun tombstoneFile(bookId: String): File = File(root, ".$bookId.deleted")

    @Synchronized
    fun save(book: Book) {
        // A book pending deletion must never be resurrected by a dying worker's
        // final progress write.
        if (tombstoneFile(book.id).exists()) return
        val dir = dirFor(book.id).apply { mkdirs() }
        File(dir, "book.json").writeText(json.encodeToString(Book.serializer(), book))
        _books.value = _books.value.toList() // poke observers
    }

    sealed interface ImportResult {
        data class Success(val book: Book) : ImportResult
        data class Error(val message: String) : ImportResult
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val cr = context.contentResolver
            val display_name = queryDisplayName(uri) ?: ""

            val id = UUID.randomUUID().toString().take(8)
            val dir = dirFor(id).apply { mkdirs() }
            val download = File(dir, "download")
            cr.openInputStream(uri)!!.use { input ->
                download.outputStream().use { input.copyTo(it) }
            }

            // Trust content over filenames: SAF display names are unreliable
            // (null, missing extensions, wrong extensions all happen).
            val head = download.inputStream().use { input ->
                val buf = ByteArray(4); val n = input.read(buf); buf.copyOf(n)
            }
            val looksEpub = head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() // "PK" zip magic
            val looksPdf = head.size >= 4 && head[0] == '%'.code.toByte() &&
                head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
            val nameExt = display_name.substringAfterLast('.', "").lowercase()
            val ext = when (nameExt) {
                "epub" -> "epub"
                "txt", "text" -> "txt"
                "pdf" -> "pdf"
                else -> when {
                    looksEpub -> "epub"
                    looksPdf -> "pdf"
                    else -> "txt"
                }
            }
            val sourceFile = File(dir, "source.$ext")
            if (!download.renameTo(sourceFile)) {
                download.copyTo(sourceFile, overwrite = true)
                download.delete()
            }

            val parsed = when (ext) {
                "epub" -> EpubParser.parse(sourceFile.inputStream())
                "pdf" -> com.forge.audiobookforge.data.parser.PdfParser.parse(sourceFile)
                else -> TxtParser.parse(sourceFile)
            }

            val book = Book(
                id = id,
                title = parsed.title
                    ?: display_name.substringBeforeLast('.').ifBlank { "Untitled" },
                author = parsed.author ?: "",
                sourceFileName = sourceFile.name,
                importedAtEpochMs = System.currentTimeMillis(),
                chapters = parsed.chapters.mapIndexed { i, ch ->
                    Chapter(index = i, title = ch.title, text = ch.text)
                },
            )
            save(book)
            reload()
            ImportResult.Success(book)
        } catch (t: Throwable) {
            ImportResult.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    @Synchronized
    fun delete(book: Book) {
        dirFor(book.id).deleteRecursively()
        reload()
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
