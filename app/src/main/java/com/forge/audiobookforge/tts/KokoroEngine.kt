package com.forge.audiobookforge.tts

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
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
        release()
        val modelFile = chooseModelFile(modelDir, preferInt8)
            ?: return "No model.int8.onnx or model.onnx found in ${modelDir.absolutePath}"
        val tokens = File(modelDir, "tokens.txt")
        if (!tokens.isFile) return "Model bundle is missing tokens.txt"

        val voices = File(modelDir, "voices.bin")
        val espeak = File(modelDir, "espeak-ng-data")
        val detectedKind =
            if (voices.isFile) ModelManager.EngineKind.KOKORO else ModelManager.EngineKind.VITS
        if (detectedKind == ModelManager.EngineKind.KOKORO && !espeak.isDirectory) {
            return "Kokoro bundle is missing espeak-ng-data/"
        }

        return try {
            val dictDir = File(modelDir, "dict").takeIf { it.isDirectory }?.absolutePath ?: ""
            val lexiconFiles = listOf("lexicon-zh.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt")
                .map { File(modelDir, it) }
                .filter { it.isFile }
            val ruleFsts = modelDir.listFiles { f -> f.isFile && f.name.endsWith(".fst") }
                ?.sortedBy { it.name }?.joinToString(",") { it.absolutePath } ?: ""

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = if (detectedKind == ModelManager.EngineKind.KOKORO) {
                        OfflineTtsKokoroModelConfig(
                            model = modelFile.absolutePath,
                            voices = voices.absolutePath,
                            tokens = tokens.absolutePath,
                            dataDir = espeak.absolutePath,
                            lexicon = lexiconFiles.joinToString(",") { it.absolutePath },
                            dictDir = dictDir,
                        )
                    } else {
                        OfflineTtsKokoroModelConfig()
                    },
                    vits = if (detectedKind == ModelManager.EngineKind.VITS) {
                        OfflineTtsVitsModelConfig(
                            model = modelFile.absolutePath,
                            tokens = tokens.absolutePath,
                            dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
                        )
                    } else {
                        OfflineTtsVitsModelConfig()
                    },
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = ruleFsts,
                // Batch more sentences per internal pass — fewer vocoder invocations.
                maxNumSentences = 3,
            )
            tts = OfflineTts(assetManager = null, config = config)
            loadedDir = modelDir
            kind = detectedKind
            null
        } catch (t: Throwable) {
            tts = null
            loadedDir = null
            kind = null
            "Engine init failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun sampleRate(): Int = tts?.sampleRate() ?: 24000

    fun numSpeakers(): Int = try { tts?.numSpeakers() ?: 0 } catch (_: Throwable) { 0 }

    fun synthesize(text: String, sid: Int, speed: Float): GeneratedAudio? =
        tts?.generate(text = text, sid = sid, speed = speed)

    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
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
