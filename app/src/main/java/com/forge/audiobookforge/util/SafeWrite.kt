package com.forge.audiobookforge.util

import java.io.File

/**
 * Crash-safe small-file writes.
 *
 * [File.writeText] truncates the target and *then* writes: a process death in
 * between (or a full disk) leaves a torn file. The library treats an
 * unparseable `book.json` as "no book", so a torn write silently loses the
 * whole book. Writing to a temp file, forcing it to disk and renaming over the
 * target is atomic on the filesystems Android uses.
 */
object SafeWrite {
    fun text(target: File, text: String) {
        val dir = target.parentFile ?: error("no parent directory for ${target.path}")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() } // best effort: not every FD supports fsync
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
