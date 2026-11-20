package com.glooboo.umessage.spam.data

import android.content.Context
import android.graphics.Bitmap
import android.provider.Telephony
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.getNameFromAddress
import com.glooboo.umessage.extensions.getNotificationBitmap
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.insertNewSMS
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.isStoredLate
import com.glooboo.umessage.extensions.recomputeThreadDate
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.postReceivedMessageNotification
import com.glooboo.umessage.extensions.shouldUnarchive
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.helpers.NotifyResult
import com.glooboo.umessage.helpers.NotifyStage
import com.glooboo.umessage.helpers.notifyFault
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.receivers.PendingSmsRetry

/** The Telephony provider did not store the SMS. Nothing was written; the caller must keep the message and retry. */
class SmsInsertException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Inserts an SMS into the Telephony provider and returns its id. Throws [SmsInsertException] when the provider
 * rejected it (`insertNewSMS` reports every failure as id 0).
 */
fun Context.insertSmsOrThrow(
    address: String,
    subject: String,
    body: String,
    date: Long,
    read: Int,
    threadId: Long,
    type: Int,
    subscriptionId: Int,
    dateSent: Long = 0L,
): Long {
    val id = insertNewSMS(
        address = address,
        subject = subject,
        body = body,
        date = date,
        read = read,
        threadId = threadId,
        type = type,
        subscriptionId = subscriptionId,
        dateSent = dateSent,
    )
    if (id <= 0L) throw SmsInsertException("Telephony provider did not store the SMS")
    return id
}

/**
 * Writes an incoming SMS to the system provider and the conversations DB and refreshes the UI.
 *
 * Only the provider insert can fail this call: it throws [SmsInsertException] (or whatever went wrong before the
 * insert) and then nothing was stored. Once the insert succeeded every later step (cache, conversation, archive
 * state, notification) is best effort, so a caller never has a reason to insert the same SMS a second time.
 */
fun Context.deliverIncomingSms(
    address: String,
    subject: String,
    body: String,
    date: Long,
    subscriptionId: Int,
    status: Int,
    notify: NotifyContext = NotifyContext(),
    threadId: Long = getThreadId(address),
    read: Int = 0,
    type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
    dateSent: Long = 0L,
): Long {
    val context = this
    val photoUri = runCatching { SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address) }.getOrDefault("")
    val bitmap = if (notify.mode == NotifyMode.NONE) {
        null
    } else {
        runCatching { context.getNotificationBitmap(photoUri) }.getOrNull()
    }

    val newMessageId = context.insertSmsOrThrow(
        address = address,
        subject = subject,
        body = body,
        date = date,
        read = read,
        threadId = threadId,
        type = type,
        subscriptionId = subscriptionId,
        dateSent = dateSent,
    )
    // N6: on disk before anything else, so a process that dies before the post leaves a notification to replay.
    if (notify.mode != NotifyMode.NONE) {
        NotifyDue.mark(context, newMessageId, date, notify)
        // R2: a process that dies before the post books nothing itself; this alarm replays the marker then. Once the
        // post has settled, that run finds nothing waiting and books no further one. A queue retry already booked is
        // kept: it replays the marker too, and its attempt count stays.
        runCatching { PendingSmsRetry.scheduleUnlessBooked(context) }
    }

    // R4: should a step below throw after all, the id must not stay in flight: the marker is left for the replay.
    var settled = false
    try {
        // From here on the SMS is safe in the provider. Nothing below may throw.
        // The thread now carries the insert time; date it by its newest message, the time this one really arrived.
        if (isStoredLate(date)) runCatching { context.recomputeThreadDate(threadId) }
        runCatching {
            context.getConversations(threadId).firstOrNull()?.let { conv ->
                // The provider stamps the thread with the insert time. A restored ("Not spam") or replayed SMS is older
                // than that, so keep the thread at its newest real message instead of jumping to the top of the list.
                val messageSeconds = (date / 1000).toInt()
                if (conv.date > messageSeconds) {
                    val cachedDate = context.conversationsDB.getConversationWithThreadId(threadId)?.date ?: 0
                    conv.date = maxOf(cachedDate, messageSeconds)
                }
                context.insertOrUpdateConversation(conv)
            }
        }

        val senderName = context.senderNameFor(address)

        runCatching {
            val participant = SimpleContact(
                rawId = 0,
                contactId = 0,
                name = senderName,
                photoUri = photoUri,
                phoneNumbers = arrayListOf(
                    PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)
                ),
                birthdays = ArrayList(),
                anniversaries = ArrayList()
            )

            val message = Message(
                id = newMessageId,
                body = body,
                type = type,
                status = status,
                participants = arrayListOf(participant),
                date = (date / 1000).toInt(),
                read = read != 0,
                threadId = threadId,
                isMMS = false,
                attachment = null,
                senderPhoneNumber = address,
                senderName = senderName,
                senderPhotoUri = photoUri,
                subscriptionId = subscriptionId
            )
            context.messagesDB.insertOrUpdate(message)
        }

        runCatching {
            if (context.shouldUnarchive()) {
                context.updateConversationArchivedStatus(threadId, false)
            }
        }

        runCatching { refreshMessages() }
        runCatching { refreshConversations(threadId) }
        context.notifyDelivered(newMessageId, address, senderName, body, threadId, bitmap, subscriptionId, notify)
        settled = true
    } finally {
        if (!settled) NotifyDue.settle(context, newMessageId, NotifyResult.FAILED)
    }
    return newMessageId
}

/** The contact name of [address], or [address] itself when there is none or contacts cannot be read. */
internal fun Context.senderNameFor(address: String): String = runCatching {
    getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
        getNameFromAddress(address, it)
    }
}.getOrNull() ?: address

/** Posts the notification of a message that was just stored and settles its "notify due" marker. Never throws. */
private fun Context.notifyDelivered(
    messageId: Long,
    address: String,
    senderName: String,
    body: String,
    threadId: Long,
    bitmap: Bitmap?,
    subscriptionId: Int,
    notify: NotifyContext,
) {
    if (notify.mode == NotifyMode.NONE) return
    NotifyDue.debugHold(this)
    // Tests only (R4): a throw here leaves the receive path the way any unexpected one would.
    notifyFault?.invoke(NotifyStage.DELIVER)
    val result = postReceivedMessageNotification(
        messageId = messageId,
        isMms = false,
        address = address,
        senderName = senderName,
        body = body,
        threadId = threadId,
        bitmap = bitmap,
        subscriptionId = subscriptionId,
        notify = notify,
    )
    NotifyDue.settle(this, messageId, result)
    // Neither form could be posted: try again on the retry alarm, not only on the next SMS or app start.
    if (result == NotifyResult.FAILED) runCatching { PendingSmsRetry.schedule(this) }
}
