package com.forge.audiobookforge.di

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock

/**
 * Restarts the app process. Used when switching TTS engines: the engine
 * choice is persisted BEFORE calling this, and the fresh process loads it.
 * Hand-rolled ProcessPhoenix pattern — no third-party dependency.
 */
object AppRestart {
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pi = PendingIntent.getActivity(
            context, 1001, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // set() (inexact) needs no SCHEDULE_EXACT_ALARM permission — Android 14+
        // denies that by default and setExact would throw, leaving the app dead
        // with no relaunch. An inexact alarm fires within moments; that is all
        // the 100 ms bounce needs.
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 100, pi)
        Runtime.getRuntime().exit(0)
    }
}
