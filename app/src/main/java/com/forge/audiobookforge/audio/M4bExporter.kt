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

    /** Test hook: the exact Nero chpl payload bytes for these chapters. */
    internal fun chplPayloadForTest(chapters: List<Entry>): ByteArray = ChapterBox.buildPayload(chapters)

    internal fun verifyChplForTest(f: File, expectedChapters: Int): Boolean =
        ChapterBox.verifyChpl(f, expectedChapters)

    data class Result(
        val file: File,
        val chapters: Int,
        val durationMs: Long,
        val neroChpl: Boolean = false,
        val appleWired: Boolean = false,
        val appleRefusedByDevice: Boolean = false,
    ) { val anatomy: String get() =
        "chapters embedded — Nero:" + (if (neroChpl) "yes" else "no") +
        " · Apple:" + when {
            appleWired -> "wired"
            appleRefusedByDevice -> "refused by device"
            else -> "off"
        } }

    fun requiresAac(book: Book): Boolean =
        book.chapters.any { it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE } &&
            book.chapters.any {
                it.status == com.forge.audiobookforge.data.model.ChapterStatus.DONE &&
                    (it.audioFile == null || !it.audioFile!!.endsWith(".m4a", true))
            }

    fun export(book: Book, audioDir: File, out: File, appleChapters: Boolean = true): Result {
        var neroOk = false
        var appleOk = false
        var appleRefused = false
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
        var textTrack = -1
        var baseUs = 0L
        val entries = ArrayList<Entry>(done.size)

        try {
            // Apple-style chapter track (timed text): the universally-read
            // chapter system. Titles are emitted as one text sample per
            // chapter; ChapterBox wires it to the audio track via tref/chap
            // after muxing, alongside the legacy Nero chpl.
            val tfmt = MediaFormat()
            tfmt.setString(MediaFormat.KEY_MIME, "text/3gpp-tt")
            tfmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)

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
                        // Vendor media stacks vary wildly on timed-text support;
                        // some native-abort instead of throwing. Degrade, never die.
                        textTrack = if (appleChapters) runCatching { muxer.addTrack(tfmt) }.getOrDefault(-1) else -1
                        if (textTrack < 0 && appleChapters) appleRefused = true
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
                    // Emit this chapter's title sample now (per-track order stays
                    // monotonic; cross-track interleaving is the muxer's job).
                    if (textTrack >= 0 && chapterLenUs > 0) {
                        val titleBytes = ch.title.toByteArray(Charsets.UTF_8)
                        val tb = ByteBuffer.allocateDirect(2 + titleBytes.size)
                        tb.putShort(titleBytes.size.toShort())
                        tb.put(titleBytes)
                        tb.flip()
                        info.set(0, tb.remaining(), chapterBase, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        val wrote = runCatching { muxer.writeSampleData(textTrack, tb, info) }.isSuccess
                        if (!wrote) textTrack = -1 // first rejection disables the track for the rest of the bundle
                    }
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
        neroOk = ChapterBox.verifyChpl(tmp, entries.size)
        if (textTrack >= 0) {
            ChapterBox.injectChapReference(tmp)
            appleOk = true
        }
        // No hand-built fallback: constructing binary boxes that must satisfy
        // every parser proved worse than honest absence. Refusing ROMs ship
        // Nero-chpl-only (see Settings toggle).
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        // Report what the file actually carries: the Nero chpl box holds at most
        // 255 chapters, so when the Apple chapter track was refused this is the
        // only chapter source and the count must not overstate it.
        val reportedChapters = if (appleOk) entries.size else entries.size.coerceAtMost(255)
        return Result(out, reportedChapters, totalMs, neroOk, appleOk, appleRefused)
    }
}

/**
 * Minimal ISO-BMFF editor: injects a Nero `chpl` chapter box under
 * moov.udta. MediaMuxer writes moov as the final top-level atom, which lets us
 * rewrite just that tail. Files larger than 2 GB (64-bit box sizes) are
 * rejected rather than mis-written.
 */
internal object ChapterBox {

    private data class Child(val type: String, val bytes: ByteArray)

