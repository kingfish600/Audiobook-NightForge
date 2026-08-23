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
        val lastChunkRtf: Float = 0f,
    ) : ConversionState {
        val overallFraction: Float
            get() = (chaptersDone + charsDoneInChapter.toFloat() / charsTotalInChapter.coerceAtLeast(1)) /
                chaptersTotal.coerceAtLeast(1)
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

    @Volatile
    var cancelRequested: Boolean = false

    /** After Stop is tapped, ignore progress updates from the dying run until it exits. */
    @Volatile
    private var suppressUpdates: Boolean = false

    @Volatile
    private var runSeq: Long = 0L

    /** Called at the start of every worker run; returns the id of this run. */
    fun beginRun(): Long {
        cancelRequested = false
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
        if (_state.value !is ConversionState.Idle) {
            cancelRequested = false
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
        cancelRequested = false
        _state.value = ConversionState.Idle
    }
}
