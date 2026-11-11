package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.helpers.DailyChecks
import com.glooboo.umessage.messaging.IncomingDateSent
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.IncomingSmsProcessor
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.PendingSmsQueue
import com.glooboo.umessage.spam.engine.SpamEngine

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        try {
            ensureBackgroundThread {
                try {
                    handle(appContext, intent, housekeeping = false)
                } catch (t: Throwable) {
                    // never let an exception kill the process or swallow a message
                    Log.e(TAG, "Incoming SMS handling failed", t)
                } finally {
                    runCatching { pending.finish() }
                }
                // After the broadcast is released: the SMS is stored, this only tidies up (B20).
                runCatching { housekeeping(appContext) }
            }
        } catch (t: Throwable) {
            runCatching { handle(appContext, intent) }
            runCatching { pending.finish() }
        }
    }

    /** Blocking: stores the SMS in [intent] (blocked or inbox), or leaves it queued with a retry booked. */
    internal fun handle(appContext: Context, intent: Intent, housekeeping: Boolean = true) {
        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (t: Throwable) {
            logIssue(appContext, UNKNOWN_SENDER, "UNREADABLE_PDU_" + t.javaClass.simpleName, intent.receivingSubscriptionId())
            Log.e(TAG, "Incoming SMS could not be parsed", t)
            return
        }?.filterNotNull()
        if (parts.isNullOrEmpty()) {
            logIssue(appContext, UNKNOWN_SENDER, "NO_PDU", intent.receivingSubscriptionId())
            return
        }

        val message = toPendingMessage(parts, intent.receivingSubscriptionId(), System.currentTimeMillis())
        if (message.address == UNKNOWN_SENDER) {
            // Some carrier and service messages carry no sender. Keep them (under a placeholder sender) instead of
            // dropping them: they go through the filter and land in the inbox or in Blocked like any other SMS.
            logIssue(appContext, UNKNOWN_SENDER, "NO_SENDER", message.subscriptionId)
        }

        // Write-ahead: the row survives a process kill and is replayed on the next start.
        val pendingId = PendingSmsQueue.add(appContext, message)
        // The carrier's own send time goes into date_sent (I16).
        val dateSent = runCatching { parts.last().timestampMillis }.getOrDefault(0L)
        try {
            IncomingDateSent.during(dateSent) { IncomingSmsProcessor.process(appContext, message) }
            PendingSmsQueue.done(appContext, pendingId)
        } catch (t: Throwable) {
            onSaveFailed(appContext, message, pendingId, t)
        }
        if (housekeeping) housekeeping(appContext)
    }

    /** Replays queued messages and runs the purge and daily checks; not part of storing the SMS (B20). */
    internal fun housekeeping(appContext: Context) {
        PendingSmsQueue.replay(appContext)
        SpamEngine.purgeExpired(appContext)
        DailyChecks.runIfDue(appContext)
    }

    /**
     * The SMS could not be stored anywhere (provider and Blocked both failed). It must not stay invisible until the
     * next SMS: keep it queued and book a retry.
     */
    private fun onSaveFailed(context: Context, message: PendingSmsEntity, pendingId: Long, error: Throwable) {
        Log.e(TAG, "Incoming SMS could not be saved; retrying later", error)
        logIssue(context, message.address, "SAVE_FAILED_" + error.javaClass.simpleName, message.subscriptionId)
        var queuedId = pendingId
        if (queuedId == 0L) {
            // The first queue write failed too. Try once more so the retry has something to replay.
            queuedId = PendingSmsQueue.add(context, message)
        }
        if (queuedId == 0L) {
            // Neither the inbox nor the queue took it: one last direct attempt before giving up.
            val saved = runCatching { IncomingSmsProcessor.process(context, message) }.isSuccess
            if (!saved) {
                Log.e(TAG, "Incoming SMS lost: neither the inbox nor the pending queue could store it")
                logIssue(context, message.address, "LOST", message.subscriptionId)
            }
            return
        }
        PendingSmsQueue.release(queuedId)
        PendingSmsRetry.schedule(context)
    }

    internal companion object {
        private const val TAG = "uMessageReceive"

        /** Sender stored for an SMS that arrived without an originating address. */
        const val UNKNOWN_SENDER = "Unknown"

        /** One SMS from its PDUs. The sender is the first non-blank originating address, else [UNKNOWN_SENDER]. */
        fun toPendingMessage(parts: List<SmsMessage>, subscriptionId: Int, now: Long): PendingSmsEntity {
            val address = parts.firstNotNullOfOrNull { part ->
                runCatching { part.originatingAddress }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            } ?: UNKNOWN_SENDER
            val last = parts.last()
            return PendingSmsEntity(
                address = address,
                subject = runCatching { last.pseudoSubject }.getOrNull().orEmpty(),
                body = buildString { parts.forEach { append(runCatching { it.messageBody }.getOrNull().orEmpty()) } },
                date = now,
                subscriptionId = subscriptionId,
                status = runCatching { last.status }.getOrDefault(0),
                createdAt = now,
                // The carrier's send time; without a usable one (0 or a time in the future) date_sent stays unset.
                dateSent = runCatching { last.timestampMillis }.getOrDefault(0L).takeIf { it in 1..now } ?: 0L,
            )
        }

        private fun logIssue(context: Context, address: String, reason: String, subscriptionId: Int) {
            runCatching {
                UMessage.log(context).record(
                    SpamLogEntry(System.currentTimeMillis(), address, subscriptionId, "ERROR", reason, "SMS_RECEIVE")
                )
            }
        }
    }
}
