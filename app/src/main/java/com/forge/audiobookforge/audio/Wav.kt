package com.forge.audiobookforge.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit mono WAV writer — used for voice previews. */
object Wav {

    fun write(out: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        FileOutputStream(out).use { f ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)              // PCM chunk size
            header.putShort(1)             // PCM format
            header.putShort(1)             // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)  // byte rate
            header.putShort(1)             // block align
            header.putShort(16)            // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataSize)
            f.write(header.array())

            val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                buf.putShort((s.coerceIn(-1f, 1f) * 32_767f).toInt().toShort())
            }
            f.write(buf.array())
        }
    }
}
