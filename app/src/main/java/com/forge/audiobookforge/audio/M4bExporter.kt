package com.forge.audiobookforge.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.forge.audiobookforge.data.model.Book
import java.io.File
import java.nio.ByteBuffer

/**
 * Builds a single-file .m4b from a book's finished AAC chapters WITHOUT
 * re-encoding: samples are copied verbatim from each chapter's .m4a into one
 * continuous MPEG-4 container, timestamps rebased per chapter. Nero `chpl`
 * chapter atoms are injected afterwards by [ChapterBox].
 *
 * Constraint: every finished chapter must be AAC (.m4a). Opus lives in Ogg and
 * WAV is PCM — neither remuxes into MP4 losslessly here.
 */
object M4bExporter {

    class IncompatibleBook(message: String) : Exception(message)

    data class Entry(val title: String, val startMs: Long)

    data class Result(val file: File, val chapters: Int, val durationMs: Long)

    fun requiresAac(book: Book): Boolean =
        book.chapters.any { it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE } &&
            book.chapters.any {
                it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE &&
                    (it.audioFile == null || !it.audioFile!!.endsWith(".m4a", true))
            }

    fun export(book: Book, audioDir: File, out: File): Result {
        val done = book.chapters.filter {
            it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE && it.audioFile != null
        }.sortedBy { it.index }
        require(done.isNotEmpty()) { "No finished chapters to bundle" }
        val bad = done.filter { !it.audioFile!!.endsWith(".m4a", true) }
        if (bad.isNotEmpty()) {
            throw IncompatibleBook(
                "Single-file .m4b requires AAC chapters. These are ${bad.first()!!.audioFile!!
                    .substringAfterLast('.')}: ${bad.joinToString(limit = 3) { it.title }}"
            )
        }

        val tmp = File(out.absolutePath + ".part")
        tmp.delete()

        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val buf = ByteBuffer.allocateDirect(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var baseUs = 0L
        val entries = ArrayList<Entry>(done.size)

        try {
            for (ch in done) {
                val src = File(audioDir, ch.audioFile!!)
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(src.absolutePath)
                    var audioIdx = -1
                    var fmt: MediaFormat? = null
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            audioIdx = i; fmt = f; break
                        }
                    }
                    check(audioIdx >= 0) { "No audio track in ${src.name}" }
                    if (track < 0) {
                        track = muxer.addTrack(fmt!!)
                        muxer.start()
                    }
                    ex.selectTrack(audioIdx)
                    val chapterBase = baseUs
                    var firstUs = -1L
                    var lastUs = -1L
                    while (true) {
                        val idx = ex.sampleTrackIndex
                        if (idx != audioIdx) break
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val t = ex.sampleTime
                        if (firstUs < 0) firstUs = t
                        lastUs = t
                        info.set(0, n, chapterBase + (t - firstUs), ex.sampleFlags)
                        muxer.writeSampleData(track, buf, info)
                        if (!ex.advance()) break
                    }
                    val chapterLenUs = if (lastUs >= firstUs) (lastUs - firstUs) + 23_000 // ~one AAC frame slack
                                       else ch.durationMs * 1_000
                    baseUs += chapterLenUs
                    entries += Entry(ch.title, chapterBase / 1_000)
                } finally {
                    ex.release()
                }
            }
            muxer.stop()
        } catch (t: Throwable) {
            runCatching { muxer.release() }
            tmp.delete()
            throw t
        } finally {
            runCatching { muxer.release() }
        }

        val totalMs = baseUs / 1_000
        ChapterBox.writeChapters(tmp, entries, totalMs)
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return Result(out, entries.size, totalMs)
    }
}

/**
 * Minimal ISO-BMFF editor: injects a Nero `chpl` chapter box under
 * moov.udta. MediaMuxer writes moov as the final top-level atom, which lets us
 * rewrite just that tail. Files larger than 2 GB (64-bit box sizes) are
 * rejected rather than mis-written.
 */
private object ChapterBox {

    private data class Child(val type: String, val bytes: ByteArray)

