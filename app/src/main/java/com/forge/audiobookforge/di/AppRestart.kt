package com.forge.audiobookforge.di

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock

/**
 * Kills and relaunches the app process. Needed because the native TTS engine
 * lives exactly once per process (sherpa-onnx constraint) and swiping the app
 * away does NOT kill the process — Android hibernates it in the background
 * with the old engine still loaded. The only way a new engine truly loads is
 * a real process death + relaunch. The choice is persisted by the caller.
 * Hand-rolled ProcessPhoenix pattern — no third-party dependency.
 */
object AppRestart {
    fun restart(context: Context) {
        // Fully guarded: a relaunch that cannot be prepared degrades to a
        // toast + saved choice — never a force close.
        runCatching {
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
            // setAlarmClock() rather than set(): since Android 10 a plain alarm is NOT
            // allowed to start an activity from the background, so the relaunch could
            // silently never happen and the app just "force closed". Alarm-clock
            // intents are exempt from that restriction.
            runCatching {
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 250, pi),
                    pi,
                )
            }.onFailure {
                // Fall back to a plain alarm if the OEM blocks alarm clocks.
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 150, pi)
            }
            android.util.Log.i("AppRestart", "relaunch armed; exiting process")
            // Exit off the main thread, ProcessPhoenix-style.
            Thread { Runtime.getRuntime().exit(0) }.start()
        }.onFailure { e ->
            android.util.Log.e("AppRestart", "auto-restart failed", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    "Couldn't restart automatically — please force-stop NightForge (Settings > Apps) to finish switching.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
