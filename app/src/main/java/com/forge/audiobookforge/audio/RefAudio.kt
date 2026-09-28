package com.forge.audiobookforge.audio

import java.io.File

/**
 * Reads a WAV file as mono float samples for voice-cloning references.
 *
 * Deliberately strict: cloning feeds this straight into native code, and a
 * misread header (wrong bit depth, odd sample rate, garbage chunk offsets)
 * crashes the process rather than throwing. Everything unusual returns null.
 *
 * Accepted: PCM 16-bit and IEEE float32, mono or multi-channel (downmixed),
 * 8 kHz – 96 kHz. Anything else — 8-bit, 24/32-bit int, ADPCM, exotic chunks —
 * is refused so the caller can tell the user instead of dying.
 */
object RefAudio {

    /** @return samples (mono, -1..1) and the sample rate, or null if unusable. */
    fun read(file: File): Pair<FloatArray, Int>? = runCatching { readBytes(file.readBytes()) }.getOrNull()

    internal fun readBytes(all: ByteArray): Pair<FloatArray, Int>? {
        val len = all.size
        if (len < 44) return null
        val riff = all[0] == 'R'.code.toByte() && all[1] == 'I'.code.toByte() &&
            all[2] == 'F'.code.toByte() && all[3] == 'F'.code.toByte() &&
            all[8] == 'W'.code.toByte() && all[9] == 'A'.code.toByte() &&
            all[10] == 'V'.code.toByte() && all[11] == 'E'.code.toByte()
        if (!riff) return null

        // Walk the chunk list: fmt describes the samples, data holds them. Both
        // must be found by id — files with LIST/fact chunks are common.
        var pos = 12
        var fmtOff = -1
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= len) {
            val id = String(all, pos, 4, Charsets.US_ASCII)
            val sz = readU32(all, pos + 4)
            if (id == "fmt " && sz >= 16) fmtOff = pos + 8
            if (id == "data") {
                dataOff = pos + 8
                dataLen = minOf(sz, len - dataOff)
                break
            }
            pos += 8 + sz + (sz and 1)
        }
        if (fmtOff < 0 || dataOff < 0 || dataLen <= 0) return null

        val audioFormat = readU16(all, fmtOff)
        val channels = readU16(all, fmtOff + 2)
        val rate = readU32(all, fmtOff + 4)
        val bits = readU16(all, fmtOff + 14)
        if (channels !in 1..8) return null
        if (rate < 8_000 || rate > 96_000) return null
        val pcm16 = audioFormat == 1 && bits == 16
        val f32 = audioFormat == 3 && bits == 32
        if (!pcm16 && !f32) return null

        val frame = channels * (bits / 8)
        if (frame <= 0) return null
        val usable = dataLen - (dataLen % frame)
        val frames = usable / frame
        if (frames <= 0) return null

        val out = FloatArray(frames)
        var b = dataOff
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val at = b + c * (bits / 8)
                sum += if (pcm16) {
                    readI16(all, at) / 32_768f
                } else {
                    Float.fromBits(readU32(all, at).toInt())
                }
            }
            out[i] = sum / channels
            b += frame
        }
        return out to rate
    }

    /** Best-effort resample to [targetRate] (linear). Returns input when equal. */
    fun resample(samples: FloatArray, fromRate: Int, targetRate: Int): FloatArray {
        if (fromRate == targetRate || samples.isEmpty()) return samples
        val ratio = targetRate.toDouble() / fromRate
        val outLen = (samples.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val src = i / ratio
            val i0 = src.toInt()
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val t = (src - i0).toFloat()
            out[i] = samples[i0] * (1f - t) + samples[i1] * t
        }
        return out
    }

    private fun readU16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun readI16(b: ByteArray, at: Int): Int =
        readU16(b, at).toShort().toInt()

    private fun readU32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
