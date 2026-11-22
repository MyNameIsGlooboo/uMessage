package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glooboo.umessage.helpers.DailyChecks
import com.glooboo.umessage.spam.data.SpamProviderMigration
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * The daily alarm: runs the automatic cleanup and the weekly summary when they are due (see [DailyChecks]), and the
 * message migration of held messages that still wait.
 */
class DailyChecksReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    // Step F (section 8): held messages still waiting are moved, daily, until done.
                    SpamProviderMigration.runPending(context, limit = SpamProviderMigration.RECEIVER_BATCH)
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
