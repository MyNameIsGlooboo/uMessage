package com.glooboo.umessage.messaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.notificationHelper
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import java.util.concurrent.ConcurrentHashMap

/**
 * F3: a scheduled message is sent by exactly one sender. The alarm, the re-arm on app start, boot or a time change and
 * "Send now" can all reach the same message at once; the one that wins [claim] sends it, the others do nothing.
 *
 * The claim is an atomic update in the database (queued to outbox), and the id is kept in memory while the send
 * runs. A scheduled row found in the outbox state that no running send owns belongs to a process that died
 * mid-send: nobody knows whether it went out, so [ScheduledAction.HOLD] keeps it as failed, with Retry, instead of
 * sending it a second time.
 */
object ScheduledSendClaims {
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * True when the caller now owns the send of scheduled message [id]. Call [release] when it is done. [allowHeld]:
     * the user's "Send now" or Retry may also take a message that is held as failed; the alarm may not.
     */
    fun claim(context: Context, id: Long, allowHeld: Boolean = false): Boolean {
        inFlight.add(id) // before the database claim, so a re-arm in between never mistakes it for a dead send
        var won = false
        try {
            val dao = context.messagesDB
            won = (if (allowHeld) dao.claimScheduledOrHeldForSend(id) else dao.claimScheduledForSend(id)) > 0
        } finally {
            if (!won) inFlight.remove(id) // lost, or the database threw: nobody owns it
        }
        return won
    }

    fun release(id: Long) {
        inFlight.remove(id)
    }

    fun isInFlight(id: Long): Boolean = id in inFlight

    /** Whether any scheduled send runs in this process right now. */
    fun anyInFlight(): Boolean = inFlight.isNotEmpty()
}

/** What to do with a scheduled message found when alarms are set again. */
enum class ScheduledAction { ARM, HOLD, SKIP }

/** [type] is the scheduled row's type; [inFlight] whether a send in this process owns it. */
internal fun scheduledActionFor(type: Int, inFlight: Boolean): ScheduledAction = when {
    type == Sms.MESSAGE_TYPE_FAILED -> ScheduledAction.SKIP // held: goes out only on the user's Retry
    type == Sms.MESSAGE_TYPE_OUTBOX && inFlight -> ScheduledAction.SKIP // being sent right now
    type == Sms.MESSAGE_TYPE_OUTBOX -> ScheduledAction.HOLD // a send that died half way
    else -> ScheduledAction.ARM
}

/**
 * M-P4: [ScheduledAction.HOLD] for [message], a scheduled send that died half way. The row is held as failed, with
 * Retry, and the user gets the "not sent" notification: nobody else will tell them, as the app was not running. It
 * opens the message's own thread, where the held message and Retry are.
 */
fun Context.holdDiedScheduledSend(message: Message) {
    val held = runCatching { messagesDB.updateScheduledType(message.id, Sms.MESSAGE_TYPE_FAILED) }.getOrDefault(0)
    if (held == 0) return
    val recipientName = message.participants.joinToString {
        it.name.ifBlank { it.phoneNumbers.firstOrNull()?.normalizedNumber.orEmpty() }
    }
    runCatching {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                notificationHelper.showSendingFailedNotification(recipientName, message.threadId, automatic = true)
            }
        }
    }
}

/**
 * MS6: a message with several attachments goes out as one MMS per attachment. This is the callback that, as each one is
 * handed off, removes it from the scheduled row of [message]; a later Retry then sends only what did not go out.
 */
fun Context.scheduledAttachmentTracker(message: Message): (Attachment) -> Unit {
    val remaining = ArrayList(message.attachment?.attachments.orEmpty())
    val text = message.attachment?.text.orEmpty()
    return { sent ->
        remaining.remove(sent)
        runCatching { messagesDB.updateScheduledAttachment(message.id, MessageAttachment(message.id, text, remaining)) }
    }
}
