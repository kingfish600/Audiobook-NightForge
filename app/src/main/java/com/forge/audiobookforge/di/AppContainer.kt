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

    /**
     * Synthesize a short sample with the book's current voice+speed and play it.
     * Returns null on success or an error message.
     */
    override suspend fun previewVoice(book: Book): String? = withContext(Dispatchers.IO) {
        val modelDir = models.ui.value.modelDir
            ?: return@withContext "Download the voice model first (Library screen)."
        kokoroEngine.load(modelDir, settings.numThreads.value, settings.preferInt8.value)?.let { return@withContext it }

        val speakers = kokoroEngine.numSpeakers()
        val sid = if (speakers > 0) book.voiceSid.coerceIn(0, speakers - 1) else book.voiceSid
        val audio = kokoroEngine.synthesize(PREVIEW_TEXT, sid, book.speed)
            ?: return@withContext "Synthesis failed — engine not loaded."
        if (audio.samples.isEmpty()) return@withContext "Synthesis produced no audio."

        val f = File(context.cacheDir, "voice_preview.wav")
        Wav.write(f, audio.samples, audio.sampleRate)
        player.playPreview(f)
        null
    }

    companion object {
        const val PREVIEW_TEXT =
            "This is how your audiobook will sound, read at the current speed with this voice."
    }
}
