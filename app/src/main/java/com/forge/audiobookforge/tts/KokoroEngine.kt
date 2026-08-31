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
 *
 * UPSTREAM CONSTRAINT (desktop-proven on sherpa-onnx 1.13.6, the same native
 * version this app ships): exactly ONE OfflineTts may exist per process.
 *  - release + re-create  -> native crash (device-proven SIGSEGV)
 *  - two engines resident -> second engine's InitFrontend kills the process
 * Therefore: one engine per process lifetime. Switching engines requires an
 * app restart; the chosen engine is persisted first so the restart picks it
 * up. Nothing here frees a native engine implicitly.
 */
class KokoroEngine {

    private var tts: OfflineTts? = null

    // Set once the native engine has been freed (Free-memory hook). Further
    // loads are refused with a friendly message: re-creating a native engine
    // in the same process is not supported by sherpa-onnx.
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
        if (poisoned) {
            return "Engine resources were freed to save RAM — restart the app to load a model."
        }
        if (tts != null) {
            if (loadedDir == modelDir) return null
            // One engine per process (upstream constraint). The choice is
            // already persisted by ModelManager; a restart picks it up.
            return RESTART_MSG + modelDir.name + "."
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
            tts = OfflineTts(assetManager = null, config = config)
            loadedDir = modelDir
            kind = family
            null
        } catch (t: Throwable) {
            // Init failed: the previous engine (if any) is untouched — the
            // user is never left without TTS.
            "Engine init failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun sampleRate(): Int = tts?.sampleRate() ?: 24000

    fun numSpeakers(): Int = try { tts?.numSpeakers() ?: 0 } catch (_: Throwable) { 0 }

    @Synchronized
    fun synthesize(text: String, sid: Int, speed: Float): GeneratedAudio? =
        tts?.generate(text = text, sid = sid, speed = speed)

    /**
     * Terminal. Frees the native engine. Because a
     * native release + re-create cycle crashes the process, the instance
     * is poisoned afterwards: load() refuses with a friendly restart hint.
     */
    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
        poisoned = true
        loadedDir = null
        kind = null
    }

    companion object {
        /** Prefix of the engine-switch message — callers detect it to offer
         *  the automatic apply-and-restart. */
        const val RESTART_MSG =
            "Switching engines needs an app restart — your choice is saved. " +
                "Close and reopen NightForge to use "
        fun isRestartNeeded(message: String?): Boolean =
            message?.startsWith(RESTART_MSG) == true

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
