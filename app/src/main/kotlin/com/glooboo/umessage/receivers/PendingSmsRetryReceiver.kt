package com.glooboo.umessage.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.spam.data.PendingSmsQueue

/**
 * Retries incoming SMS whose save failed (they wait in the pending_sms table). Without this a failed SMS stays
 * invisible, with no notification, until the next SMS arrives or the app is started again.
 */
class PendingSmsRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        try {
            ensureBackgroundThread {
                try {
                    PendingSmsRetry.run(appContext, attempt)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { PendingSmsRetry.run(appContext, attempt) }
            runCatching { pending.finish() }
        }
    }

    internal companion object {
        const val EXTRA_ATTEMPT = "attempt"
    }
}

internal object PendingSmsRetry {
    private const val TAG = "uMessageReceive"

    /** First retry after a minute, then doubling: 1, 2, 4, 8, 16, 32 minutes. After that app start and the next SMS replay. */
    const val FIRST_DELAY_MS = 60_000L
    const val MAX_ATTEMPTS = 6

    fun delayFor(attempt: Int): Long = FIRST_DELAY_MS shl attempt.coerceIn(0, MAX_ATTEMPTS - 1)

    /** Replays the queue and books the next attempt while rows are still waiting. Blocking, off the main thread. */
    fun run(context: Context, attempt: Int) {
        val waiting = PendingSmsQueue.replay(context)
        if (waiting != 0) schedule(context, attempt + 1)
    }

    /**
     * Books a replay [delayFor] ([attempt]) from now. An inexact alarm (no exact-alarm permission needed); a newer
     * booking replaces an older one, so there is at most one. Does nothing once [MAX_ATTEMPTS] is reached.
     */
    fun schedule(context: Context, attempt: Int = 0) {
        if (attempt >= MAX_ATTEMPTS) {
            Log.w(TAG, "Pending SMS still not saved after $MAX_ATTEMPTS retries; next try on app start or next SMS")
            return
        }
        try {
            val intent = Intent(context, PendingSmsRetryReceiver::class.java)
                .putExtra(PendingSmsRetryReceiver.EXTRA_ATTEMPT, attempt)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayFor(attempt),
                pendingIntent
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not schedule a retry for a pending SMS", t)
        }
    }
}
