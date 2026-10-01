package com.forge.audiobookforge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Records a voice-clone reference.
 *
 * Captures at exactly the layout cloning engines want — **24 kHz, mono, PCM 16** —
 * so the clip can be handed to the engine with no decoding or resampling at all.
 * (ZipVoice's own examples specify mono 24 kHz PCM s16.)
 *
 * Uses AudioRecord rather than MediaRecorder deliberately: MediaRecorder cannot
 * write uncompressed WAV, and re-decoding an AAC recording would add a lossy step
 * between the user's voice and the thing being cloned.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 24_000

        /**
         * Shorter clips do not give the model enough to learn from. Deliberately stricter
         * than CloneStore.MIN_REFERENCE_SECONDS (2s): that is a hard floor for a clip the
         * user already has, while this dialog can afford to ask for a genuinely good take.
         */
        const val MIN_SECONDS = 4.0

        /** Longer is not better: it costs synthesis time and adds nothing. */
        const val MAX_SECONDS = 30.0

        /** Where clips clone best. */
        const val IDEAL_MIN_SECONDS = 5.0
        const val IDEAL_MAX_SECONDS = 15.0

        /** Below this average level the microphone effectively heard nothing. */
        const val SILENCE_RMS = 0.004f

        /** Above this the signal is clipped and the clone will sound harsh. */
        const val CLIPPING_PEAK = 0.995f

        /**
         * Below this the take is audible but thin — usable, yet worth flagging:
         * a quiet recording carries the room into the clone.
         */
        const val QUIET_RMS = 0.02f
    }

    sealed interface Start {
        object Ok : Start
        data class Failed(val message: String) : Start
    }

    data class Quality(
        val seconds: Double,
        val averageRms: Float,
        val peak: Float,
        val warning: String?,
        val usable: Boolean,
    )

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    @Volatile private var level = 0f
    private var chunks = ArrayList<ShortArray>()
    private var totalSamples = 0

    /** Microphone level 0..1 while recording, for a simple meter. */
    val currentLevel: Float get() = level

    val elapsedSeconds: Double get() = totalSamples.toDouble() / SAMPLE_RATE

    /**
     * Begins capturing. Fails cleanly when permission is missing or another app
     * holds the microphone, so the caller can explain instead of crashing.
     */
    @SuppressLint("MissingPermission")
    fun start(): Start {
        if (running) return Start.Failed("Already recording.")
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return Start.Failed("This device cannot record at 24 kHz.")

        // MIC (not VOICE_RECOGNITION): avoid the platform's speech processing —
        // we want the person's actual voice, not a cleaned-up version of it.
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (e: SecurityException) {
            return Start.Failed("Microphone permission is needed to record a voice.")
        } catch (e: IllegalArgumentException) {
            return Start.Failed("This device cannot record at 24 kHz.")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return Start.Failed("The microphone is busy or unavailable.")
        }

        chunks = ArrayList()
        totalSamples = 0
        level = 0f
        val buffer = ShortArray(minBuffer.coerceAtLeast(2048))
        return try {
            rec.startRecording()
            record = rec
            running = true
            worker = Thread {
                while (running) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val copy = buffer.copyOf(n)
                    synchronized(this) {
                        chunks.add(copy)
                        totalSamples += n
                    }
                    if (elapsedSeconds >= MAX_SECONDS) running = false
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = copy[i] / 32_768.0
                        sum += v * v
                    }
                    level = sqrt(sum / n).toFloat()
                }
            }.also { it.isDaemon = true; it.start() }
            Start.Ok
        } catch (e: SecurityException) {
            rec.release()
            Start.Failed("Microphone permission is needed to record a voice.")
        } catch (e: IllegalStateException) {
            rec.release()
            Start.Failed("The microphone is busy or unavailable.")
        }
    }

    /** Stops and returns the recorded audio, or null when nothing usable was captured. */
    fun stop(): FloatArray? {
        running = false
        worker?.let { runCatching { it.join(2_000) } }
        worker = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        level = 0f

        val captured: ShortArray
        synchronized(this) {
            if (totalSamples <= 0) return null
            captured = ShortArray(totalSamples)
            var at = 0
            chunks.forEach { c ->
                System.arraycopy(c, 0, captured, at, c.size)
                at += c.size
            }
            chunks = ArrayList()
            totalSamples = 0
        }
        return FloatArray(captured.size) { captured[it] / 32_768f }
    }

    fun cancel() {
        running = false
        worker?.let { runCatching { it.join(1_000) } }
        worker = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        synchronized(this) {
            chunks = ArrayList()
            totalSamples = 0
        }
        level = 0f
    }

    /** Judges a finished take so the user is told *why* a clip will clone badly. */
    fun assess(samples: FloatArray): Quality {
        val seconds = samples.size.toDouble() / SAMPLE_RATE
        if (samples.isEmpty()) {
            return Quality(0.0, 0f, 0f, "Nothing was recorded — check the microphone.", false)
        }
        var sum = 0.0
        var peak = 0f
        for (s in samples) {
            sum += s.toDouble() * s
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        val rms = sqrt(sum / samples.size).toFloat()
        val warning = when {
            seconds < MIN_SECONDS ->
                "Too short (${"%.1f".format(seconds)}s). Read a little further — aim for 5–15 seconds."
            rms < SILENCE_RMS -> "Almost silent — the microphone may be muted or too far away."
            peak > CLIPPING_PEAK -> "Too loud, which distorts the clone. Move back a little and try again."
            rms < QUIET_RMS ->
                "Quite quiet — the clone copies the room as well as your voice. " +
                    "Move closer or speak up a little."
            seconds > IDEAL_MAX_SECONDS ->
                "Longer than needed (" + "%.1f".format(seconds) + "s). It will still work."
            else -> null
        }
        val usable = seconds >= MIN_SECONDS && rms >= SILENCE_RMS && peak <= CLIPPING_PEAK
        return Quality(seconds, rms, peak, warning, usable)
    }
}
