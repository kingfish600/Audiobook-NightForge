package com.forge.audiobookforge.tts

import android.content.Context
import com.forge.audiobookforge.audio.RefAudio
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Optional speech recogniser, used to write the transcript of an imported clip.
 *
 * A cloned voice is a reference recording *plus the exact words spoken in it*, and
 * a wrong transcript quietly ruins the clone. Recorded clips get their transcript
 * from the read-aloud script; an imported clip has no such help, so this listens to
 * it once and writes the words down.
 *
 * Whisper tiny is deliberately multilingual (covers Chinese and English, matching
 * the cloning engines) and auto-detects the language, so a clip in either language
 * works without the user choosing anything.
 */
class Transcriber(private val context: Context, private val models: ModelManager) {

    companion object {
        const val URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
        const val APPROX_MB = 111

        /** Whisper is trained at 16 kHz. */
        const val SAMPLE_RATE = 16_000
    }

    val dir: File get() = File(context.filesDir, "asr/whisper-tiny")

    private fun encoder(): File? = dir.listFiles { f -> f.isFile && f.name.startsWith("tiny-encoder") }?.minByOrNull { it.name.length }
    private fun decoder(): File? = dir.listFiles { f -> f.isFile && f.name.startsWith("tiny-decoder") }?.minByOrNull { it.name.length }
    private fun tokens(): File? = dir.listFiles { f -> f.isFile && f.name.endsWith("tokens.txt") }?.minByOrNull { it.name.length }

    fun isReady(): Boolean = encoder() != null && decoder() != null && tokens() != null

    /** Downloads the recogniser if it is not installed yet. */
    fun install(onPhase: (String) -> Unit = {}) {
        if (isReady()) return
        onPhase("Downloading transcriber (${APPROX_MB} MB)…")
        models.fetchBundle(URL, dir, onPhase)
        onPhase("")
    }

    fun remove() {
        dir.deleteRecursively()
    }

    /**
     * Transcribes a clip. Returns null when the recogniser is not installed or the
     * audio cannot be read; an empty string means it heard nothing intelligible.
     */
    fun transcribe(wav: File): String? {
        if (!isReady()) return null
        val audio = RefAudio.read(wav) ?: return null
        // 16 kHz is what Whisper expects; the reader and its resampler are tested.
        val pcm = RefAudio.resample(audio.first, audio.second, SAMPLE_RATE)
        if (pcm.isEmpty()) return null

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder()!!.absolutePath,
                    decoder = decoder()!!.absolutePath,
                    language = "",      // empty = detect from the audio
                    task = "transcribe",
                ),
                tokens = tokens()!!.absolutePath,
                numThreads = 4,
                provider = "cpu",
                debug = false,
            ),
        )
        val recognizer = OfflineRecognizer(config = config)
        return try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(pcm, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            null
        } finally {
            // Free ~110 MB of model memory straight away; transcription is occasional.
            runCatching { recognizer.release() }
        }
    }
}
