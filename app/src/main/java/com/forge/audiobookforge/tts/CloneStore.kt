package com.forge.audiobookforge.tts

import com.forge.audiobookforge.audio.RefAudio
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
        if (RefAudio.readBytes(wavBytes) == null) {
            return "That audio could not be read. Use a WAV file (16-bit PCM or 32-bit float, 8–96 kHz)."
        }
        root.mkdirs()
        wavFor(safe).writeBytes(wavBytes)
        txtFor(safe).writeText(clean)
        return null
    }

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
                if (add(wav.nameWithoutExtension, wav.readBytes(), transcript) == null) added++
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
