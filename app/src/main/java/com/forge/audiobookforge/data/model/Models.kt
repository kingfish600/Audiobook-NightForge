package com.forge.audiobookforge.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChapterStatus { PENDING, RENDERING, DONE, FAILED }

@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val text: String,
    val charCount: Int = text.length,
    var audioFile: String? = null,
    var durationMs: Long = 0L,
    var status: ChapterStatus = ChapterStatus.PENDING,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val sourceFileName: String,
    val importedAtEpochMs: Long,
    val chapters: List<Chapter>,
    var voiceSid: Int = 3,          // default voice (see Voices.kt)
    /** Cloned voice to speak with (see CloneStore); null = the engine's own voices. */
    var cloneName: String? = null,
    /**
     * Catalog id of the engine this book was first forged with, e.g. "kokoro-int8" or
     * "piper-lessac". Recorded once and never overwritten: without it, switching engines
     * and resuming silently changed the narrator's voice part-way through a book.
     */
    var engineId: String? = null,
    var speed: Float = 1.0f,
) {
    val doneCount: Int get() = chapters.count { it.status == ChapterStatus.DONE }
}
