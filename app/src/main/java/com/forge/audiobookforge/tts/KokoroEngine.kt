package com.forge.audiobookforge.tts

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Thin lifecycle wrapper around sherpa-onnx OfflineTts.
 * Supports two engine families, auto-detected from the installed bundle:
 *  - KOKORO: model(.int8).onnx + voices.bin + tokens.txt + espeak-ng-data
 *  - VITS (Piper): model(.int8).onnx + tokens.txt (+ optional espeak-ng-data)
 * The native session is created once and reused for every chunk of a book
 * (re-creating it per utterance is the classic cause of "laggy" TTS).
 */
class KokoroEngine {

    private var tts: OfflineTts? = null

    // Retired-but-resident engines. sherpa-onnx's native stack (espeak-ng
    // phonemization globals) cannot survive a native release + re-create
    // cycle inside one process: the second init SIGSEGVs (device-proven).
    // So engine swaps RETIRE the old instance instead of freeing it; it
    // stays resident until process death or the explicit Free-memory hook.
    private val retired = ArrayList<OfflineTts>()

    // Set once any native engine has actually been freed. Further loads
    // are refused with a friendly message instead of risking the crash.
    @Volatile
    private var poisoned = false

    var loadedDir: File? = null; private set
    var kind: ModelManager.EngineKind? = null; private set
    val isLoaded: Boolean get() = tts != null

    /**
     * Loads a model bundle. Returns null on success, or a human-readable
     * error message describing exactly what went wrong.
     */
    @Synchronized
    fun load(modelDir: File, numThreads: Int = 4, preferInt8: Boolean = true): String? {
        if (tts != null && loadedDir == modelDir) return null
        if (poisoned) {
            return "Engine resources were freed to save RAM — restart the app to load a model."
        }
        val family = ModelManager.bundleKind(modelDir)
            ?: return "Unrecognized model bundle layout in ${modelDir.absolutePath}"
        val modelFile = chooseModelFile(modelDir, preferInt8)
            ?: return "No model.int8.onnx or model.onnx found in ${modelDir.absolutePath}"
        val tokens = File(modelDir, "tokens.txt")
        if (!tokens.isFile) return "Model bundle is missing tokens.txt"

        val voices = File(modelDir, "voices.bin")
        val espeak = File(modelDir, "espeak-ng-data")
        if (family == ModelManager.EngineKind.KOKORO && !espeak.isDirectory) {
            return "Kokoro bundle is missing espeak-ng-data/"
        }

        return try {
            val dictDir = File(modelDir, "dict").takeIf { it.isDirectory }?.absolutePath ?: ""
            val lexiconFiles = listOf("lexicon-zh.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt")
                .map { File(modelDir, it) }
                .filter { it.isFile }
            val ruleFsts = modelDir.listFiles { f -> f.isFile && f.name.endsWith(".fst") }
                ?.sortedBy { it.name }?.joinToString(",") { it.absolutePath } ?: ""

            val modelConfig = when (family) {
                ModelManager.EngineKind.KOKORO -> OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = modelFile.absolutePath,
                        voices = voices.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = espeak.absolutePath,
                        lexicon = lexiconFiles.joinToString(",") { it.absolutePath },
                        dictDir = dictDir,
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                )
                ModelManager.EngineKind.VITS -> OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                )
                ModelManager.EngineKind.KITTEN -> {
                    val modelFile = chooseModelFile(modelDir, preferInt8)
                        ?: return "Kitten bundle has no model .onnx"
                    val tokens = File(modelDir, "tokens.txt")
                    if (!tokens.isFile) return "Kitten bundle is missing tokens.txt"
                    val voices = File(modelDir, "voices.bin")
                    OfflineTtsModelConfig(kitten = OfflineTtsKittenModelConfig(
                        model = modelFile.absolutePath,
                        voices = if (voices.isFile) voices.absolutePath else "",
                        tokens = tokens.absolutePath,
                        dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
                    ))
                }
            }

            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = ruleFsts,
                // Batch more sentences per internal pass — fewer vocoder invocations.
                maxNumSentences = 3,
            )
            val created = OfflineTts(assetManager = null, config = config)
            // Adopt BEFORE freeing anything: the new engine is live, the old
            // one is retired (kept resident), never torn down mid-process.
            tts?.let { old -> retired.add(old); trimRetired() }
            tts = created
            loadedDir = modelDir
            kind = family
            null
        } catch (t: Throwable) {
            // Init failed: the previous engine (if any) is untouched — the
            // user is never left without TTS.
            "Engine init failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun trimRetired() {
        // At most two retired engines stay resident (covers the small
        // models). Past that we must free something — and any native free
        // poisons the process: later loads ask for a restart, no crash.
        while (retired.size > 2) {
            runCatching { retired.removeAt(0).release() }
            poisoned = true
        }
    }

    fun sampleRate(): Int = tts?.sampleRate() ?: 24000

    fun numSpeakers(): Int = try { tts?.numSpeakers() ?: 0 } catch (_: Throwable) { 0 }

    @Synchronized
    fun synthesize(text: String, sid: Int, speed: Float): GeneratedAudio? =
        tts?.generate(text = text, sid = sid, speed = speed)

    /**
     * Terminal. Frees ALL native engines (active + retired). Because a
     * native release + re-create cycle crashes the process, the instance
     * is poisoned afterwards: load() refuses with a friendly restart hint.
     */
    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
        retired.forEach { runCatching { it.release() } }
        retired.clear()
        poisoned = true
        loadedDir = null
        kind = null
    }

    companion object {
        fun chooseModelFile(modelDir: File, preferInt8: Boolean): File? {
            val int8 = File(modelDir, "model.int8.onnx")
            val full = File(modelDir, "model.onnx")
            when {
                preferInt8 && int8.isFile -> return int8
                full.isFile -> return full
                int8.isFile -> return int8
            }
            // Fallback for bundles that name their weights differently,
            // e.g. piper ships "en_US-lessac-medium.onnx".
            return modelDir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }
                ?.sortedBy { it.name }?.firstOrNull()
        }
    }
}
