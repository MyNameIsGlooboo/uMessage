package com.glooboo.umessage.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import androidx.annotation.VisibleForTesting
import androidx.core.app.AlarmManagerCompat
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteScheduledMessage
import com.glooboo.umessage.extensions.getAddresses
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.notificationHelper
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.R
import com.glooboo.umessage.messaging.bookNextRepeat
import com.glooboo.umessage.messaging.canReadScheduledAttachments
import com.glooboo.umessage.messaging.deleteScheduledRepeat
import com.glooboo.umessage.messaging.deleteScheduledAttachments
import com.glooboo.umessage.messaging.sendMessageCompat
import com.glooboo.umessage.models.Message
import kotlin.time.Duration.Companion.minutes

/** How a late send is counted: more than this after the scheduled time. Inexact alarms are often a few minutes off. */
internal const val SCHEDULED_LATE_AFTER_MILLIS = 5 * 60_000L

/** Whole minutes [nowMillis] is past [scheduledMillis], or null when it is not late enough to mention. */
internal fun lateMinutes(scheduledMillis: Long, nowMillis: Long): Long? {
    val late = nowMillis - scheduledMillis
    return if (late > SCHEDULED_LATE_AFTER_MILLIS) late / 60_000L else null
}

class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakelock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "simple.messenger:scheduled.message.receiver"
        )
        wakelock.acquire(1.minutes.inWholeMilliseconds)

        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                handleIntent(context, intent)
            } finally {
                try {
                    if (wakelock.isHeld) wakelock.release()
                } catch (_: Exception) {
                }

                pendingResult.finish()
            }
        }
    }

    private fun notifyIfLate(context: Context, message: Message, addresses: List<String>) {
        lateMinutes(message.millis(), System.currentTimeMillis())?.let { late ->
            notifySentLate(context, message, addresses, late)
        }
    }

    @VisibleForTesting
    internal fun handleIntent(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(SCHEDULED_MESSAGE_ID, 0L)
        // Looked up by id alone: alarms set by older builds also carry a thread id, which is stale once the message
        // moved to a temporary thread. An alarm for a message that was deleted, or one already held after a failed
        // send, does nothing: a held message goes out only on the user's Retry.
        val message = try {
            context.messagesDB.getAllScheduledMessages().firstOrNull { it.id == messageId }
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        if (message == null || message.isHeldScheduled()) return

        val addresses = message.participants.getAddresses()
        val attachments = message.attachment?.attachments ?: emptyList()

        var notified = false
        fun notifyOnce() {
            if (!notified) {
                notified = true
                notifyNotSent(context, message, addresses)
            }
        }

        if (!context.canReadScheduledAttachments(message)) {
            // Scheduled before the app kept its own copy: the picker's permission expired or the file is gone.
            // Sending it would go out without the photo, so it is held and the user is told why.
            hold(context, messageId)
            notifyNotSent(context, message, addresses, attachmentMissing = true)
            refreshMessages()
            return
        }

        try {
            // Send right here on the worker, inside the goAsync window and wakelock. The scheduled row is the only
            // copy of the message, so it is deleted only once the message is in the telephony provider.
            val handedOff = context.sendMessageCompat(
                message.body, addresses, message.subscriptionId, attachments, onFailure = ::notifyOnce
            )
            if (!handedOff) {
                // The scheduled row stays, so the message is not lost, but held: it goes out only on the user's
                // Retry, never again by itself.
                hold(context, messageId)
                refreshMessages()
                return
            }

            // A failure already has its "not sent" notification; "sent late" is only for messages that went out.
            if (!notified) notifyIfLate(context, message, addresses)

            // A repeating message books its next copy only now that this one went out; the copy gets its own
            // attachment files, so this runs before they are deleted. A message that failed on the radio is kept as
            // failed in its thread, and its repeat pauses with it.
            if (!notified) runCatching { context.bookNextRepeat(message) }
            context.deleteScheduledRepeat(messageId)

            // delete temporary conversation and message as it's already persisted to the telephony db now
            context.deleteScheduledMessage(messageId)
            // The attachments were read into the outgoing message, so the private copies are no longer needed.
            context.deleteScheduledAttachments(messageId)
            context.conversationsDB.deleteThreadId(messageId)
            refreshMessages()
            refreshConversations()
        } catch (e: Exception) {
            hold(context, messageId) // no-op if the row was already handed off and deleted
            notifyOnce()
            context.showErrorToast(e)
        } catch (e: Error) {
            hold(context, messageId)
            notifyOnce()
            context.showErrorToast(
                e.localizedMessage ?: context.getString(org.fossify.commons.R.string.unknown_error_occurred)
            )
        }
    }

    /**
     * Marks a scheduled message that could not be sent as failed. rescheduleAllScheduledMessages skips failed ones,
     * so it is never sent again on app start, boot or a time change, only when the user retries it.
     */
    private fun hold(context: Context, messageId: Long) {
        runCatching { context.messagesDB.updateScheduledType(messageId, Telephony.Sms.MESSAGE_TYPE_FAILED) }
    }

    /**
     * RM-27: a message that went out well after its time (no exact alarm access, or the phone was off or dozing) says
     * so, and why, instead of leaving the user to wonder.
     */
    private fun notifySentLate(context: Context, message: Message, addresses: List<String>, lateMinutes: Long) {
        val recipientName = message.participants.joinToString {
            it.name.ifBlank { it.phoneNumbers.firstOrNull()?.normalizedNumber.orEmpty() }
        }
        val threadId = context.getThreadId(addresses.toSet()).takeIf { it != 0L } ?: message.threadId
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactAllowed = runCatching { AlarmManagerCompat.canScheduleExactAlarms(alarmManager) }.getOrDefault(true)
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    context.notificationHelper.showScheduledSentLateNotification(
                        recipientName, threadId, lateMinutes, exactAllowed
                    )
                }
            }
        }
    }

    /**
     * A scheduled message fires from an alarm, usually while the app is closed, where a toast is never seen. So a
     * failed one always gets a "not sent" notification. It opens the real conversation with [addresses]: a message
     * scheduled in a new conversation lives in a temporary thread that is gone once it was handed off.
     */
    private fun notifyNotSent(
        context: Context, message: Message, addresses: List<String>, attachmentMissing: Boolean = false
    ) {
        val recipientName = message.participants.joinToString {
            it.name.ifBlank { it.phoneNumbers.firstOrNull()?.normalizedNumber.orEmpty() }
        }
        // A held message stays in its own thread (a temporary one for a new conversation), where Retry is.
        val threadId = if (attachmentMissing) {
            message.threadId
        } else {
            context.getThreadId(addresses.toSet()).takeIf { it != 0L } ?: message.threadId
        }
        val reason = if (attachmentMissing) {
            context.getString(R.string.scheduled_attachment_missing, recipientName)
        } else {
            null
        }
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    context.notificationHelper.showSendingFailedNotification(recipientName, threadId, reason)
                }
            }
        }
    }
}
