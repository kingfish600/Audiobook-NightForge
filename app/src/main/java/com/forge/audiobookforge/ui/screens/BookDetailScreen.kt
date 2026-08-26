package com.forge.audiobookforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.audiobookforge.conversion.ConversionState
import com.forge.audiobookforge.conversion.ConversionWorker
import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.data.model.ChapterStatus
import com.forge.audiobookforge.di.LocalAppContainer
import com.forge.audiobookforge.tts.Voices
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BookDetailScreen(bookId: String?) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val books by container.library.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId }
    val conversion by container.conversion.state.collectAsState()
    val settings by container.settings.requireCharging.collectAsState()
    val modelUi by container.models.ui.collectAsState()

    if (book == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("Book not found.") }
        return
    }

    val running = (conversion as? ConversionState.Running)?.takeIf { it.bookId == book.id }
    val failed = conversion as? ConversionState.Failed
    val runningElsewhere = (conversion as? ConversionState.Running)?.takeIf { it.bookId != book.id }

    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var pendingM4b by remember { mutableStateOf<java.io.File?>(null) }
    val m4bLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-m4b"),
    ) { uri ->
        val src = pendingM4b
        if (uri != null && src != null) {
            snackbarScope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open destination")
                }.onSuccess {
                    src.delete()
                    pendingM4b = null
                    snackbar.showSnackbar("Single-file audiobook saved")
                }.onFailure { t ->
                    snackbar.showSnackbar("m4b export failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        } else {
            src?.delete()
            pendingM4b = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))

                    // Voice picker (single-voice engines like Piper Lite have nothing to pick)
                    val singleVoice = container.kokoroEngine.isLoaded &&
                        container.kokoroEngine.numSpeakers() <= 1
                    var voiceMenu by remember { mutableStateOf(false) }
                    OutlinedButton(enabled = !singleVoice, onClick = { voiceMenu = true }) {
                        Text(
                            if (singleVoice) "Voice: built-in"
                            else "Voice: ${Voices.displayName(book.voiceSid)}"
                        )
                    }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        Voices.ALL.forEach { v ->
                            DropdownMenuItem(
                                text = { Text("${v.description} — ${v.name}") },
                                onClick = {
                                    book.voiceSid = v.sid
                                    container.library.save(book)
                                    voiceMenu = false
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    var previewBusy by remember { mutableStateOf(false) }
                    OutlinedButton(
                        enabled = modelUi.ready && !previewBusy,
                        onClick = {
                            previewBusy = true
                            snackbarScope.launch {
                                val err = container.previewVoice(book)
                                previewBusy = false
                                if (err != null) snackbar.showSnackbar(err)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text(if (previewBusy) "  Generating preview…" else "  Preview this voice & speed")
                    }

                    Spacer(Modifier.height(6.dp))
                    var speed by remember(book.id) { mutableFloatStateOf(book.speed) }
                    Text("Speed: ${"%.2f".format(speed)}×", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = speed,
                        onValueChange = {
                            speed = it
                            book.speed = it // live write-through so any recomposition agrees
                        },
                        onValueChangeFinished = { container.library.save(book) },
                        valueRange = 0.5f..2.0f,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings,
                            onCheckedChange = { v ->
                                container.settings.setRequireCharging(v)
                                // Apply immediately: if THIS book is rendering right now,
                                // restart its job under the new constraint. Finished
                                // chapters are kept; only the in-flight chapter redoes.
                                val st = conversion
                                if (st is ConversionState.Running && st.bookId == book.id) {
                                    ConversionWorker.enqueue(context, book.id, requireCharging = v)
                                    snackbarScope.launch {
                                        snackbar.showSnackbar(
                                            if (v) "Resuming under charging-only — will pause when unplugged"
                                            else "Resuming without the charging requirement"
                                        )
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Forge only while charging",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Each chapter is forged in the background while you go about your day — or overnight. Keep it plugged in for best results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    failed?.let {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Forge failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                                Text(it.message, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { container.conversion.idle() }) { Text("Dismiss") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    val notifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                    if (!notifsEnabled) {
                        Text(
                            "Notifications are off for this app — forging still works, but you won't see progress in the notification shade.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    when {
                        running != null && running.chapterIndex < 0 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Starting engine…", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    ConversionWorker.cancel(context, book.id, container.conversion)
                                    container.conversion.markUiStopped()
                                    snackbarScope.launch { snackbar.showSnackbar("Stopping — finishing the current audio segment…") }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Icon(Icons.Filled.Pause, contentDescription = "Stop forging"); Text("  Stop") }
                        }
                        running != null -> {
                            LinearProgressIndicator(
                                progress = { running.overallFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${running.chapterTitle} · ${running.chaptersDone}/${running.chaptersTotal} chapters · RTF ${"%.2f".format(running.lastChunkRtf)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = {
                                    ConversionWorker.cancel(context, book.id, container.conversion)
                                    container.conversion.markUiStopped()
                                    snackbarScope.launch { snackbar.showSnackbar("Stopping — finishing the current audio segment…") }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Icon(Icons.Filled.Pause, contentDescription = "Stop forging"); Text("  Stop forging") }
                        }
                        modelUi.ready -> {
                            val bookComplete = book.chapters.isNotEmpty() &&
                                book.chapters.all { it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE }
                            Button(
                                enabled = runningElsewhere == null && !bookComplete,
                                onClick = {
                                    ConversionWorker.enqueue(context, bookId!!, requireCharging = settings)
                                if (container.settings.forgeScreen.value == "night") {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                context,
                                                com.forge.audiobookforge.ui.ForgeNightActivity::class.java,
                                            ).putExtra("bookId", bookId)
                                        )
                                    }
                                }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Start forging")
                                Text(
                                    when {
                                        bookComplete -> "  All chapters forged ✓"
                                        book.doneCount > 0 -> "  Continue forging"
                                        else -> "  Forge audiobook"
                                    }
                                )
                            }
                            if (book.doneCount > 0) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        snackbarScope.launch {
                                            val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.forge.audiobookforge.audio.BookExporter.export(
                                                    book,
                                                    container.library.audioDir(book.id),
                                                    context,
                                                    container.settings.exportTreeUri.value,
                                                )
                                            }
                                            snackbar.showSnackbar(
                                                if (count > 0) "Exported $count chapter(s)"
                                                else "Nothing exported — no finished chapter files found"
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Export ${book.doneCount} chapter(s) to Music library") }

                                Spacer(Modifier.height(8.dp))
                                if (!com.forge.audiobookforge.audio.M4bExporter.requiresAac(book)) {
                                    TextButton(
                                        onClick = {
                                            snackbarScope.launch {
                                                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    val tmp = java.io.File(context.cacheDir, "${book.title}.m4b")
                                                    com.forge.audiobookforge.audio.M4bExporter.export(
                                                        book,
                                                        container.library.audioDir(book.id),
                                                        tmp,
                                                    )
                                                }
                                                pendingM4b = res.file
                                                m4bLauncher.launch("${book.title}.m4b")
                                                snackbar.showSnackbar("Bundled ${res.chapters} chapters — choose where to save")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Export single-file .m4b (with chapters)") }
                                }
                            }
                        }
                        else -> {
                            Text(
                                "One-time setup: this app needs a voice model before it can render.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                enabled = !modelUi.downloading,
                                onClick = { snackbarScope.launch { container.models.download(com.forge.audiobookforge.tts.ModelManager.CATALOG[1]) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download voice model (≈440 MB)") }
                            if (modelUi.downloading) {
                                Spacer(Modifier.height(6.dp))
                                if (modelUi.indeterminate) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(
                                        progress = { modelUi.progress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Text(
                                    modelUi.phaseLabel + if (!modelUi.indeterminate) " · ${(modelUi.progress * 100).toInt()}%" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        items(book.chapters, key = { it.index }) { ch ->
            val renderPct = (conversion as? ConversionState.Running)
                ?.takeIf { it.bookId == book.id && it.chapterIndex == ch.index }
                ?.let { r -> r.charsDoneInChapter.toFloat() / r.charsTotalInChapter.coerceAtLeast(1) }
            ChapterRow(book, ch, renderPct = renderPct, onTap = {
                if (ch.status == ChapterStatus.DONE && ch.audioFile != null) {
                    container.player.playBook(book, ch.index)
                } else if (ch.status == ChapterStatus.FAILED) {
                    snackbarScope.launch { snackbar.showSnackbar("Chapter failed last time — re-run Forge to retry it.") }
                } else if (ch.status == ChapterStatus.RENDERING) {
                    snackbarScope.launch { snackbar.showSnackbar("“${ch.title}” is being forged right now.") }
                } else {
                    snackbarScope.launch { snackbar.showSnackbar("Not forged yet — tap “Forge audiobook” first.") }
                }
            })
        }
        item { Spacer(Modifier.height(90.dp)) }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ChapterRow(
    book: Book,
    chapter: com.forge.audiobookforge.data.model.Chapter,
    renderPct: Float? = null,
    onTap: () -> Unit,
) {
    val audioExists = chapter.audioFile?.let {
        File(LocalAppContainer.current.library.audioDir(book.id), it).isFile()
    } == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        when {
            chapter.status == ChapterStatus.DONE ->
                Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
            chapter.status == ChapterStatus.FAILED ->
                Icon(Icons.Filled.Error, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
            chapter.status == ChapterStatus.RENDERING && renderPct != null ->
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { renderPct },
                    modifier = Modifier.width(20.dp).height(20.dp),
                    strokeWidth = 2.5.dp,
                )
            chapter.status == ChapterStatus.RENDERING ->
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    strokeWidth = 2.5.dp,
                )
            else ->
                Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "Pending", tint = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chapter.title, maxLines = 1)
            Text(
                when {
                    chapter.durationMs > 0 -> fmtDur(chapter.durationMs)
                    chapter.status == ChapterStatus.RENDERING && renderPct != null ->
                        "forging · ${(renderPct * 100).toInt()}%"
                    chapter.status == ChapterStatus.RENDERING -> "forging…"
                    chapter.status == ChapterStatus.PENDING -> "${chapter.charCount} chars"
                    else -> chapter.status.name.lowercase()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chapter.status == ChapterStatus.DONE && audioExists) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play chapter", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun fmtDur(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
