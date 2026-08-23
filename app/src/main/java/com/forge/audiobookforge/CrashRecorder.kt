package com.forge.audiobookforge

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any uncaught exception to external files dir so the user can share it
 * from Settings → "Share crash report". Installed once in [ForgeApp.onCreate].
 */
object CrashRecorder {

    fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrashFile(context: Context): File? =
        File(context.getExternalFilesDir(null), "last-crash.txt")
            .takeIf { it.isFile }

    private fun write(context: Context, thread: Thread, t: Throwable) {
        val out = File(context.getExternalFilesDir(null), "last-crash.txt")
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        out.writeText(
            buildString {
                appendLine("Audiobook NightForge crash — $stamp")
                appendLine("thread: ${thread.name}")
                appendLine()
                appendLine(sw.toString())
            }
        )
    }
}
