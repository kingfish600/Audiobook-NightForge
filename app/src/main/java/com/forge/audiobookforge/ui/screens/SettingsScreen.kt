package com.forge.audiobookforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forge.audiobookforge.di.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val threads by container.settings.numThreads.collectAsState()
    val charging by container.settings.requireCharging.collectAsState()
    val modelUi by container.models.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("TTS engine", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Install as many engines as you like — each stays until you remove it. " +
                        "Switching takes effect on the next preview or render, with no restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (modelUi.downloading) {
                    Spacer(Modifier.height(8.dp))
                    if (modelUi.indeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { modelUi.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        modelUi.phaseLabel + if (!modelUi.indeterminate) " · ${(modelUi.progress * 100).toInt()}%" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val installedIds = container.models.installedOptionIds()
                com.forge.audiobookforge.tts.ModelManager.CATALOG.forEach { opt ->
                    val installed = opt.id in installedIds
                    val active = modelUi.optionId == opt.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                opt.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                (if (active) "In use · " else "") + opt.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            active -> Text(
                                "Active",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            installed && !modelUi.downloading -> TextButton(
                                onClick = { container.models.useModel(opt.id) },
                            ) { Text("Use") }
                            !modelUi.downloading -> TextButton(
                                onClick = { scope.launch { container.models.download(opt) } },
                            ) { Text("Get") }
                        }
                        if (installed && !modelUi.downloading) {
                            TextButton(onClick = { container.models.deleteModel(opt.id) }) {
                                Text("Remove")
                            }
                        }
                    }
                }

                if (!modelUi.ready && modelUi.error != null) {
                    Text(modelUi.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                modelUi.error?.takeIf { modelUi.ready }?.let {
                    Text("Download failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                modelUi.notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelUi.error != null) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                    )
                }
                if (installedIds.isNotEmpty()) {
                    Row {
                        TextButton(onClick = { container.models.deleteAllModels() }) {
                            Text("Remove all engines")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Cloned voices", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A clone is a short recording plus the exact words spoken in it. " +
                        "The ZipVoice engine then reads your books in that voice — no training, " +
                        "no cloud, nothing leaves the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                var cloneList by remember { mutableStateOf(container.clones.list()) }
                var cloneStatus by remember { mutableStateOf<String?>(null) }
                var pendingWav by remember { mutableStateOf<ByteArray?>(null) }
                var pendingName by remember { mutableStateOf("") }
                var transcript by remember { mutableStateOf("") }
                var askTranscript by remember { mutableStateOf(false) }
                val cloneCtx = androidx.compose.ui.platform.LocalContext.current

                val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri: android.net.Uri? ->
                    if (uri != null) {
                        val bytes = runCatching {
                            cloneCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null) {
                            cloneStatus = "Could not read that file."
                        } else {
                            pendingWav = bytes
                            pendingName = runCatching {
                                cloneCtx.contentResolver
                                    .query(uri, null, null, null, null)
                                    ?.use { c ->
                                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                                    }
                            }.getOrNull()?.substringBeforeLast('.')?.take(40) ?: "My voice"
                            transcript = ""
                            askTranscript = true
                        }
                    }
                }

                if (cloneList.isEmpty()) {
                    Text(
                        "No cloned voices yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cloneList.forEach { c ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("🎙 ${c.name}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                c.text.take(64),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                cloneStatus = "Speaking as ${c.name}…"
                                cloneStatus = container.previewClone(c.name) ?: "That is how ${c.name} sounds."
                            }
                        }) { Text("Hear") }
                        TextButton(onClick = {
                            container.clones.delete(c.name)
                            cloneList = container.clones.list()
                            cloneStatus = "Removed ${c.name}."
                        }) { Text("Remove") }
                    }
                }

                Row {
                    TextButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                        Text("Add a voice from a file")
                    }
                    val engineDir = container.models.ui.value.modelDir
                    if (engineDir != null) {
                        TextButton(onClick = {
                            val n = container.clones.importBundled(engineDir)
                            cloneList = container.clones.list()
                            val clips = java.io.File(engineDir, "test_wavs")
                                .listFiles()?.count { it.isFile } ?: -1
                            cloneStatus = when {
                                n > 0 -> "Imported $n sample voices from the engine."
                                clips > 0 -> "Found $clips clips in ${engineDir.name}/test_wavs but none imported."
                                else -> "No test_wavs in ${engineDir.name} (looked for sample clips)."
                            }
                        }) { Text("Import engine samples") }
                    }
                }
                Text(
                    "Tip: 5–15 seconds of clean speech, and the transcript typed exactly as spoken. " +
                        "A clip with background noise or a guessed transcript clones badly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cloneStatus?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (askTranscript) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { askTranscript = false },
                        title = { Text("What does the clip say?") },
                        text = {
                            Column {
                                Text(
                                    "Type the words spoken in the recording, exactly. The clone copies " +
                                        "the voice; this text is how it learns which sounds belong to it.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.OutlinedTextField(
                                    value = transcript,
                                    onValueChange = { transcript = it },
                                    label = { Text("Transcript") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val bytes = pendingWav
                                val name = pendingName.ifBlank { "My voice" }
                                val err = if (bytes == null) "Nothing to import."
                                          else container.clones.add(name, bytes, transcript)
                                cloneList = container.clones.list()
                                cloneStatus = err ?: "Added “$name”. Pick it on any book screen."
                                askTranscript = false
                            }) { Text("Save voice") }
                        },
                        dismissButton = {
                            TextButton(onClick = { askTranscript = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Synthesis", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("CPU threads: $threads")
                Text(
                    "More isn't better past the sweet spot — for Kokoro, 6 is typically fastest; " +
                        "7–8 can be slower due to thread contention. Piper is less sensitive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { container.settings.setNumThreads(it.toInt().coerceIn(1, 8)) },
                    valueRange = 1f..8f,
                    steps = 6,
                )

                val segLen by container.settings.segmentChars.collectAsState()
                Text("Segment length: $segLen chars")
                Text(
                    "Text synthesized per engine call. Longer segments can be more efficient per " +
                        "character; shorter ones stop sooner. Default 280.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = segLen.toFloat(),
                    onValueChange = { container.settings.setSegmentChars(it.toInt()) },
                    valueRange = 160f..720f,
                    steps = 13,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = charging, onCheckedChange = { container.settings.setRequireCharging(it) })
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Forge only while charging (pauses when unplugged)")
                }
                Spacer(Modifier.height(20.dp))
                Text("While forging, keep screen", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val forgeScreen by container.settings.forgeScreen.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.FilterChip(
                        selected = forgeScreen == "off",
                        onClick = { container.settings.setForgeScreen("off") },
                        label = { Text("Off") },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = if (forgeScreen == "off") MaterialTheme.colorScheme.onSurfaceVariant
                                         else MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (forgeScreen == "off") 1f else 0.6f),
                        ),
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    androidx.compose.material3.FilterChip(
                        selected = forgeScreen == "night",
                        onClick = { container.settings.setForgeScreen("night") },
                        label = { Text("🌙 Night forge") },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = if (forgeScreen == "night") androidx.compose.ui.graphics.Color(0xFF33190A)
                                             else MaterialTheme.colorScheme.surface,
                            labelColor = if (forgeScreen == "night") androidx.compose.ui.graphics.Color(0xFFFF5A1F)
                                         else MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp, if (forgeScreen == "night") androidx.compose.ui.graphics.Color(0xFFFF5A1F)
                                  else MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (forgeScreen) {
                        "night" -> "Night forge: a black fullscreen view holds foreground status so performance clocks persist overnight. Set brightness low first."
                        else -> "Off: no screen handling — background runs may be clock-throttled by your device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                val codec by container.settings.codec.collectAsState()
                Text("Audio format", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.FilterChip(
                        selected = codec == "opus",
                        onClick = { container.settings.setCodec("opus") },
                        label = { Text("Opus · .ogg") },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = if (codec == "opus") androidx.compose.ui.graphics.Color(0xFF33190A)
                                             else MaterialTheme.colorScheme.surface,
                            labelColor = if (codec == "opus") androidx.compose.ui.graphics.Color(0xFFFFB59B)
                                         else MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (codec == "opus") androidx.compose.ui.graphics.Color(0xFFFF5A1F)
                                  else MaterialTheme.colorScheme.outline,
                        ),
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    androidx.compose.material3.FilterChip(
                        selected = codec == "aac",
                        onClick = { container.settings.setCodec("aac") },
                        label = { Text("AAC · .m4a") },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = if (codec == "aac") androidx.compose.ui.graphics.Color(0xFF33190A)
                                             else MaterialTheme.colorScheme.surface,
                            labelColor = if (codec == "aac") androidx.compose.ui.graphics.Color(0xFFFFB59B)
                                         else MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (codec == "aac") androidx.compose.ui.graphics.Color(0xFFFF5A1F)
                                  else MaterialTheme.colorScheme.outline,
                        ),
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    androidx.compose.material3.FilterChip(
                        selected = codec == "wav",
                        onClick = { container.settings.setCodec("wav") },
                        label = { Text("WAV") },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = if (codec == "wav") androidx.compose.ui.graphics.Color(0xFF33190A)
                                             else MaterialTheme.colorScheme.surface,
                            labelColor = if (codec == "wav") androidx.compose.ui.graphics.Color(0xFFFFB59B)
                                         else MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (codec == "wav") androidx.compose.ui.graphics.Color(0xFFFF5A1F)
                                  else MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
                Text(
                    when (codec) {
                        "opus" -> "Opus: smaller files, great quality; supported by modern players. " +
                            "Non-standard sample rates are resampled automatically. Applies to future renders."
                        "wav" -> "WAV: uncompressed — maximum fidelity, enormous files. Best reserved for " +
                            "short content where quality matters more than storage."
                        else -> "AAC (.m4a): plays everywhere. Applies to future renders."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                val exportTree by container.settings.exportTreeUri.collectAsState()
                Text("Export folder", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val exportCtx = androidx.compose.ui.platform.LocalContext.current
                val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                ) { uri: android.net.Uri? ->
                    if (uri != null) {
                        runCatching {
                            exportCtx.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        container.settings.setExportTreeUri(uri.toString())
                    }
                }
                Text(
                    exportTree?.let { "Custom folder selected (chapters land in a sub-folder per book)." }
                        ?: "Default: Music/AudiobookForge in the shared music library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                        Text(if (exportTree == null) "Choose a custom folder…" else "Change folder…")
                    }
                    if (exportTree != null) {
                        Spacer(Modifier.padding(start = 8.dp))
                        TextButton(onClick = { container.settings.setExportTreeUri(null) }) { Text("Use default") }
                    }
                }

                Spacer(Modifier.height(10.dp))
                val ctx = LocalContext.current
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                Text(
                    if (exempt) "Battery optimization: exempt ✓ — background renders keep full speed"
                    else "Battery optimization: ACTIVE — Android may throttle overnight renders heavily",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (!exempt) {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${ctx.packageName}"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Allow unrestricted background forging…") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val crashCtx = LocalContext.current
        val crashFile = remember { com.forge.audiobookforge.CrashRecorder.lastCrashFile(crashCtx) }
        if (crashFile != null) {
            OutlinedButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Audiobook NightForge crash report")
                    putExtra(android.content.Intent.EXTRA_TEXT, runCatching { crashFile.readText() }.getOrDefault(""))
                }
                runCatching {
                    crashCtx.startActivity(
                        android.content.Intent.createChooser(send, "Share crash report")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Text("Share crash report", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { container.kokoroEngine.release() }) {
            Text("Unload engine from memory")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Audiobook NightForge ${com.forge.audiobookforge.BuildConfig.VERSION_NAME} — forges EPUB/TXT/PDF books into " +
                ".ogg/.m4a/.wav chapters fully offline using Kokoro-82M via sherpa-onnx. Forging runs as background work " +
                "and can be restricted to charging state so it never touches your battery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
