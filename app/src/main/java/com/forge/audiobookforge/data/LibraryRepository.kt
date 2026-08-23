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

    @Synchronized
    fun save(book: Book) {
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
            val display_name = queryDisplayName(uri) ?: "book.epub"
            val ext = display_name.substringAfterLast('.', "epub").lowercase()

            val id = UUID.randomUUID().toString().take(8)
            val dir = dirFor(id).apply { mkdirs() }
            val sourceFile = File(dir, "source.$ext")
            cr.openInputStream(uri)!!.use { input ->
                sourceFile.outputStream().use { input.copyTo(it) }
            }

            val parsed = when (ext) {
                "epub" -> EpubParser.parse(sourceFile.inputStream())
                "txt", "text" -> TxtParser.parse(sourceFile)
                else -> return@withContext ImportResult.Error(
                    "Unsupported format .$ext — EPUB and TXT are supported for now."
                )
            }

            val book = Book(
                id = id,
                title = parsed.title ?: display_name.substringBeforeLast('.'),
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
