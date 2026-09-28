package com.forge.audiobookforge.di

import android.content.Context
import com.forge.audiobookforge.audio.Wav
import com.forge.audiobookforge.conversion.ConversionController
import com.forge.audiobookforge.data.LibraryRepository
import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.playback.PlayerController
import com.forge.audiobookforge.tts.KokoroEngine
import com.forge.audiobookforge.tts.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Hand-rolled dependency container — deliberately simple. */
class AppContainer(private val context: Context) : ContainerApi {
    override val settings = AppSettings(context)
    override val library = LibraryRepository(context)
    override val models = ModelManager(context)
    override val kokoroEngine = KokoroEngine()
    override val conversion = ConversionController()
    override val player = PlayerController(context, library)
    private val transcriber = com.forge.audiobookforge.tts.Transcriber(context, models)

    override fun transcriberReady(): Boolean = transcriber.isReady()

    override fun removeTranscriber() = transcriber.remove()

    override suspend fun installTranscriber() = withContext(Dispatchers.IO) { transcriber.install() }

    override suspend fun transcribeClip(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "clip-to-transcribe.wav")
        try {
            tmp.writeBytes(bytes)
            transcriber.transcribe(tmp)
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    override val clones = com.forge.audiobookforge.tts.CloneStore(
        File(context.filesDir, "clones").apply { mkdirs() },
    )

    /**
     * Resolves the cloned voice for a book: its explicit choice, else the first
     * clone available (so a cloning engine works the moment one exists).
     */
    override fun cloneFor(book: com.forge.audiobookforge.data.model.Book):
        com.forge.audiobookforge.tts.KokoroEngine.ReferenceVoice? {
        val pick = book.cloneName?.let { clones.get(it) } ?: clones.list().firstOrNull()
        return pick?.let {
            com.forge.audiobookforge.tts.KokoroEngine.ReferenceVoice(it.wav, it.text)
        }
    }

    init {
        // A model change invalidates the loaded engine: the bundle may have been
        // deleted, and a stale engine answers numSpeakers() for the wrong model.
        models.onModelChanged = {
            // Deliberately NOT releasing the engine here. release() is @Synchronized and
            // the worker holds that monitor for a whole chunk (10-30 s Kokoro, ~100 s
            // ZipVoice), so doing it inline from a UI action ANR'd the app during a
            // render — while the UI advertised switching with no restart. Nothing needs
            // it either: load() swaps engines in-process on the next synthesis, and the
            // picker already ignores an engine whose directory is not the active model.
            // A cloning engine ships reference clips with their transcripts —
            // import them so voice cloning works with zero setup. Idempotent.
            models.ui.value.modelDir?.let { dir ->
                if (ModelManager.bundleKind(dir) == ModelManager.EngineKind.ZIPVOICE) {
                    runCatching { clones.importBundled(dir) }
                }
            }
        }
    }

    /**
     * Synthesize a short sample with the book's current voice+speed and play it.
     * Returns null on success or an error message.
     */
    override suspend fun previewVoice(book: Book): String? = withContext(Dispatchers.IO) {
        val modelDir = models.ui.value.modelDir
            ?: return@withContext "Download the voice model first (Library screen)."
        val loadError = kokoroEngine.load(modelDir, settings.numThreads.value)
        if (loadError != null && com.forge.audiobookforge.tts.KokoroEngine.isRestartNeeded(loadError)) {
            // The new engine is already the saved choice, but the old one is
            // still loaded in THIS process (swiping away never kills it).
            // Apply for real: exit + relaunch. The fresh process loads it.
            // Toasts MUST be shown on a Looper thread — previewVoice runs on
            // Dispatchers.IO; showing directly here crashes the process.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, "Applying new engine — restarting NightForge…",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            Thread { Thread.sleep(1200); AppRestart.restart(context) }.start()
            return@withContext null
        }
        loadError?.let { return@withContext it }

        val speakers = kokoroEngine.numSpeakers()
        val sid = if (speakers > 0) book.voiceSid.coerceIn(0, speakers - 1) else book.voiceSid
        val reference = cloneFor(book)
        if (kokoroEngine.kind == ModelManager.EngineKind.ZIPVOICE && reference == null) {
            return@withContext "Add a voice clone first (Settings → Cloned voices), then preview."
        }
        val audio = kokoroEngine.synthesize(
            text = PREVIEW_TEXT,
            sid = sid,
            speed = book.speed,
            reference = reference,
            steps = settings.cloneSteps.value,
        )
            ?: return@withContext "Synthesis failed — engine not loaded."
        if (audio.samples.isEmpty()) return@withContext "Synthesis produced no audio."

        val f = File(context.cacheDir, "voice_preview.wav")
        Wav.write(f, audio.samples, audio.sampleRate)
        player.playPreview(f)
        null
    }

    override suspend fun previewClone(name: String): String? = withContext(Dispatchers.IO) {
        val modelDir = models.ui.value.modelDir
            ?: return@withContext "Install a cloning engine first (Settings → TTS engine)."
        kokoroEngine.load(modelDir, settings.numThreads.value)?.let { return@withContext it }
        val clone = clones.get(name) ?: return@withContext "That voice is no longer in the library."
        val audio = kokoroEngine.synthesize(
            text = PREVIEW_TEXT,
            sid = 0,
            speed = 1f,
            reference = com.forge.audiobookforge.tts.KokoroEngine.ReferenceVoice(clone.wav, clone.text),
            steps = settings.cloneSteps.value,
        ) ?: return@withContext "Synthesis failed — is a cloning engine (ZipVoice) installed?"
        if (audio.samples.isEmpty()) return@withContext "Synthesis produced no audio."
        val f = File(context.cacheDir, "clone_preview.wav")
        Wav.write(f, audio.samples, audio.sampleRate)
        player.playPreview(f)
        null
    }

    companion object {
        const val PREVIEW_TEXT =
            "This is how your audiobook will sound, read at the current speed with this voice."
    }
}
