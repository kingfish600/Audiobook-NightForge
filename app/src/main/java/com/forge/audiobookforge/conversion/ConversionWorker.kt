package com.forge.audiobookforge.conversion

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forge.audiobookforge.ForgeApp
import com.forge.audiobookforge.R
import com.forge.audiobookforge.audio.AacChapterWriter
import com.forge.audiobookforge.data.LibraryRepository
import com.forge.audiobookforge.data.model.Book
import com.forge.audiobookforge.data.model.Chapter
import com.forge.audiobookforge.data.model.ChapterStatus
import com.forge.audiobookforge.tts.KokoroEngine
import com.forge.audiobookforge.util.TextOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a book chapter-by-chapter in the background.
 * Enqueued with setRequiresCharging(true) so heavy synthesis only runs on wall power —
 * that single constraint is what turns "TTS drains my battery" into "free while charging".
 */
class ConversionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val container = (applicationContext as ForgeApp).container
        val repo = container.library
        val engine = container.kokoroEngine
        val settings = container.settings
        val controller = container.conversion

        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val book = repo.book(bookId) ?: return@withContext Result.failure()
        if (book.chapters.all { it.status == ChapterStatus.DONE }) return@withContext Result.success()

        // A fresh run clears any stale stop flag and claims ownership of the UI state.
        val runId = controller.beginRun()
        log("worker started for '${book.title}' (${book.chapters.size} chapters)")

        // React on-screen immediately so a click never looks like a no-op.
        controller.update(
            ConversionState.Running(
                bookId = book.id,
                bookTitle = book.title,
                chapterIndex = -1,
                chapterTitle = "Starting engine…",
                chaptersDone = book.doneCount,
                chaptersTotal = book.chapters.size,
            )
        )

        val modelDir = container.models.ui.value.modelDir
        if (modelDir == null) {
            controller.fail("Kokoro model is not installed.", book.id)
            return@withContext Result.failure()
        }
        val loadError = engine.load(modelDir, settings.numThreads.value, settings.preferInt8.value)
        if (loadError != null) {
            log("engine load FAILED: $loadError")
            controller.fail(loadError, book.id)
            postProgress(applicationContext, book.title, 0f, "Failed: $loadError")
            return@withContext Result.failure()
        }
        log("engine loaded from ${modelDir.name}, sampleRate=${engine.sampleRate()}, speakers=${engine.numSpeakers()}")

        setForeground(createForegroundInfo(book.title, 0f, "Starting…"))
        postProgress(applicationContext, book.title, 0f, "Preparing…")

        var failed = false
        try {
            val sampleRate = engine.sampleRate()
            for (ch in book.chapters) {
                if (controller.cancelRequested) break
                val existing = ch.audioFile?.let { File(repo.audioDir(bookId), it) }
                if (ch.status == ChapterStatus.DONE && existing != null && existing.isFile) continue

                renderChapter(engine, repo, controller, book, ch, sampleRate)
                ch.status = ChapterStatus.DONE
                repo.save(book)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // WorkManager cancelled us: state already saved per chapter, treat as graceful stop
            log("cancelled by user")
        } catch (t: Throwable) {
            failed = true
            log("render failed: ${t.message ?: t.javaClass.simpleName}")
            controller.fail(t.message ?: t.javaClass.simpleName, book.id)
            postProgress(applicationContext, book.title, 0f, "Error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            engine.release()
            controller.endRun(runId)
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        }
        if (failed) Result.failure() else Result.success()
    }

    private suspend fun renderChapter(
        engine: KokoroEngine,
        repo: LibraryRepository,
        controller: ConversionController,
        book: Book,
        ch: Chapter,
        sampleRate: Int,
    ) {
        val audioDir = repo.audioDir(book.id).apply { mkdirs() }
        val outFile = File(audioDir, "%03d.m4a".format(ch.index))
        ch.status = ChapterStatus.RENDERING
        repo.save(book)

        val chunks = TextOps.splitIntoChunks(ch.text, maxLen = CHUNK_MAX_LEN)
        val charsTotal = chunks.sumOf { it.length }.coerceAtLeast(1)
        var charsDone = 0
        val startedAt = System.currentTimeMillis()

        outFile.delete()
        val writer = AacChapterWriter(outFile, sampleRate = sampleRate)
        try {
            for ((n, chunk) in chunks.withIndex()) {
                if (controller.cancelRequested) throw kotlinx.coroutines.CancellationException("stopped")
                val t0 = System.nanoTime()
                val audio = checkNotNull(engine.synthesize(chunk, book.voiceSid, book.speed)) { "Engine not loaded" }
                writer.writePcm(audio.samples)

                val synthSec = (System.nanoTime() - t0) / 1e9
                val audioSec = audio.samples.size.toDouble() / audio.sampleRate
                val rtf = if (audioSec > 0) (synthSec / audioSec).toFloat() else 0f
                charsDone += chunk.length

                controller.update(
                    ConversionState.Running(
                        bookId = book.id,
                        bookTitle = book.title,
                        chapterIndex = ch.index,
                        chapterTitle = ch.title,
                        chaptersDone = book.doneCount,
                        chaptersTotal = book.chapters.size,
                        charsDoneInChapter = charsDone,
                        charsTotalInChapter = charsTotal,
                        lastChunkRtf = rtf,
                    )
                )
                if (n % 4 == 0 || n == chunks.lastIndex) {
                    val etaMin = estimateEtaMinutes(chunks, charsDone, startedAt, book.chapters.size - ch.index)
                    postProgress(
                        applicationContext, book.title,
                        fraction = charsDone.toFloat() / charsTotal,
                        text = "${ch.title} · ${n + 1}/${chunks.size} segments · ETA ~${etaMin}m",
                    )
                }
            }
        } catch (t: Throwable) {
            ch.status = if (controller.cancelRequested) ChapterStatus.PENDING else ChapterStatus.FAILED
            runCatching { writer.close() }
            outFile.delete()
            repo.save(book)
            throw t
        }
        ch.durationMs = writer.durationMs()
        ch.audioFile = outFile.name
        writer.close()
    }

    private fun estimateEtaMinutes(
        chunks: List<String>,
        charsDone: Int,
        startedAtMs: Long,
        chaptersRemainingAfterThis: Int,
    ): Long {
        val elapsedMin = (System.currentTimeMillis() - startedAtMs) / 60_000.0
        val fracDone = charsDone.coerceAtLeast(1).toDouble() / chunks.sumOf { it.length }.coerceAtLeast(1)
        if (elapsedMin < 0.05) return 1
        val thisChapterEta = elapsedMin / fracDone * (1 - fracDone)
        return ((thisChapterEta + chaptersRemainingAfterThis * elapsedMin / fracDone) + 0.5).toLong().coerceIn(1, 99_999)
    }

    private fun createForegroundInfo(title: String, progress: Float, text: String): ForegroundInfo {
        val notification = buildNotification(title, progress, text)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, progress: Float, text: String): Notification {
        val ctx = applicationContext
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(ctx.getString(R.string.notif_channel_conversion))
                .setDescription(ctx.getString(R.string.notif_channel_conversion_desc))
                .build()
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .build()
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val CHANNEL_ID = "conversion"
        const val NOTIFICATION_ID = 42
        const val CHUNK_MAX_LEN = 280

        private fun log(msg: String) = android.util.Log.i("ForgeWorker", msg)

        fun postProgress(context: Context, title: String, fraction: Float, text: String) {
            val workerProgress = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
                .build()
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, workerProgress)
            }
        }

        fun enqueue(context: Context, bookId: String, requireCharging: Boolean) {
            val request = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(requireCharging)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("convert_$bookId", ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, bookId: String, controller: ConversionController) {
            controller.cancelRequested = true
            WorkManager.getInstance(context).cancelUniqueWork("convert_$bookId")
        }
    }
}
