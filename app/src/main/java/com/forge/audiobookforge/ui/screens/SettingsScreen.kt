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
    val int8 by container.settings.preferInt8.collectAsState()
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
                    "One engine is installed at a time — getting another replaces it.",
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
                com.forge.audiobookforge.tts.ModelManager.CATALOG.forEach { opt ->
                    val installed = modelUi.optionId == opt.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                opt.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            installed -> Text(
                                "Installed",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            !modelUi.downloading -> TextButton(
                                onClick = { scope.launch { container.models.download(opt) } },
                            ) { Text("Get") }
                        }
                    }
                }
                if (!modelUi.ready && modelUi.error != null) {
                    Text(modelUi.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { container.models.deleteModel() }) { Text("Delete installed model") }
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
                    Switch(checked = int8, onCheckedChange = { container.settings.setPreferInt8(it) })
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Prefer int8 weights (smaller download; often *slower* than full on flagship chips)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = charging, onCheckedChange = { container.settings.setRequireCharging(it) })
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Render only while charging (default)")
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
                    }) { Text("Allow unrestricted background rendering…") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { container.kokoroEngine.release() }) {
            Text("Unload engine from memory")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Audiobook Forge ${com.forge.audiobookforge.BuildConfig.VERSION_NAME} — renders EPUB/TXT books to .m4a " +
                "chapters fully offline using Kokoro-82M via sherpa-onnx. Rendering runs as background work and can be " +
                "restricted to charging state so it never touches your battery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