    fun writeChapters(f: File, chapters: List<M4bExporter.Entry>, totalMs: Long) {
        // Top-level walk to locate moov (must be last box). Uses RandomAccessFile
        // because InputStream.skip() may legally skip FEWER bytes than requested,
        // silently desyncing the scan. On failure the exception carries the full
        // box layout + head hexdump so remote diagnosis needs zero guessing.
        var moovOff = -1L
        var moovLen = 0
        val layout = ArrayList<String>()
        val len = f.length()
        java.io.RandomAccessFile(f, "r").use { raf ->
            var off = 0L
            while (off + 8 <= len) {
                raf.seek(off)
                val hdr = ByteArray(8).also { raf.readFully(it) }
                var size = readU32(hdr, 0).toLong() and 0xFFFFFFFFL
                val type = String(hdr, 4, 4, Charsets.US_ASCII)
                if (size == 1L) {
                    // 64-bit largesize box
                    val lb = ByteArray(8).also { raf.readFully(it) }
                    var ls = 0L
                    for (b in lb) ls = (ls shl 8) or (b.toLong() and 0xFF)
                    size = ls
                } else if (size == 0L) {
                    size = len - off // extends to EOF
                }
                layout.add("$type@$off+$size")
                if (type == "moov") {
                    check(size <= Int.MAX_VALUE) { "moov too large (>2GB)" }
                    moovOff = off
                    moovLen = size.toInt()
                }
                if (size < 8) break // malformed; avoid infinite loop
                off += size
            }
        }
        if (moovOff < 0) {
            val head = ByteArray(48)
            java.io.RandomAccessFile(f, "r").use { raf -> raf.seek(0); raf.readFully(head) }
            val hex = head.joinToString("") { b -> "%02x".format(b) }
            error("moov atom not found | len=$len | boxes=${layout.joinToString(",")} | head=$hex")
        }
        check(moovOff + moovLen >= len) { "moov is not the final atom — refusing to edit" }
        check(moovLen < Int.MAX_VALUE) { "moov too large (>2GB) for safe editing" }

        val moov = f.inputStream().use { ins ->
            ins.skip(moovOff)
            val b = ByteArray(moovLen)
            var n = 0
            while (n < moovLen) { val k = ins.read(b, n, moovLen - n); if (k < 0) break; n += k }
            b
        }

        // Parse moov children.
        val kids = ArrayList<Child>()
        var p = 8
        while (p + 8 <= moov.size) {
            val sz = readU32(moov, p)
            if (sz <= 0) break
            val ty = String(moov, p + 4, 4, Charsets.US_ASCII)
            kids += Child(ty, moov.copyOfRange(p, p + sz))
            p += sz
        }

        val chplPayload = buildPayload(chapters, totalMs)
        val chpl = box("chpl", chplPayload)
        val udta = box("udta", chpl) // fresh udta containing only our chpl

        val out = java.io.ByteArrayOutputStream((moov.size + udta.size + 16))
        out.write(int32(moov.size + udta.size)) // placeholder, patched below
        out.write("moov".toByteArray(Charsets.US_ASCII))
        var wroteUdta = false
        for (k in kids) {
            if (k.type == "udta") {
                // extend an existing udta: rebuild it with original children + ours
                val ukids = ArrayList<Child>()
                var q = 8
                while (q + 8 <= k.bytes.size) {
                    val sz = readU32(k.bytes, q)
                    if (sz <= 0) break
                    ukids += Child(String(k.bytes, q + 4, 4, Charsets.US_ASCII), k.bytes.copyOfRange(q, q + sz))
                    q += sz
                }
                ukids += Child("chpl", chpl)
                val merged = mergeChildren(ukids)
                out.write(int32(merged.size)); out.write("udta".toByteArray(Charsets.US_ASCII))
                out.write(merged, 8, merged.size - 8)
                wroteUdta = true
            } else {
                out.write(k.bytes)
            }
        }
        if (!wroteUdta) out.write(udta)

        val newMoov = out.toByteArray()
        writeU32(newMoov, 0, newMoov.size)

        java.io.RandomAccessFile(f, "rw").use { raf ->
            raf.seek(moovOff)
            raf.write(newMoov)
            raf.setLength(moovOff + newMoov.size)
        }
    }

    private fun mergeChildren(kids: List<Child>): ByteArray {
        var total = 8
        kids.forEach { total += it.bytes.size }
        val arr = ByteArray(total)
        writeU32(arr, 0, total)
        "udta".toByteArray(Charsets.US_ASCII).copyInto(arr, 4)
        var q = 8
        kids.forEach { it.bytes.copyInto(arr, q); q += it.bytes.size }
        return arr
    }

    private fun buildPayload(chapters: List<M4bExporter.Entry>, totalMs: Long): ByteArray {
        val titles = chapters.map { (t, _) -> t.take(200).toByteArray(Charsets.UTF_8) }
        var body = 4 + chapters.size * 9 + titles.sumOf { 1 + it.size }
        val buf = ByteBuffer.allocate(8 + body).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.put(0.toByte()); buf.putShort(0); buf.put(0.toByte())   // version 0, flags
        buf.putInt(chapters.size)
        for ((i, e) in chapters.withIndex()) {
            val endMs = chapters.getOrNull(i + 1)?.startMs ?: totalMs
            buf.putInt((e.startMs * 10_000).toInt())
            buf.putInt((endMs.coerceAtLeast(e.startMs) * 10_000).toInt())
            val t = titles[i]
            buf.put(t.size.coerceAtMost(255).toByte())
            buf.put(t, 0, t.size.coerceAtMost(255))
        }
        buf.flip()
        val arr = ByteArray(buf.remaining()); buf.get(arr); return arr
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val arr = ByteArray(8 + payload.size)
        writeU32(arr, 0, arr.size)
        type.toByteArray(Charsets.US_ASCII).copyInto(arr, 4)
        payload.copyInto(arr, 8)
        return arr
    }

    private fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readU32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
