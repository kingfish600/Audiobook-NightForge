package com.forge.audiobookforge.di

import androidx.compose.runtime.staticCompositionLocalOf
import com.forge.audiobookforge.data.LibraryRepository
import com.forge.audiobookforge.conversion.ConversionController
import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.playback.PlayerController
import com.forge.audiobookforge.tts.KokoroEngine
import com.forge.audiobookforge.tts.ModelManager

/** Placeholder mirroring AppContainer's surface for the CompositionLocal type. */
interface ContainerApi {
    val settings: AppSettings
    val library: LibraryRepository
    val models: ModelManager
    val kokoroEngine: KokoroEngine
    val conversion: ConversionController
    val player: PlayerController
    val clones: com.forge.audiobookforge.tts.CloneStore

    suspend fun previewVoice(book: Book): String?

    /** The cloned voice a book should speak with, or null when none applies. */
    fun cloneFor(book: Book): com.forge.audiobookforge.tts.KokoroEngine.ReferenceVoice?

    /** Speak a sample sentence with a cloned voice so the user can judge it. */
    suspend fun previewClone(name: String): String?
}

val LocalAppContainer = staticCompositionLocalOf<ContainerApi> {
    error("AppContainer not provided")
}
