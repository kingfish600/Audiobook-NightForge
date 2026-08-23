package com.forge.audiobookforge.audio

/** Small audio conversion helpers. */
object AudioOps {

    /** Sample rates the Opus codec accepts. */
    val OPUS_RATES = intArrayOf(8_000, 12_000, 16_000, 24_000, 48_000)

    fun isOpusRate(rate: Int): Boolean = OPUS_RATES.contains(rate)

    /** Nearest Opus-legal rate (prefers not resampling when possible). */
    fun opusSafeRate(sourceRate: Int): Int =
        if (isOpusRate(sourceRate)) sourceRate
        else OPUS_RATES.minByOrNull { kotlin.math.abs(it - sourceRate) } ?: 24_000

    /** Linear-interpolation resample of mono float samples. Good enough for speech. */
    fun resampleLinear(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || samples.isEmpty()) return samples
        val ratio = srcRate.toDouble() / dstRate
        val outLen = Math.max(1, Math.round(samples.size / ratio).toInt())
        val out = FloatArray(outLen)
        var pos = 0.0
        for (i in 0 until outLen) {
            val i0 = pos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (pos - pos.toInt()).toFloat()
            out[i] = samples[i0] * (1f - frac) + samples[i1] * frac
            pos += ratio
        }
        return out
    }
}
