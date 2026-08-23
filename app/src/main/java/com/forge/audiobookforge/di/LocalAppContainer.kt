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

    suspend fun previewVoice(book: Book): String?
}

val LocalAppContainer = staticCompositionLocalOf<ContainerApi> {
    error("AppContainer not provided")
}
