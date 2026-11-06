package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glooboo.umessage.helpers.DailyChecks
import org.fossify.commons.helpers.ensureBackgroundThread

/** The daily alarm: runs the automatic cleanup and the weekly summary when they are due (see [DailyChecks]). */
class DailyChecksReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    DailyChecks.runIfDue(context)
                } finally {
                    runCatching { pendingResult.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pendingResult.finish() }
        }
    }
}
