package com.forge.audiobookforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.audiobookforge.data.LibraryRepository
import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.di.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val books by container.library.books.collectAsState()
    val modelUi by container.models.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { androidx.compose.runtime.mutableStateOf<com.forge.audiobookforge.data.model.Book?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val r = container.library.import(uri)) {
                    is LibraryRepository.ImportResult.Success -> {}
                    is LibraryRepository.ImportResult.Error -> snackbar.showSnackbar(r.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/epub+zip", "text/plain", "application/pdf", "application/octet-stream"))
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Add a book") },
                text = { Text("Import EPUB / TXT") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                modelUi.downloading -> item { ModelProgressCard() }
                !modelUi.ready -> item { ModelBanner() }
            }

            if (books.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No books yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Import an EPUB, TXT, or PDF file, then forge it into an audiobook\nright on this phone — no internet needed after setup.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(books, key = { it.id }) { book ->
                BookCard(
                    book,
                    onClick = { onOpenBook(book.id) },
                    onDelete = { pendingDelete = book },
                )
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }

    pendingDelete?.let { doomed ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this book?") },
            text = {
                Text("“${doomed.title}” and all of its rendered audio will be permanently removed from the device.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val book = doomed
                    pendingDelete = null
                    scope.launch {
                        // Cancel any active render first, then delete. A tombstone
                        // marker blocks late writes from the dying worker, and a
                        // delayed sweep catches anything it recreated mid-teardown.
                        androidx.work.WorkManager.getInstance(context)
                            .cancelUniqueWork("convert_${book.id}")
                        val st = container.conversion.state.value
                        if (st is com.forge.audiobookforge.conversion.ConversionState.Running && st.bookId == book.id) {
                            container.conversion.markUiStopped()
                        }
                        if (container.player.ui.value.bookId == book.id) container.player.stopAll()
                        container.library.deleteBook(book.id)
                        kotlinx.coroutines.delay(2_000)          // let the worker finish dying…
                        container.library.deleteBook(book.id)    // …then sweep any zombie files
                        snackbar.showSnackbar("Deleted “${book.title}”")
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelProgressCard() {
    val container = LocalAppContainer.current
    val modelUi by container.models.ui.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Downloading voice model…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                modelUi.phaseLabel.ifEmpty { "Working…" } +
                    if (!modelUi.indeterminate) " · ${(modelUi.progress * 100).toInt()}% of archive" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (modelUi.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { modelUi.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The download lands in cache first, then extracts into app storage — the app's reported size will jump around until it finishes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelBanner() {
    val container = LocalAppContainer.current
    val modelUi by container.models.ui.collectAsState()
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Step 1 — Get the voice engine (required)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Before any book can be rendered, download a voice model once. " +
                    "It lives inside the app afterwards — no internet needed from then on. " +
                    "The Lite engine is smaller and faster; the standard one sounds better.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            modelUi.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !modelUi.downloading,
                onClick = { scope.launch { container.models.download(com.forge.audiobookforge.tts.ModelManager.CATALOG[1]) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Recommended: Kokoro full precision · ≈440 MB") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !modelUi.downloading,
                onClick = { scope.launch { container.models.download(com.forge.audiobookforge.tts.ModelManager.CATALOG[2]) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Lite version for modest phones · ≈30 MB") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Both sound great — full precision renders fastest on modern flagship chips; " +
                    "the smaller int8 variant lives in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookCard(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { if (book.chapters.isEmpty()) 0f else book.doneCount.toFloat() / book.chapters.size },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        " ${book.doneCount}/${book.chapters.size}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            androidx.compose.material3.IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete book", tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}
