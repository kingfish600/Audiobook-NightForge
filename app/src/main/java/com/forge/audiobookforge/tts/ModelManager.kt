package com.forge.audiobookforge.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and installs TTS model bundles from the k2-fsa release assets.
 * Multiple engines are supported (studio-grade Kokoro, lightweight Piper/VITS);
 * one model is installed at a time.
 */
class ModelManager(private val context: Context) {

    enum class EngineKind { KOKORO, VITS }

    data class ModelOption(
        val id: String,
        val title: String,
        val subtitle: String,
        val url: String,
        val kind: EngineKind,
        val recommended: Boolean = false,
    )

    data class ModelUi(
        val ready: Boolean = false,
        val modelDir: File? = null,
        val optionId: String? = null,
        val int8Available: Boolean = false,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val phaseLabel: String = "",
        val error: String? = null,
    )

    private val modelsRoot: File get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _ui = MutableStateFlow(detect())
    val ui: StateFlow<ModelUi> = _ui.asStateFlow()

    fun detect(): ModelUi {
        // Known catalog locations first (plus legacy "kokoro" dir from earlier builds).
        for (opt in CATALOG) {
            val dir = dirFor(opt)
            if (KokoroEngine.chooseModelFile(dir, preferInt8 = true) != null &&
                File(dir, "tokens.txt").isFile()
            ) {
                return ModelUi(
                    ready = true,
                    modelDir = dir,
                    optionId = opt.id,
                    int8Available = File(dir, "model.int8.onnx").isFile(),
                )
            }
        }
        // Sideloaded/unknown layout: any dir with a model file + tokens.txt.
        val any = modelsRoot.listFiles { f -> f.isDirectory }
            ?.firstOrNull { KokoroEngine.chooseModelFile(it, preferInt8 = true) != null && File(it, "tokens.txt").isFile() }
        return ModelUi(
            ready = any != null,
            modelDir = any,
            optionId = null,
            int8Available = any != null && File(any, "model.int8.onnx").isFile(),
        )
    }

    private fun dirFor(opt: ModelOption): File =
        if (opt.id == CATALOG.first().id) {
            // prefer the stable id, but accept the legacy name
            val stable = File(modelsRoot, opt.id)
            if (stable.isDirectory) stable else File(modelsRoot, "kokoro")
        } else {
            File(modelsRoot, opt.id)
        }

    suspend fun download(option: ModelOption) = withContext(Dispatchers.IO) {
        try {
            update {
                it.copy(
                    downloading = true, error = null, progress = 0f,
                    phaseLabel = "Downloading ${option.title}…",
                )
            }
            val archive = File(context.cacheDir, "${option.id}.tar.bz2")
            downloadFile(option.url, archive)

            update { it.copy(progress = 0f, phaseLabel = "Extracting…") }
            val stage = File(modelsRoot, "stage").apply { deleteRecursively(); mkdirs() }
            extractTarBz2(archive, stage)
            archive.delete()

            val inner = stage.listFiles { f -> f.isDirectory }?.firstOrNull() ?: stage
            val target = File(modelsRoot, option.id)
            target.deleteRecursively()
            if (!inner.renameTo(target)) {
                inner.copyRecursively(target, overwrite = true)
                inner.deleteRecursively()
            }
            stage.deleteRecursively()

            // One model at a time: remove other catalog dirs + legacy dir.
            CATALOG.filter { it.id != option.id }.forEach { other ->
                File(modelsRoot, other.id).deleteRecursively()
            }
            if (option.id != "kokoro") File(modelsRoot, "kokoro").deleteRecursively()

            val detected = detect()
            check(detected.ready) { "Extraction finished but no usable model files were found" }
            update { detected.copy(phaseLabel = "Ready") }
        } catch (t: Throwable) {
            update { it.copy(downloading = false, error = t.message ?: t.javaClass.simpleName, phaseLabel = "") }
        }
    }

    fun deleteModel() {
        modelsRoot.listFiles { f -> f.isDirectory }?.forEach { it.deleteRecursively() }
        _ui.value = detect()
    }

    private fun update(f: (ModelUi) -> ModelUi) { _ui.value = f(_ui.value) }

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "AudiobookForge/0.1")
        conn.connect()
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} downloading model" }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L; var n: Int
                var lastPct = -1
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n); read += n
                    if (total > 0) {
                        val pct = ((read * 100) / total).toInt()
                        if (pct != lastPct && pct % 2 == 0) {
                            lastPct = pct
                            update { it.copy(progress = read.toFloat() / total) }
                        }
                    }
                }
            }
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile) continue
                val outFile = File(destDir, entry.name).canonicalFile
                check(outFile.path.startsWith(destDir.canonicalPath)) { "Bad archive entry: ${entry.name}" }
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tar.copyTo(it) }
            }
        }
    }

    companion object {
        const val KOKORO_INT8_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
        const val KOKORO_FULL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
        const val PIPER_MEDIUM_INT8_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium-int8.tar.bz2"

        val CATALOG = listOf(
            ModelOption(
                id = "kokoro-int8",
                title = "Kokoro 82M · int8",
                subtitle = "Studio quality, multilingual — recommended · ≈126 MB",
                url = KOKORO_INT8_URL,
                kind = EngineKind.KOKORO,
                recommended = true,
            ),
            ModelOption(
                id = "kokoro-fp32",
                title = "Kokoro 82M · full precision",
                subtitle = "Maximum quality, multilingual · ≈440 MB",
                url = KOKORO_FULL_URL,
                kind = EngineKind.KOKORO,
            ),
            ModelOption(
                id = "piper-lessac",
                title = "Piper Lite · int8",
                subtitle = "Tiny & fast for modest phones, English only · ≈30 MB",
                url = PIPER_MEDIUM_INT8_URL,
                kind = EngineKind.VITS,
            ),
        )
    }
}
