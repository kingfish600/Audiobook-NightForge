package com.forge.audiobookforge.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
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

    var loadedDir: File? = null; private set
    var kind: ModelManager.EngineKind? = null; private set
    val isLoaded: Boolean
        @Synchronized get() = tts != null

    /**
     * Loads [modelDir], switching engines IN-PROCESS when a different one is already
     * loaded. Releasing and re-creating the native engine is safe: the GBGH playground
     * has shipped exactly this on real devices, and the engine-switch lab shows
     * release + re-create runs clean (several engines can even coexist). The old
     * "restart the app to switch" behaviour is gone.
     *
     * If the new bundle cannot be loaded, the previous engine is put back so the user
     * is never left without TTS, and the error is returned for display.
     */
    @Synchronized
    fun load(modelDir: File, numThreads: Int = 4, preferInt8: Boolean = true): String? {
        if (tts != null && loadedDir == modelDir) return null
        val previousDir = loadedDir
        if (tts != null) release()
        val result = loadInternal(modelDir, numThreads, preferInt8)
        if (result != null && tts == null && previousDir != null && previousDir != modelDir) {
            runCatching { loadInternal(previousDir, numThreads, preferInt8) }
        }
        return result
    }

    @Synchronized
    private fun loadInternal(modelDir: File, numThreads: Int, preferInt8: Boolean): String? {
        val family = ModelManager.bundleKind(modelDir)
            ?: return "Unrecognized model bundle layout in ${modelDir.absolutePath}"
        val modelFile = chooseModelFile(modelDir, preferInt8)
            ?: return "No model.int8.onnx or model.onnx found in ${modelDir.absolutePath}"
        // An incomplete model file kills the process inside native code, where no
        // catch block can help. Refuse it here, where we can still explain.
        if (modelFile.length() < 1_000_000) {
            return "This voice model looks incomplete (${modelFile.length()} bytes) — " +
                "delete it on the Library screen and download it again."
        }
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
                ModelManager.EngineKind.ZIPVOICE -> {
                    val tokens = File(modelDir, "tokens.txt")
                    if (!tokens.isFile) return "ZipVoice bundle is missing tokens.txt"
                    fun find(prefix: String): File? =
                        modelDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
                            ?.sortedByDescending { it.name.contains("int8") }
                            ?.firstOrNull()
                    val encoder = find("encoder") ?: return "ZipVoice bundle is missing encoder"
                    val decoder = find("decoder") ?: return "ZipVoice bundle is missing decoder"
                    val vocoder = find("vocoder") ?: return "ZipVoice bundle is missing vocoder"
                    // The bundle ships a single lexicon.txt at its root. Passing an
                    // empty lexicon path makes native init crash during model load.
                    val lexicon = listOf(
                        "lexicon.txt",
                        "lexicon-zh.txt",
                        "lexicon-us-en.txt",
                        "lexicon-gb-en.txt",
                    ).map { File(modelDir, it) }.filter { it.isFile }
                        .joinToString(",") { it.absolutePath }
                    OfflineTtsModelConfig(zipvoice = OfflineTtsZipVoiceModelConfig(
                        tokens = tokens.absolutePath,
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        vocoder = vocoder.absolutePath,
                        dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
                        lexicon = lexicon,
                    ))
                }
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

    // Synchronized: these touch the native engine, which may be freed at any
    // moment by load()/release() on another thread.
    @Synchronized
    fun sampleRate(): Int = try { tts?.sampleRate() ?: 24000 } catch (_: Throwable) { 24000 }

    @Synchronized
    fun numSpeakers(): Int = try { tts?.numSpeakers() ?: 0 } catch (_: Throwable) { 0 }

    /** A cloned voice: the reference clip plus the exact words spoken in it. */
    data class ReferenceVoice(val wav: File, val text: String)

    /** Decoded reference clips, so a long book does not re-parse the WAV per chunk. */
    private val referenceCache = com.forge.audiobookforge.audio.RefCache()

    @Synchronized
    fun synthesize(
        text: String,
        sid: Int,
        speed: Float,
        reference: ReferenceVoice? = null,
    ): GeneratedAudio? {
        val engine = tts ?: return null
        // Zero-shot cloning engines speak from a reference clip + its transcript.
        // Without a usable reference there is nothing to clone, so return null
        // rather than feeding the engine a half-configured request.
        if (kind == ModelManager.EngineKind.ZIPVOICE) {
            val ref = reference ?: return null
            val audio = referenceCache.samples(ref.wav) ?: return null
            val cfg = GenerationConfig(
                speed = speed,
                sid = sid,
                referenceAudio = audio.first,
                referenceSampleRate = audio.second,
                referenceText = ref.text,
            )
            return runCatching { engine.generateWithConfig(text, cfg) }.getOrNull()
        }
        // Kokoro >= 1.0 chooses its phonemizer language from
        // generationConfig.extra["lang"] (sherpa's offline-tts-kokoro-impl.h).
        // Without it the engine uses the bundle default, so every non-English
        // voice read its text with an English accent.
        if (kind == ModelManager.EngineKind.KOKORO) {
            val lang = Voices.langForSid(sid)
            if (lang.isNotEmpty()) {
                val cfg = GenerationConfig(speed = speed, sid = sid, extra = mapOf("lang" to lang))
                runCatching { engine.generateWithConfig(text, cfg) }.getOrNull()?.let { return it }
            }
        }
        return engine.generate(text = text, sid = sid, speed = speed)
    }

    /**
     * Frees the native engine (e.g. the Settings "unload to save RAM" button).
     * NOT terminal any more: the next load() simply re-creates the engine in
     * process, so nothing below needs a restart.
     */
    @Synchronized
    fun release() {
        // Publish the cleared field FIRST, then free the native object. Doing it
        // the other way round left a window where another thread saw a non-null
        // engine whose native pointer had already been deleted, and
        // numSpeakers()/generate() then dereferenced null inside the JNI
        // (SIGSEGV, verified from a device tombstone).
        val old = tts
        tts = null
        loadedDir = null
        kind = null
        referenceCache.clear()
        runCatching { old?.release() }
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
