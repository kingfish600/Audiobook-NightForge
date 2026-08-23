package com.forge.audiobookforge.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit mono WAV writer — used for voice previews and lossless chapter export. */
object Wav {

    fun write(out: File, samples: FloatArray, sampleRate: Int) {
        val streamAndFile = openStream(out, sampleRate)
        try {
            append(streamAndFile, samples)
        } finally {
            finish(streamAndFile)
        }
    }

    /** Streaming variant: open once, [append] per chunk, [finish] at chapter end. */
    fun openStream(out: File, sampleRate: Int): Pair<FileOutputStream, File> {
        val f = FileOutputStream(out)
        writeHeader(f, 0, sampleRate) // sizes patched by finish()
        return f to out
    }

    fun append(streamAndFile: Pair<FileOutputStream, File>, samples: FloatArray) {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32_767f).toInt().toShort())
        streamAndFile.first.write(buf.array())
    }

    /** Rewrites the RIFF/data size fields with actual byte counts and closes. */
    fun finish(streamAndFile: Pair<FileOutputStream, File>) {
        val (stream, file) = streamAndFile
        stream.fd.sync()
        stream.close()
        val dataSize = (file.length() - 44).coerceAtLeast(0)
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4); raf.writeInt(java.lang.Integer.reverseBytes((36 + dataSize).toInt()))
            raf.seek(40); raf.writeInt(java.lang.Integer.reverseBytes(dataSize.toInt()))
        }
    }

    private fun writeHeader(f: FileOutputStream, dataSize: Int, sampleRate: Int) {
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
    }
}
