package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.helpers.ReminderNotification
import com.glooboo.umessage.messaging.REMINDER_ID
import com.glooboo.umessage.messaging.cancelReminder
import com.glooboo.umessage.messaging.resolveReminderTarget
import kotlinx.coroutines.runBlocking
import org.fossify.commons.helpers.ensureBackgroundThread

/** I51: a message reminder is due. It posts one notification that opens the message; nothing is sent. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                handleIntent(context, intent)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    @VisibleForTesting
    internal fun handleIntent(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(REMINDER_ID, 0L)
        val reminder = runCatching {
            runBlocking { context.getMessagesDB().MessageRemindersDao().get(reminderId) }
        }.getOrNull() ?: return
        runCatching { ReminderNotification(context).show(reminder, context.resolveReminderTarget(reminder)) }
        // Deleted once it fired, so re-arming after a reboot never fires it a second time.
        context.cancelReminder(reminderId)
    }
}
