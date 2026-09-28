package com.forge.audiobookforge.conversion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConversionState {
    data object Idle : ConversionState

    data class Running(
        val bookId: String,
        val bookTitle: String,
        val chapterIndex: Int,      // -1 while the engine is still loading
        val chapterTitle: String,
        val chaptersDone: Int,
        val chaptersTotal: Int,
        val charsDoneInChapter: Int = 0,
        val charsTotalInChapter: Int = 1,
        /**
         * Characters finished in earlier chapters. Progress is weighted by TEXT LENGTH, not
         * by chapter count: a chapter can be a few hundred characters (a table of contents)
         * or tens of thousands. Counting chapters made short leading chapters advance the
         * bar as far as a full one, so a book looked nearly finished long before it was.
         */
        val charsDoneOverall: Int = 0,
        val charsTotalOverall: Int = 1,
        val lastChunkRtf: Float = 0f,
    ) : ConversionState {
        val overallFraction: Float
            get() = (charsDoneOverall + charsDoneInChapter).toFloat() /
                charsTotalOverall.coerceAtLeast(1)
    }

    /** Conversion stopped because something failed; message is shown inline in the UI. */
    data class Failed(
        val bookId: String?,
        val message: String,
    ) : ConversionState
}

class ConversionController {
    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    /**
     * Cancellation is tracked per RUN, not with a single boolean. A boolean was
     * cleared by the next beginRun(), so a worker still inside a long native
     * render would read "not cancelled" and keep writing the same chapter file
     * as its replacement. Each run now carries its own id.
     */
    @Volatile
    private var cancelledRunId: Long = -1L

    /** Ask the CURRENT run to stop at its next chunk boundary. */
    fun requestStop() {
        cancelledRunId = runSeq
    }

    fun isCancelled(runId: Long): Boolean = cancelledRunId == runId

    /** After Stop is tapped, ignore progress updates from the dying run until it exits. */
    @Volatile
    private var suppressUpdates: Boolean = false

    @Volatile
    private var runSeq: Long = 0L

    /** Called at the start of every worker run; returns the id of this run. */
    fun beginRun(): Long {
        // A new run clears the previous failure (endRun deliberately leaves it visible).
        if (_state.value is ConversionState.Failed) _state.value = ConversionState.Idle
        suppressUpdates = false
        runSeq += 1
        return runSeq
    }

    /**
     * Called when a run finishes (success, stop, or failure). Only the *current*
     * run may clear the visible state — a stale cancelled worker must not clobber
     * a freshly started replacement run.
     */
    fun endRun(runId: Long) {
        if (runId != runSeq) return
        suppressUpdates = false
        // Never erase a failure. fail() runs in the worker's catch and endRun() runs
        // microseconds later in its finally, so every "why did it fail" message was
        // being wiped before the user could read it. The next run clears it instead.
        val current = _state.value
        if (current !is ConversionState.Idle && current !is ConversionState.Failed) {
            _state.value = ConversionState.Idle
        }
    }

    fun update(s: ConversionState) {
        if (suppressUpdates && s is ConversionState.Running) return
        _state.value = s
    }

    fun fail(message: String, bookId: String? = null) {
        _state.value = ConversionState.Failed(bookId, message)
    }

    /**
     * Optimistic UI feedback on Stop. Does NOT clear [cancelRequested] — the worker
     * still needs to see it at the next chunk boundary — but from now on this dying
     * run may no longer push progress back onto screen.
     */
    fun markUiStopped() {
        suppressUpdates = true
        _state.value = ConversionState.Idle
    }

    fun idle() {
        cancelledRunId = -1L
        _state.value = ConversionState.Idle
    }
}
