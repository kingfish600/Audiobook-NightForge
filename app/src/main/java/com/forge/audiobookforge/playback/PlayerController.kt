package com.forge.audiobookforge.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.forge.audiobookforge.data.LibraryRepository
import com.forge.audiobookforge.data.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Minimal Media3 wrapper: playlist = rendered chapters of a book. */
class PlayerController(
    context: Context,
    private val library: LibraryRepository,
) {
    data class PlayerUi(
        val visible: Boolean = false,
        val playing: Boolean = false,
        val bookId: String? = null,
        val bookTitle: String = "",
        val chapterIndex: Int = -1,   // index into Book.chapters
        val chapterTitle: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTicker: kotlinx.coroutines.Job? = null

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    // maps playlist position -> Book.chapters index
    private var playlistChapters: List<Int> = emptyList()
    private var currentBookId: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.value = _ui.value.copy(playing = isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshChapterMeta()
            }
        })
    }

    fun playBook(book: Book, fromChapterIndex: Int) {
        val playable = book.chapters
            .filter { it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE && it.audioFile != null }
            .sortedBy { it.index }
        if (playable.isEmpty()) return

        playlistChapters = playable.map { it.index }
        currentBookId = book.id
        val items = playable.map {
            MediaItem.fromUri(Uri.fromFile(File(library.audioDir(book.id), it.audioFile!!)))
        }
        val startListPos = playlistChapters.indexOf(fromChapterIndex).coerceAtLeast(0)
        player.setMediaItems(items, startListPos, 0L)
        player.prepare()
        player.playWhenReady = true

        _ui.value = PlayerUi(
            visible = true,
            playing = true,
            bookId = book.id,
            bookTitle = book.title,
            chapterTitle = playable[startListPos].title,
            chapterIndex = playlistChapters[startListPos],
        )
        startPositionTicker()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    fun dismiss() {
        positionTicker?.cancel()
        stopPreview()
        player.stop()
        _ui.value = PlayerUi()
    }

    // ---- one-shot voice previews (separate lightweight player) ----

    private var previewPlayer: android.media.MediaPlayer? = null

    /** Halt audiobook playback entirely (used when its book is deleted). */
    fun stopAll() {
        player.stop()
        player.clearMediaItems()
        _ui.value = PlayerUi(visible = false)
    }

    fun playPreview(file: java.io.File) {        stopPreview()
        previewPlayer = android.media.MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                runCatching { it.release() }
                if (previewPlayer === this) previewPlayer = null
            }
        }
    }

    fun stopPreview() {
        previewPlayer?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        previewPlayer = null
    }

    private fun refreshChapterMeta() {
        val idx = player.currentMediaItemIndex
        val chapterIdx = playlistChapters.getOrNull(idx) ?: -1
        _ui.value = _ui.value.copy(chapterIndex = chapterIdx)
        currentBookId?.let { id ->
            library.book(id)?.chapters?.firstOrNull { it.index == chapterIdx }?.let {
                _ui.value = _ui.value.copy(chapterTitle = it.title)
            }
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                if (_ui.value.visible) {
                    _ui.value = _ui.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
                delay(500)
            }
        }
    }
}
