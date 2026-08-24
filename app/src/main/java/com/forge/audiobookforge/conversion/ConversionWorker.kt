package com.forge.audiobookforge.conversion

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
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

        // Hold the CPU on through Doze/screen-off — OEM throttling otherwise
        // stretches chunk times by 3-4x and an overnight render stalls.
        val wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NightForge:render")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(MAX_WAKE_MS)

        // Optional plugged-in-night mode: hold the display on so ROM gaming
        // clocks stay engaged (they typically disengage at screen-off even
        // with a CPU wake lock held). Deprecated lock is intentional.
        @Suppress("DEPRECATION")
        val screenLock = if (settings.keepScreenAwake.value) {
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "NightForge:screen")
        } else null
        screenLock?.setReferenceCounted(false)
        screenLock?.acquire(MAX_WAKE_MS)
        if (screenLock != null) log("screen-awake lock held (performance clocks should persist)")

        var failed = false
        try {
            val codec = settings.codec.value
            val useOpus = codec == "opus"
            val engineRate = engine.sampleRate()
            val sampleRate = if (useOpus) com.forge.audiobookforge.audio.AudioOps.opusSafeRate(engineRate) else engineRate
            val outExt = when (codec) {
                "opus" -> "ogg"
                "wav" -> "wav"
                else -> "m4a"
            }
            log("rendering ${if (useOpus) "opus" else "aac"} at ${sampleRate}Hz (engine native ${engineRate}Hz)")
            for (ch in book.chapters) {
                if (controller.cancelRequested) break
                val existing = ch.audioFile?.let { File(repo.audioDir(bookId), it) }
                if (ch.status == ChapterStatus.DONE && existing != null && existing.isFile) continue

                renderChapter(engine, repo, controller, book, ch, sampleRate, settings.segmentChars.value, useOpus, outExt)
                ch.status = ChapterStatus.DONE
                repo.save(book)
                val doneFile = File(repo.audioDir(bookId), "%03d.$outExt".format(ch.index))
                log(
                    "chapter ${ch.index} DONE: '${ch.title}' duration=${ch.durationMs}ms " +
                        "file=${doneFile.name} bytes=${doneFile.length()}"
                )
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // WorkManager cancelled us: state already saved per chapter, treat as graceful stop
            log("cancelled (user stop or system stop)")
        } catch (t: Throwable) {
            failed = true
            log("render failed: ${t.message ?: t.javaClass.simpleName}")
            controller.fail(t.message ?: t.javaClass.simpleName, book.id)
            postProgress(applicationContext, book.title, 0f, "Error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            runCatching { if (screenLock?.isHeld == true) screenLock.release() }
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
        segmentLen: Int,
        useOpus: Boolean = false,
        outExt: String = "m4a",
    ) {
        val audioDir = repo.audioDir(book.id).apply { mkdirs() }
        val outFile = File(audioDir, "%03d.$outExt".format(ch.index))
        ch.status = ChapterStatus.RENDERING
        repo.save(book)

        // Punctuation-only fragments (dialogue dashes, stray quotes) can hang the
        // native phonemizer — never feed them to the engine.
        val allChunks = TextOps.splitIntoChunks(ch.text, maxLen = segmentLen)
        val speakable = allChunks.filter { it.any { c -> c.isLetterOrDigit() } }
        val chunks = speakable.ifEmpty { listOf(allChunks.firstOrNull() ?: "…") }
        val charsTotal = chunks.sumOf { it.length }.coerceAtLeast(1)
        var charsDone = 0
        val startedAt = System.currentTimeMillis()
        val rtfWindow = ArrayDeque<Float>()

        outFile.delete()
        val wavMode = outExt == "wav"
        val wavOut = if (wavMode) com.forge.audiobookforge.audio.Wav.openStream(outFile, sampleRate) else null
        var wavSamples = 0L
        val writer = if (wavMode) null else if (useOpus) {
            AacChapterWriter(
                outFile, sampleRate = sampleRate,
                mimeType = android.media.MediaFormat.MIMETYPE_AUDIO_OPUS,
                muxerFormat = android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
            )
        } else {
            AacChapterWriter(outFile, sampleRate = sampleRate)
        }
        try {
            for ((n, chunk) in chunks.withIndex()) {
                if (controller.cancelRequested) throw kotlinx.coroutines.CancellationException("stopped")
                val t0 = System.nanoTime()
                val audio = checkNotNull(engine.synthesize(chunk, book.voiceSid, book.speed)) { "Engine not loaded" }
                val pcm = if (audio.sampleRate != sampleRate) {
                    com.forge.audiobookforge.audio.AudioOps.resampleLinear(audio.samples, audio.sampleRate, sampleRate)
                } else audio.samples
                if (writer != null) writer.writePcm(pcm) else {
                    com.forge.audiobookforge.audio.Wav.append(wavOut!!, pcm)
                    wavSamples += pcm.size
                }

                val synthSec = (System.nanoTime() - t0) / 1e9
                val audioSec = audio.samples.size.toDouble() / audio.sampleRate
                val rtf = if (audioSec > 0) (synthSec / audioSec).toFloat() else 0f
                charsDone += chunk.length
                rtfWindow.addLast(rtf)
                if (rtfWindow.size > 12) rtfWindow.removeFirst()
                val avgRtf = rtfWindow.average().toFloat()
                log(
                    "chunk ${n + 1}/${chunks.size} seg=${segmentLen} chars=${chunk.length} " +
                        "synth=%.2fs audio=%.1fs rtf=%.2f avg12=%.2f preview=%s".format(
                            synthSec, audioSec, rtf, avgRtf, chunk.take(48).replace("\n", " ")
                        )
                )

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
                        lastChunkRtf = avgRtf,
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
            // User stop OR system stop (constraint lost, app swiped away):
            // leave the chapter pending so resume picks it up cleanly. Only a
            // genuine synthesis error deserves the FAILED badge.
            ch.status = if (controller.cancelRequested || isStopped) {
                ChapterStatus.PENDING
            } else {
                ChapterStatus.FAILED
            }
            runCatching {
                writer?.close() ?: wavOut?.let { com.forge.audiobookforge.audio.Wav.finish(it) }
            }
            outFile.delete()
            repo.save(book)
            throw t
        }
        if (writer != null) {
            ch.durationMs = writer.durationMs()
            ch.audioFile = outFile.name
            writer.close()
        } else {
            com.forge.audiobookforge.audio.Wav.finish(wavOut!!)
            ch.durationMs = wavSamples * 1_000L / sampleRate
            ch.audioFile = outFile.name
        }
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
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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

        /** 12 h ceiling; renders should finish or be stopped long before this. */
        const val MAX_WAKE_MS = 12L * 60 * 60 * 1000

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
