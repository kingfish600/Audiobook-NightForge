package com.forge.audiobookforge.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams float PCM into an AAC-LC encoded .m4a file (one file per chapter).
 * Uses only platform APIs — no ffmpeg dependency.
 */
class AacChapterWriter(
    outFile: File,
    private val sampleRate: Int = 24_000,
    bitRate: Int = 64_000,
) : Closeable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(
            MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        start()
    }

    private val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private var nextPtsUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Append PCM samples in [-1f, 1f]. Safe to call repeatedly. */
    fun writePcm(samples: FloatArray) {
        if (samples.isEmpty()) return
        val byteBuf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) {
            val s = (f.coerceIn(-1f, 1f) * 32_767f).toInt().toShort()
            byteBuf.putShort(s)
        }
        byteBuf.flip()

        var bytesFed = 0
        while (byteBuf.hasRemaining()) {
            val chunkBytes = minOf(byteBuf.remaining(), 4096 * 2)
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(endOfStream = false); continue }
            val ib = codec.getInputBuffer(idx) ?: continue
            ib.clear()
            repeat(chunkBytes) { ib.put(byteBuf.get()) }
            val ptsUs = nextPtsUs + (bytesFed / 2L) * 1_000_000L / sampleRate
            codec.queueInputBuffer(idx, 0, chunkBytes, ptsUs, 0)
            bytesFed += chunkBytes
        }
        nextPtsUs += samples.size.toLong() * 1_000_000L / sampleRate
        totalSamples += samples.size
        drain(endOfStream = false)
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    continue // keep waiting for the EOS-flagged buffer
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val ob = codec.getOutputBuffer(outIdx)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (ob != null && !isConfig && bufferInfo.size > 0 && muxerStarted && trackIndex >= 0) {
                        ob.position(bufferInfo.offset)
                        ob.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, ob, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun durationMs(): Long = totalSamples * 1_000L / sampleRate

    override fun close() {
        try {
            drain(endOfStream = true)
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) muxer.stop()
        } finally {
            muxer.release()
        }
    }
}
