package com.forge.audiobookforge.tts

import com.forge.audiobookforge.audio.RefAudio
import com.forge.audiobookforge.audio.Wav
import java.io.File

/**
 * The cloned-voice library.
 *
 * A clone is a short reference recording plus the exact words spoken in it —
 * zero-shot engines (ZipVoice) need both: the clip is the timbre, the transcript
 * is what teaches the model how that timbre maps to phonemes. A wrong transcript
 * audibly degrades the clone, which is why every clone stores its own text.
 *
 * Pure file layout (no Android types) so it can be unit-tested.
 */
class CloneStore(private val root: File) {

    data class Clone(val name: String, val wav: File, val text: String)

    companion object {
        /**
         * Ceiling for any file that claims to be a voice clip. Far beyond any useful reference
         * (minutes of 24 kHz PCM) while small enough that reading one cannot exhaust memory.
         * The settings screen uses this too, so "too big" has ONE definition.
         */
        const val MAX_CLIP_BYTES = 50L * 1024 * 1024

        /**
         * Below this a reference teaches the model almost nothing. This is the hard floor for
         * an imported clip; the in-app recorder asks for more (VoiceRecorder.MIN_SECONDS, 4s)
         * because it can guide the user there.
         */
        const val MIN_REFERENCE_SECONDS = 2.0
    }

    private fun wavFor(name: String) = File(root, "$name.wav")
    private fun txtFor(name: String) = File(root, "$name.txt")

    /** Names of every usable clone, sorted for stable UI ordering. */
    fun list(): List<Clone> {
        root.mkdirs()
        return root.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.mapNotNull { wav ->
                val name = wav.name.removeSuffix(".wav")
                val text = txtFor(name).takeIf { it.isFile }?.readText()?.trim().orEmpty()
                if (text.isEmpty()) null else Clone(name, wav, text)
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun get(name: String): Clone? = list().firstOrNull { it.name == name }

    /** True when the clip parses as audio we can feed to the engine. */
    fun isUsable(wav: File): Boolean = RefAudio.read(wav) != null

    /** Same check for bytes straight off the file picker, before anything is saved. */
    fun canUse(bytes: ByteArray): Boolean = problemWith(bytes) == null

    /**
     * Why a clip cannot be used, or null when it is fine. Checked as soon as a file
     * is chosen so the user is not told after typing a transcript.
     */
    fun problemWith(bytes: ByteArray): String? {
        val audio = runCatching { RefAudio.readBytes(bytes) }.getOrNull()
            ?: return "That file is not audio the engine can use. Use a WAV file " +
                "(16-bit PCM or 32-bit float, 8–96 kHz)."
        // A file that parses can still be useless: a truncated download gives a valid
        // header with almost no audio in it, which would clone into noise.
        val seconds = audio.first.size.toDouble() / audio.second
        if (seconds < MIN_REFERENCE_SECONDS) {
            return "That clip is only ${"%.2f".format(seconds)}s long. Cloning needs at least " +
                "${MIN_REFERENCE_SECONDS.toInt()} seconds of speech."
        }
        return null
    }

    /**
     * Adds (or replaces) a clone. [wavBytes] must be a WAV the engine can read —
     * refused up front rather than crashing later inside native code.
     * @return null on success, otherwise a message for the user.
     */
    fun add(name: String, wavBytes: ByteArray, text: String): String? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return "Give the voice a name."
        val clean = text.trim()
        if (clean.isEmpty()) return "Type the words spoken in the clip — the engine needs them to clone the voice."
        if (clean.length > 600) return "Keep the transcript under 600 characters (a few sentences is plenty)."
        problemWith(wavBytes)?.let { return it }
        root.mkdirs()
        wavFor(safe).writeBytes(wavBytes)
        txtFor(safe).writeText(clean)
        return null
    }

    /**
     * Saves a clip recorded in the app (24 kHz mono float samples). Written through
     * the same WAV writer the rest of the app uses, then validated by [add], so a
     * recorded clone and an imported one go through identical checks.
     */
    fun addRecorded(name: String, samples: FloatArray, rate: Int, text: String): String? {
        if (samples.isEmpty()) return "Nothing was recorded."
        root.mkdirs()
        val tmp = File(root, "recording.tmp.wav")
        return try {
            Wav.write(tmp, samples, rate)
            // Bounded: the recorder has no maximum duration, so a long take would otherwise be
            // read into memory whole before [add] could reject it.
            val bytes = readClip(tmp) ?: return "That recording is too long to use as a clip."
            add(name, bytes, text)
        } catch (e: Exception) {
            "Could not save the recording."
        } finally {
            tmp.delete()
        }
    }

    /**
     * Reads a clip from disk, refusing to buffer more than [MAX_CLIP_BYTES].
     *
     * Every path that feeds [add] must come through here: reading first and validating
     * afterwards is what let a huge file exhaust memory before the length check ran. That was
     * fixed for the file picker, and was still present in the two paths below.
     */
    private fun readClip(file: File): ByteArray? = runCatching {
        file.inputStream().use {
            com.forge.audiobookforge.util.BoundedRead.readAtMost(it, MAX_CLIP_BYTES)
        }
    }.getOrNull()

    fun delete(name: String) {
        wavFor(name).delete()
        txtFor(name).delete()
    }

    /**
     * Imports the reference clips an engine bundle ships (ZipVoice bundles carry
     * test_wavs/ plus a prompt.txt that maps each clip to its exact transcript).
     * These make cloning usable with zero setup — the user can also add their own.
     */
    fun importBundled(modelDir: File): Int {
        val dir = File(modelDir, "test_wavs")
        if (!dir.isDirectory) return 0
        val transcripts = File(dir, "prompt.txt")
            .takeIf { it.isFile }
            ?.readLines()
            .orEmpty()
            .mapNotNull { line ->
                val t = line.trim()
                val sep = t.indexOfFirst { it == ' ' || it == '\t' }
                if (sep <= 0) null else t.substring(0, sep) to t.substring(sep + 1).trim()
            }
            .toMap()
        var added = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.forEach { wav ->
                val transcript = transcripts[wav.name]
                if (transcript.isNullOrBlank()) return@forEach
                // Bounded: these come from a downloaded engine bundle, so their size is not
                // something this app controls.
                val bytes = readClip(wav) ?: return@forEach
                if (add(wav.nameWithoutExtension, bytes, transcript) == null) added++
            }
        return added
    }

    private fun sanitize(name: String): String =
        name.trim().map { c ->
            when {
                c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == '(' || c == ')' -> c
                else -> '_'
            }
        }.joinToString("").trim().take(48)
}