    fun writeChapters(f: File, chapters: List<M4bExporter.Entry>, totalMs: Long) {
        // Top-level walk to locate moov (must be last box). Uses RandomAccessFile
        // because InputStream.skip() may legally skip FEWER bytes than requested,
        // silently desyncing the scan. On failure the exception carries the full
        // box layout + head hexdump so remote diagnosis needs zero guessing.
        val (moovOff, moovLen) = locateMoovRobust(f)
        // moov located by signature + arithmetic proof against EOF; upstream box
        // corruption (this ROM's unfinalized mdat largesize) is irrelevant.
        check(moovOff + moovLen >= f.length()) { "moov is not the final atom — refusing to edit" }
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

        val chplPayload = buildPayload(chapters)
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

    /**
     * Apple-style chapter wiring: finds the text track and the audio track in
     * moov, then inserts tref[chap]=<textTrackId> inside the audio trak. This
     * is the chapter system iTunes/iOS/every serious player reads; chpl alone
     * proved insufficient in the wild (v0.6.x field reports).
     */
    fun injectChapReference(f: File) {
        val moov = locateMoov(f) ?: error("chap ref: moov not found | len=${f.length()}")
        val (moovOff, moovLen) = moov
        check(moovOff + moovLen >= f.length()) { "moov not final atom (chap)" }

        val bytes = readAt(f, moovOff, moovLen)
        data class Trak(val index: Int, val bytes: ByteArray)
        val traks = ArrayList<Pair<Int, ByteArray>>() // childIndex -> trak bytes
        var p = 8
        while (p + 8 <= bytes.size) {
            val sz = readU32(bytes, p)
            if (sz <= 0) break
            val ty = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (ty == "trak") traks += traks.size to bytes.copyOfRange(p, p + sz)
            p += sz
        }
        // Single-track output (text track rejected by this ROM, or user disabled
        // Apple chapters): nothing to wire — chpl chapters are already present.
        if (traks.size < 2) return

        fun hdlrSubtype(trak: ByteArray): String? {
            // walk children -> mdia -> children -> hdlr; subtype at offset 16..20 of hdlr payload
            var q = 8
            while (q + 8 <= trak.size) {
                val sz = readU32(trak, q)
                if (sz <= 0) break
                if (String(trak, q + 4, 4, Charsets.US_ASCII) == "mdia") {
                    val mdia = trak.copyOfRange(q, q + sz)
                    var r = 8
                    while (r + 8 <= mdia.size) {
                        val msz = readU32(mdia, r)
                        if (msz <= 0) break
                        if (String(mdia, r + 4, 4, Charsets.US_ASCII) == "hdlr") {
                            return String(mdia, r + 8 + 8, 4, Charsets.US_ASCII) // ver/flags(4)+pre_defined(4)
                        }
                        r += msz
                    }
                }
                q += sz
            }
            return null
        }

        fun trackId(trak: ByteArray): Int {
            var q = 8
            while (q + 8 <= trak.size) {
                val sz = readU32(trak, q)
                if (sz <= 0) break
                if (String(trak, q + 4, 4, Charsets.US_ASCII) == "tkhd") {
                    val version = trak[q + 8].toInt()
                    val idPos = q + 8 + 4 + (if (version == 1) 16 else 8)
                    return readU32(trak, idPos)
                }
                q += sz
            }
            error("tkhd not found")
        }

        var audioIdx = -1; var textIdx = -1; var textId = -1
        for ((i, trak) in traks) {
            when (hdlrSubtype(trak)) {
                "soun" -> audioIdx = i
                "sbtl", "text", "subt" -> { textIdx = i; textId = trackId(trak) }
            }
        }
        // Unrecognized handler layout: ship without tref rather than fail.
        // Nero chpl remains embedded for players that read it.
        if (audioIdx < 0 || textIdx < 0 || textId <= 0) return

        // Build tref box containing chap -> textId
        val chapPayload = ByteArray(4)
        writeU32(chapPayload, 0, textId)
        val chap = box("chap", chapPayload)
        val tref = box("tref", chap)

        // Insert into audio trak right after tkhd
        val audioBytes = traks.firstOrNull { it.first == audioIdx }!!.second
        var q = 8; var insertAt = -1
        while (q + 8 <= audioBytes.size) {
            val sz = readU32(audioBytes, q); if (sz <= 0) break
            if (String(audioBytes, q + 4, 4, Charsets.US_ASCII) == "tkhd") { insertAt = q + sz; break }
            q += sz
        }
        check(insertAt > 0) { "audio tkhd not found for tref insert" }
        val newTrak = ByteArray(audioBytes.size + tref.size)
        audioBytes.copyInto(newTrak, 0, 0, insertAt)
        tref.copyInto(newTrak, insertAt)
        audioBytes.copyInto(newTrak, insertAt + tref.size, insertAt)
        writeU32(newTrak, 0, newTrak.size)

        // Rebuild moov with new audio trak
        val out = java.io.ByteArrayOutputStream(bytes.size + tref.size + 16)
        out.write(int32(0)); out.write("moov".toByteArray(Charsets.US_ASCII))
        var c = 8
        var i2 = 0
        while (c + 8 <= bytes.size) {
            val sz = readU32(bytes, c); if (sz <= 0) break
            val ty = String(bytes, c + 4, 4, Charsets.US_ASCII)
            if (ty == "trak" && i2++ == audioIdx) {
                out.write(newTrak)
            } else {
                out.write(bytes, c, sz)
            }
            c += sz
        }
        val newMoov = out.toByteArray()
        writeU32(newMoov, 0, newMoov.size)
        java.io.RandomAccessFile(f, "rw").use { raf ->
            raf.seek(moovOff); raf.write(newMoov); raf.setLength(moovOff + newMoov.size)
        }

        // Self-verify: 'chap' must now exist inside moov bytes on disk
        val verify = readAt(f, moovOff, newMoov.size)
        check(String(verify, Charsets.ISO_8859_1).contains("chap")) { "chap self-check failed" }
    }

    private fun locateMoov(f: File): Pair<Long, Int>? =
        runCatching { locateMoovRobust(f) }.getOrNull()

    /**
     * Finds the FINAL moov atom by scanning raw bytes for the signature and
     * requiring boxStart + declaredSize == fileSize. Immune to any corruption
     * in earlier boxes (observed on RedMagic: mdat largesize left unfinalized).
     */
    private fun locateMoovRobust(f: File): Pair<Long, Int> {
        val len = f.length()
        check(len in 17..Int.MAX_VALUE) { "bad size for edit: $len" }
        val candidates = LinkedHashSet<Long>()
        java.io.RandomAccessFile(f, "r").use { raf ->
            val chunk = 1 shl 20
            val overlap = 8
            var carry = ByteArray(0)
            var base = 0L
            while (base < len) {
                val sz = minOf(chunk.toLong(), len - base).toInt()
                val buf = ByteArray(sz).also { raf.seek(base); raf.readFully(it) }
                val hay = carry + buf
                var i = 0
                while (i + 4 <= hay.size) {
                    if (hay[i] == 'm'.code.toByte() && hay[i + 1] == 'o'.code.toByte() &&
                        hay[i + 2] == 'o'.code.toByte() && hay[i + 3] == 'v'.code.toByte()) {
                        val boxStart = base - carry.size + i - 4
                        if (boxStart >= 0) candidates.add(boxStart)
                    }
                    i++
                }
                carry = hay.copyOfRange(hay.size - overlap, hay.size)
                base += sz
            }
        }
        // Deliberately avoid LinkedHashSet.reversed(): on JDK 21+ that resolves to the
        // SequencedCollection member, which does not exist on older runtimes (NoSuchMethodError).
        for (bs in candidates.toList().asReversed()) { // final atom most likely last candidate
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(bs)
                val hdr = ByteArray(8).also { raf.readFully(it) }
                val s32 = readU32(hdr, 0)
                if (s32 == 1) {
                    val lb = ByteArray(8).also { raf.readFully(it) }
                    var ls = 0L; for (b in lb) ls = (ls shl 8) or (b.toLong() and 0xFF)
                    if (ls == len - bs && ls <= Int.MAX_VALUE) return bs to ls.toInt()
                } else if (s32 > 8 && bs + s32 == len) {
                    return bs to s32
                }
            }
        }
        error("no moov candidate terminates at EOF | len=$len | candidates=${candidates.size}")
    }

