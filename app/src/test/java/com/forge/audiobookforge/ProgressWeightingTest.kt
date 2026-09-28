package com.forge.audiobookforge

import com.forge.audiobookforge.conversion.ConversionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Progress must reflect how much TEXT is left, not how many chapters are ticked off.
 * Chapters vary by orders of magnitude — a table of contents is a few hundred characters
 * where a real chapter is tens of thousands — so counting chapters made a book appear to
 * race ahead while almost all of the reading remained.
 */
class ProgressWeightingTest {

    /** A 4-chapter book whose first chapter is a short table of contents: 1% of the text. */
    private fun afterShortFirstChapter() = ConversionState.Running(
        bookId = "book",
        bookTitle = "Title",
        chapterIndex = 1,
        chapterTitle = "Chapter 1",
        chaptersDone = 1,
        chaptersTotal = 4,
        charsDoneInChapter = 0,
        charsTotalInChapter = 1_000,
        charsDoneOverall = 100,
        charsTotalOverall = 10_000,
        lastChunkRtf = 1f,
    )

    @Test
    fun oneShortChapterDoesNotLookLikeAQuarterOfTheBook() {
        assertEquals(
            "1% of the text must read as 1%, not as 1 of 4 chapters",
            0.01f,
            afterShortFirstChapter().overallFraction,
            0.0001f,
        )
    }

    @Test
    fun progressTracksCharactersAcrossTheBook() {
        val quarter = afterShortFirstChapter().copy(charsDoneOverall = 2_500)
        assertEquals(0.25f, quarter.overallFraction, 0.0001f)

        val mostOfIt = afterShortFirstChapter().copy(charsDoneOverall = 9_000)
        assertEquals(0.90f, mostOfIt.overallFraction, 0.0001f)
    }

    @Test
    fun workInsideTheCurrentChapterStillCounts() {
        val midChapter = afterShortFirstChapter().copy(charsDoneInChapter = 500)
        assertEquals(0.06f, midChapter.overallFraction, 0.0001f)
    }

    @Test
    fun aLongFirstChapterMeansSubstantialProgress() {
        // Same 4 chapters, but no short TOC: the first chapter is half the text.
        val halfDone = afterShortFirstChapter().copy(charsDoneOverall = 5_000)
        assertEquals(0.50f, halfDone.overallFraction, 0.0001f)
    }
}
