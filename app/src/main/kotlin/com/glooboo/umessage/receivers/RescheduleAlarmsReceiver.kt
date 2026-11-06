package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.rescheduleAllScheduledMessages
import com.glooboo.umessage.helpers.DailyChecks
import com.glooboo.umessage.messaging.rearmReminders

/**
 * Reschedules alarms (scheduled messages and message reminders) after boot/package updates/time changes, and when
 * the user grants or revokes "Alarms & reminders" (android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).
 * The system drops exact alarms on revoke, and rescheduling picks exact or inexact alarms to match the new state, so
 * every action is handled the same way.
 */
class RescheduleAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    context.rescheduleAllScheduledMessages()
                    context.rearmReminders()
                    DailyChecks.arm(context, force = true)
                } finally {
                    runCatching { pendingResult.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pendingResult.finish() }
        }
    }
}