    private fun readAt(f: File, off: Long, len: Int): ByteArray =
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(off); ByteArray(len).also { raf.readFully(it) }
        }

    /**
     * Structural proof that the chpl we wrote is actually readable: locate
     * moov/udta/chpl, check the version word, walk every entry, and confirm the
     * declared count matches what we meant to write. The previous check only
     * looked for the ASCII string "chpl" — which the app itself had just
     * written — so it reported success for a box no player could parse.
     */
    fun verifyChpl(f: File, expectedChapters: Int): Boolean = runCatching {
        val (moovOff, moovLen) = locateMoovRobust(f)
        val moov = java.nio.ByteBuffer.wrap(readAt(f, moovOff, moovLen))
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        val cap = moov.capacity()
        fun typeAt(at: Int): String {
            val t = ByteArray(4); moov.get(at, t); return String(t, Charsets.US_ASCII)
        }
        val want = expectedChapters.coerceAtMost(255)
        var p = 8
        while (p + 8 <= cap) {
            val size = moov.getInt(p)
            if (size < 8 || p + size > cap) break
            if (typeAt(p + 4) == "udta") {
                var q = p + 8
                while (q + 8 <= p + size) {
                    val csize = moov.getInt(q)
                    if (csize < 8 || q + csize > p + size) break
                    if (typeAt(q + 4) == "chpl") {
                        val body = q + 8
                        val end = q + csize
                        if (body + 9 > end) return@runCatching false
                        if (moov.getInt(body) != 0x01000000) return@runCatching false
                        val count = moov.get(body + 8).toInt() and 0xFF
                        var e = body + 9
                        repeat(count) {
                            if (e + 9 > end) return@runCatching false
                            e += 8
                            val len = moov.get(e).toInt() and 0xFF
                            e += 1 + len
                            if (e > end) return@runCatching false
                        }
                        return@runCatching count == want
                    }
                    q += csize
                }
            }
            p += size
        }
        false
    }.getOrDefault(false)

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

    /** UTF-8-safe truncation: never split a multi-byte character (which would
     *  emit invalid UTF-8 in the chapter title). */
    private fun utf8Trim(s: String, maxBytes: Int): ByteArray {
        var i = 0
        var total = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val bytes = s.substring(i, i + n).toByteArray(Charsets.UTF_8).size
            if (total + bytes > maxBytes) break
            total += bytes
            i += n
        }
        return s.substring(0, i).toByteArray(Charsets.UTF_8)
    }

    internal fun buildPayload(chapters: List<M4bExporter.Entry>): ByteArray {
        // Nero chpl, version 1. Layout verified against FFmpeg's
        // mov_write_chpl_tag() and GPAC's chpl_box_read():
        //   4B version+flags (0x01000000), 4B reserved, 1B chapter count,
        //   then per chapter: 8B start in 100ns units, 1B title length, title.
        // The previous layout wrote a 32-bit count and 32-bit start/end, which
        // shifted every field: compliant parsers read count = 0 and saw no
        // chapters at all (and starts past 3.58 min overflowed anyway).
        // End times are not stored by this box — players derive them from the
        // next chapter's start (verified with ffprobe).
        val titles = chapters.map { (t, _) -> utf8Trim(t, 255) }
        val count = chapters.size.coerceAtMost(255)
        val body = 4 + 4 + 1 + count * 9 + (0 until count).sumOf { 1 + titles[it].size }
        val buf = ByteBuffer.allocate(body).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.putInt(0x01000000)   // version 1, flags 0
        buf.putInt(0)            // reserved
        buf.put(count.toByte())  // chapter count (8-bit)
        for (i in 0 until count) {
            buf.putLong(chapters[i].startMs * 10_000L)  // 64-bit start, 100ns units
            val t = titles[i]
            buf.put(t.size.toByte())
            buf.put(t)
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
