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

    enum class EngineKind { KOKORO, VITS, KITTEN, ZIPVOICE }

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
        val indeterminate: Boolean = false,
        val phaseLabel: String = "",
        val error: String? = null,
        val notice: String? = null,
    )

    private val modelsRoot: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * USB drop-in bay: user-reachable via MTP at
     * Android/data/com.forge.audiobookforge/files/models/<AnyName>/
     * A valid bundle contains a model .onnx + tokens.txt (voices.bin optional).
     */
    val externalModelsRoot: File? =
        context.getExternalFilesDir(null)?.let { File(it, "models").apply { mkdirs() } }

    fun isValidBundle(dir: File): Boolean = bundleComplete(dir)

    /**
     * Family-aware usability. Tokens and a big enough model are not enough for
     * every family: a ZipVoice bundle additionally needs its encoder, decoder and
     * vocoder (the vocoder is fetched separately when the engine is installed).
     */
    private fun bundleComplete(dir: File): Boolean {
        val kind = bundleKind(dir) ?: return false
        if (!bundleTokensOk(dir) || !bundleSizeSane(dir)) return false
        if (kind == EngineKind.ZIPVOICE) {
            return listOf("encoder", "decoder", "vocoder").all { part ->
                dir.listFiles { f -> f.isFile && f.name.startsWith(part) && f.length() > 100_000 }
                    ?.isNotEmpty() == true
            }
        }
        return true
    }

    fun listExternal(): List<File> =
        externalModelsRoot?.listFiles { f -> f.isDirectory }
            ?.filter { isValidBundle(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun activateExternal(dir: File) {
        require(isValidBundle(dir)) { "Not a usable model bundle" }
        prefs().edit().putString("active_model_path", dir.absolutePath).apply()
        _ui.value = detect()
    }

    fun useCatalog() {
        prefs().edit().remove("active_model_path").apply()
        _ui.value = detect()
    }

    private fun prefs() = context.getSharedPreferences("forge_settings", Context.MODE_PRIVATE)

    /** Guards against concurrent installs (double tap on Get/Download). */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Fired whenever the installed set or the active engine changes. The caller
     * releases the native engine so it reloads from the right bundle (a deleted
     * bundle must not stay mapped, and a switched-to engine must actually load).
     */
    var onModelChanged: (() -> Unit)? = null

    private val _ui = MutableStateFlow(detect())
    val ui: StateFlow<ModelUi> = _ui.asStateFlow()

    fun detect(): ModelUi {
        // 1. Explicit user choice wins if still valid (drop-in or catalog path).
        prefs().getString("active_model_path", null)?.let { path ->
            val dir = File(path)
            if (isValidBundle(dir)) {
                // An explicit choice may be a catalog engine or a USB drop-in bundle;
                // report the catalog id when it matches so the settings list can mark
                // the right row as active.
                val catalogId = CATALOG.firstOrNull { dirFor(it).absolutePath == dir.absolutePath }?.id
                return ModelUi(
                    ready = true,
                    modelDir = dir,
                    optionId = catalogId ?: "local:${dir.name}",
                    int8Available = File(dir, "model.int8.onnx").isFile(),
                )
            } else {
                prefs().edit().remove("active_model_path").apply()
            }
        }

        // Known catalog locations first (plus legacy "kokoro" dir from earlier builds).
        for (opt in CATALOG) {
            val dir = dirFor(opt)
            if (isValidBundle(dir)) {
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
            ?.firstOrNull { isValidBundle(it) }
        return ModelUi(
            ready = any != null,
            modelDir = any,
            optionId = null,
            int8Available = any != null && File(any, "model.int8.onnx").isFile(),
        )
    }

    private fun dirFor(opt: ModelOption): File =
        if (opt.id == "kokoro-int8") {
            // prefer the stable id, but accept the legacy name
            val stable = File(modelsRoot, opt.id)
            if (stable.isDirectory) stable else File(modelsRoot, "kokoro")
        } else {
            File(modelsRoot, opt.id)
        }

    suspend fun download(option: ModelOption) = withContext(Dispatchers.IO) {
        // Single-flight. Two installs running at once (a fast double tap) could
        // interleave their staging directories and delete the working model while
        // reporting that nothing had been touched.
        if (!inFlight.compareAndSet(false, true)) return@withContext
        try {
            val replaced = _ui.value.optionId?.let { id -> CATALOG.firstOrNull { it.id == id }?.title }
                ?: _ui.value.modelDir?.let { "the previously installed model" }
            update {
                it.copy(
                    downloading = true, error = null, progress = 0f,
                    phaseLabel = "Downloading ${option.title}…",
                    // Make replacement semantics explicit up front.
                    notice = if (replaced != null) "Will replace $replaced once the download verifies"
                             else "Preparing install…",
                )
            }
            val archive = File(context.cacheDir, "${option.id}.tar.bz2")
            downloadFile(option.url, archive)

            update { it.copy(progress = 0f, indeterminate = true, phaseLabel = "Extracting…") }
            val stage = File(modelsRoot, "stage").apply { deleteRecursively(); mkdirs() }
            var filesSeen = 0
            extractTarBz2(archive, stage) {
                filesSeen = it
                if (it % 50 == 0) {
                    update { ui -> ui.copy(phaseLabel = "Extracting… $it files") }
                }
            }
            update { it.copy(indeterminate = false, phaseLabel = "Extracted $filesSeen files") }
            archive.delete()

            val inner = stage.listFiles { f -> f.isDirectory }?.firstOrNull() ?: stage
            val target = File(modelsRoot, option.id)
            // Transactional swap. The old code deleted the working model BEFORE
            // validating the new one, so an unusable archive left the user with
            // nothing while the message claimed the previous model was untouched.
            // The vocoder is fetched into the STAGED bundle, before the installed model is
            // touched at all. Doing it after the swap meant a 54 MB failure stranded the
            // working engine in ".backup" while the user was told it was "untouched".
            if (option.kind == EngineKind.ZIPVOICE) {
                update { it.copy(indeterminate = true, phaseLabel = "Fetching vocoder (54 MB)…") }
                val vocoder = File(inner, "vocoder.onnx")
                downloadFile(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx",
                    vocoder,
                )
                check(vocoder.length() > 10_000_000) { "Vocoder download failed — nothing was replaced." }
                update { it.copy(indeterminate = false) }
            }

            // A previous failure may have left the only good copy in ".backup"; rescue it
            // before this download creates a fresh backup directory over the top.
            restoreStrandedBackups()
            val backup = File(modelsRoot, option.id + ".backup").apply { deleteRecursively() }
            val hadOld = target.isDirectory && target.renameTo(backup)
            if (!inner.renameTo(target)) {
                inner.copyRecursively(target, overwrite = true)
                inner.deleteRecursively()
            }
            stage.deleteRecursively()

            val newOk = bundleComplete(target)
            if (!newOk) {
                target.deleteRecursively()
                if (hadOld) backup.renameTo(target)
                check(false) {
                    "The downloaded model could not be verified — " +
                        if (hadOld) "your previous model is still in place." else "nothing was installed."
                }
            }
            backup.deleteRecursively()

            // Engines are kept side by side until the user deletes them. Installing
            // one makes it the active choice (previously the others were deleted,
            // and that deletion is what made the new engine active by default).
            prefs().edit().putString("active_model_path", target.absolutePath).apply()
            // Publish first: a listener that inspects models.ui was otherwise handed the
            // PRE-download state, so AppContainer never saw the new engine and the
            // bundled reference clips were not imported on a first ZipVoice install.
            _ui.value = detect()
            onModelChanged?.invoke()

            val detected = detect()
            update {
                detected.copy(
                    phaseLabel = "Ready",
                    notice = "${option.title} installed and made active — other engines stay installed.",
                )
            }
            inFlight.set(false)
        } catch (t: Throwable) {
            inFlight.set(false)
            // Whatever failed, a working engine must not be left sitting in ".backup"
            // (invisible to detect(), and destroyed by "remove all engines").
            restoreStrandedBackups()
            update {
                it.copy(
                    downloading = false,
                    error = t.message ?: t.javaClass.simpleName,
                    notice = "Download failed — your previous model is untouched.",
                    phaseLabel = "",
                )
            }
        }
    }

    /** Catalog ids whose bundles are actually on disk right now. */
    /**
     * Puts back any engine left in a ".backup" directory whose live bundle is missing or
     * incomplete, and clears backups that are no longer needed. Called after a failed
     * install so an interrupted swap can never cost the user a working engine.
     */
    private fun restoreStrandedBackups() {
        runCatching {
            modelsRoot.listFiles { f -> f.isDirectory && f.name.endsWith(".backup") }?.forEach { b ->
                val original = File(modelsRoot, b.name.removeSuffix(".backup"))
                if (!bundleComplete(original)) {
                    // Move the unusable bundle aside and only then promote the backup, so a
                    // failed rename cannot destroy both copies.
                    val bad = File(modelsRoot, original.name + ".unusable")
                    bad.deleteRecursively()
                    if (original.isDirectory && !original.renameTo(bad)) return@forEach
                    if (!b.renameTo(original)) {
                        if (bad.isDirectory) bad.renameTo(original)   // put it back
                        return@forEach
                    }
                    bad.deleteRecursively()
                } else {
                    b.deleteRecursively()
                }
            }
        }
    }

    fun installedOptionIds(): Set<String> =
        CATALOG.filter { isValidBundle(dirFor(it)) }.map { it.id }.toSet()

    /** Make an already-installed engine the active one. */
    fun useModel(id: String) {
        val opt = CATALOG.firstOrNull { it.id == id } ?: return
        val dir = dirFor(opt)
        if (!isValidBundle(dir)) return
        prefs().edit().putString("active_model_path", dir.absolutePath).apply()
        _ui.value = detect()
        onModelChanged?.invoke()
    }

    /** Remove ONE installed engine; falls back to another installed one if it was active. */
    fun deleteModel(id: String) {
        val opt = CATALOG.firstOrNull { it.id == id }
        val dir = if (opt != null) dirFor(opt) else File(modelsRoot, id)
        dir.deleteRecursively()
        if (prefs().getString("active_model_path", null) == dir.absolutePath) {
            prefs().edit().remove("active_model_path").apply()
        }
        _ui.value = detect()
        onModelChanged?.invoke()
    }

    /** Remove every installed engine. */
    fun deleteAllModels() {
        val external = externalModelsRoot?.canonicalPath
        modelsRoot.listFiles { f -> f.isDirectory }?.forEach { f ->
            if (external == null || f.canonicalPath != external) f.deleteRecursively()
        }
        prefs().edit().remove("active_model_path").apply()
        _ui.value = detect()
        onModelChanged?.invoke()
    }

    private fun update(f: (ModelUi) -> ModelUi) { _ui.value = f(_ui.value) }

    /**
     * Fetches an auxiliary bundle (currently the optional speech recogniser used to
     * transcribe clone references) into its own directory, reusing the download and
     * extraction path the engines already rely on. Extraction is staged: a partial
     * or failed fetch never leaves a half-written model behind.
     */
    fun fetchBundle(url: String, destDir: File, onPhase: (String) -> Unit = {}) {
        val stage = File(context.cacheDir, "${destDir.name}.incoming")
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            onPhase("Downloading…")
            val archive = File(stage, "bundle.tar.bz2")
            downloadFile(url, archive)
            onPhase("Extracting…")
            val unpacked = File(stage, "unpacked").apply { mkdirs() }
            extractTarBz2(archive, unpacked) { }
            // Bundles carry a single top-level directory; hoist it.
            val inner = unpacked.listFiles { f -> f.isDirectory }?.firstOrNull() ?: unpacked
            destDir.parentFile?.mkdirs()
            // Stage the existing bundle aside rather than deleting it: a failed rename
            // used to cost the user a working 111 MB recogniser with no way back.
            val previous = File(context.cacheDir, "${destDir.name}.previous")
            previous.deleteRecursively()
            if (destDir.isDirectory && !destDir.renameTo(previous)) {
                error("Could not stage the existing bundle aside.")
            }
            if (!inner.renameTo(destDir)) {
                if (previous.isDirectory) previous.renameTo(destDir)
                error("Could not finish installing the bundle.")
            }
            previous.deleteRecursively()
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "AudiobookNightForge/0.1")
        conn.connect()
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} downloading model" }
        val total = conn.contentLengthLong
        // Engines stay installed until deleted now, so refuse to start a download
        // that cannot also be extracted (roughly archive size again) plus headroom.
        if (total > 0) {
            val free = android.os.StatFs(modelsRoot.absolutePath).availableBytes
            val needed = total * 2 + 50L * 1024 * 1024
            check(free > needed) {
                "Not enough free space: about ${needed / (1024 * 1024)} MB needed, " +
                    "${free / (1024 * 1024)} MB available."
            }
        }
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
        // Completeness: a truncated download passes the existence checks below
        // and then kills the process inside the native engine, so never adopt
        // one. Content-Length is the only signal the server gives us.
        if (total > 0) {
            check(dest.length() == total) {
                "Download incomplete (${dest.length()} of $total bytes) — retry"
            }
        }
    }

    private fun extractTarBz2(archive: File, destDir: File, onFileExtracted: (Int) -> Unit = {}) {
        var count = 0
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile) continue
                val outFile = File(destDir, entry.name).canonicalFile
                check(outFile.path.startsWith(destDir.canonicalPath)) { "Bad archive entry: ${entry.name}" }
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tar.copyTo(it) }
                count++
                onFileExtracted(count)
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

        /** Kitten bundles look like Kokoro (voices.bin) but need the KITTEN config:
         *  detect by directory-name prefix first. */
        fun bundleKind(dir: File): EngineKind? {
            if (!dir.isDirectory) return null
            if (KokoroEngine.chooseModelFile(dir, preferInt8 = true) == null) return null
            val n = dir.name.lowercase()
            return when {
                n.startsWith("kitten") -> EngineKind.KITTEN
                n.startsWith("sherpa-onnx-zipvoice") -> EngineKind.ZIPVOICE
                File(dir, "voices.bin").isFile() -> EngineKind.KOKORO
                else -> EngineKind.VITS
            }
        }
        fun bundleTokensOk(dir: File): Boolean = File(dir, "tokens.txt").isFile()

        /**
         * Existence is not integrity: a truncated download leaves a file with the
         * right NAME and the wrong bytes, and the native engine dies on it (no Java
         * exception to catch). Require plausible sizes before adopting a bundle.
         */
        internal fun bundleSizeSane(dir: File): Boolean {
            val model = KokoroEngine.chooseModelFile(dir, preferInt8 = true) ?: return false
            if (model.length() < 1_000_000) return false
            val tokens = File(dir, "tokens.txt")
            if (tokens.isFile && tokens.length() < 100) return false
            return true
        }

        /** Test hook: bundle usability without an Android context. */
        internal fun isValidBundleForTest(dir: File): Boolean =
            bundleKind(dir) != null && bundleTokensOk(dir) && bundleSizeSane(dir)

        val CATALOG = listOf(
            ModelOption(
                id = "kokoro-fp32",
                title = "Kokoro 82M · full precision",
                subtitle = "Best quality — often fastest too on modern chips · ≈440 MB",
                url = KOKORO_FULL_URL,
                kind = EngineKind.KOKORO,
                recommended = true,
            ),
            ModelOption(
                id = "kokoro-int8",
                title = "Kokoro 82M · int8",
                subtitle = "Smallest download, multilingual · ≈126 MB",
                url = KOKORO_INT8_URL,
                kind = EngineKind.KOKORO,
            ),
            ModelOption(
                id = "kitten-nano-en",
                title = "Kitten nano · en v0.8",
                subtitle = "25M-param nano TTS, English, Apache-2.0 · ≈30 MB",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
                kind = EngineKind.KITTEN,
            ),
            ModelOption(
                id = "piper-lessac",
                title = "Piper Lite · int8",
                subtitle = "Tiny & fast for modest phones, English only · ≈30 MB",
                url = PIPER_MEDIUM_INT8_URL,
                kind = EngineKind.VITS,
            ),
            ModelOption(
                id = "piper-nl-ronnie",
                title = "Piper Dutch · ronnie int8",
                subtitle = "Dutch (nl_NL) voice, VITS · ≈21 MB",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-nl_NL-ronnie-medium-int8.tar.bz2",
                kind = EngineKind.VITS,
            ),
            ModelOption(
                id = "piper-nl-pim",
                title = "Piper Dutch · pim int8",
                subtitle = "Dutch (nl_NL) voice, VITS · ≈21 MB",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-nl_NL-pim-medium-int8.tar.bz2",
                kind = EngineKind.VITS,
            ),
            ModelOption(
                id = "sherpa-onnx-zipvoice-distill",
                title = "ZipVoice · voice cloning",
                subtitle = "Clone any voice from a short clip plus its transcript · ≈104 MB (+54 MB vocoder)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
                kind = EngineKind.ZIPVOICE,
            ),
        )
    }
}
