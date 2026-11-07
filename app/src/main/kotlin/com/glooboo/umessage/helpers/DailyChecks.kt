package com.glooboo.umessage.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.glooboo.umessage.receivers.DailyChecksReceiver
import com.glooboo.umessage.spam.data.DigestNotifier

/**
 * The small jobs that run about once a day without the user: moving old messages to the recycle bin (I02, I45) and
 * the weekly summary of what was blocked (I08), and clearing old exports and photo copies from the cache (S8). They
 * run from one inexact daily alarm, when the app starts and after a received message, because some phones drop alarms
 * of apps in the background. Each job keeps its own "last run", so calling this often costs a few preference reads.
 * Blocking: call it on a background thread.
 */
object DailyChecks {
    private const val REQUEST_CODE = 0xDA11

    fun runIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        runCatching { RetentionRunner(app).runIfDue(now) }
        runCatching { DigestNotifier.postIfDue(app, now) }
        runCatching { CacheCleaner.runIfDue(app, now) }
    }

    /**
     * Books the daily alarm. Without [force] it only does so when none is booked (app start, so opening the app
     * does not push the alarm back every time); [force] books it again (after a reboot, when alarms are gone).
     */
    fun arm(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!force && pendingIntent(app, PendingIntent.FLAG_NO_CREATE) != null) return
        val pendingIntent = pendingIntent(app, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY,
                pendingIntent,
            )
        }
    }

    private fun pendingIntent(context: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DailyChecksReceiver::class.java),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )
}
