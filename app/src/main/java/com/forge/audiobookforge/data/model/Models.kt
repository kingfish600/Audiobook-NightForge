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
    var speed: Float = 1.0f,
) {
    val doneCount: Int get() = chapters.count { it.status == ChapterStatus.DONE }
}
