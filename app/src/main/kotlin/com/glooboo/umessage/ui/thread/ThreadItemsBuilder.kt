package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.extensions.toSortedMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.glooboo.umessage.models.ThreadItem.ThreadDateTime
import com.glooboo.umessage.models.ThreadItem.ThreadError
import com.glooboo.umessage.models.ThreadItem.ThreadSending
import com.glooboo.umessage.models.ThreadItem.ThreadSent
import com.glooboo.umessage.models.ThreadItem.ThreadUnreadDivider

/**
 * The chat rows for a list of messages, and the unread messages among them that the user has now seen. [reactions]
 * (I30) holds the iPhone reactions of each message, by [Message.getStableId]; their texts are not in [items].
 */
data class ThreadItems(
    val items: List<ThreadItem>,
    val newlyRead: List<Message>,
    val reactions: Map<Long, List<TapbackReaction>> = emptyMap(),
)

/**
 * Turns the loaded messages into chat rows: a date header when more than [MIN_DATE_TIME_DIFF_SECS] passed or the
 * SIM changed, then the message, its "failed" or "sending" line, and "sent"/"delivered" under the newest one.
 *
 * [simLabels] maps a subscription id to the SIM number shown in the header ("1", "2"); unknown ones show "?".
 * [deliveryReportsOn]: the "delivery reports" setting; without it the newest sent message only says "Sent".
 * [seen] (TU-11): only while the user can see the chat are unread messages returned in [ThreadItems.newlyRead], for
 * the caller to mark read.
 * [firstUnreadId] (I26): the [Message.getStableId] of the message that gets an "Unread messages" line above it, under
 * its date header; see [firstUnreadMessageId].
 * [showTapbacks] (I30): iPhone reaction texts such as `Liked “...”` leave the list and count on the message they name.
 */
fun buildThreadItems(
    messages: List<Message>,
    simLabels: Map<Int, String>,
    seen: Boolean,
    deliveryReportsOn: Boolean = true,
    firstUnreadId: Long? = null,
    showTapbacks: Boolean = false,
): ThreadItems {
    val sorted = messages.toSortedMessages()
    val tapbacks = if (showTapbacks) applyTapbacks(sorted) else TapbackResult.None
    var dividerPending = false
    val items = ArrayList<ThreadItem>()
    val newlyRead = ArrayList<Message>()
    var prevDateTime = 0
    var prevSIMId = -2
    sorted.forEachIndexed { i, message ->
        if (message.getStableId() in tapbacks.hidden) {
            // A hidden reaction is marked read like any message; the unread line moves to the next one shown.
            if (!message.read && seen) newlyRead.add(message)
            if (message.getStableId() == firstUnreadId) dividerPending = true
            return@forEachIndexed
        }
        if (shouldShowThreadDateTime(message, prevDateTime, prevSIMId)) {
            items.add(ThreadDateTime(message.date, simLabels[message.subscriptionId] ?: "?"))
            prevDateTime = message.date
        }
        if (firstUnreadId != null && (dividerPending || message.getStableId() == firstUnreadId)) {
            items.add(ThreadUnreadDivider(firstUnreadId))
            dividerPending = false
        }
        items.add(message)

        if (message.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
            items.add(ThreadError(message.id, message.body, message.isMMS))
        }
        if (message.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX) {
            items.add(ThreadSending(message.id))
        }
        if (!message.read && seen) newlyRead.add(message)

        if (i == sorted.lastIndex && message.type == Telephony.Sms.MESSAGE_TYPE_SENT) {
            // "Delivered" only while delivery reports are on: a network can report delivery the app never asked for.
            val delivered = deliveryReportsOn && message.status == Telephony.Sms.STATUS_COMPLETE
            items.add(ThreadSent(messageId = message.id, delivered = delivered))
        }
        prevSIMId = message.subscriptionId
    }
    return ThreadItems(items, newlyRead, tapbacks.reactions)
}

/** The oldest received message that is still unread, or null: where the "Unread messages" line goes (I26). */
fun firstUnreadMessageId(messages: List<Message>): Long? =
    messages.toSortedMessages().firstOrNull { it.isReceivedMessage() && !it.read && !it.isScheduled }?.getStableId()

private fun shouldShowThreadDateTime(message: Message, prevDateTime: Int, prevSIMId: Int): Boolean {
    val isSentFromDifferentKnownSIM = prevSIMId != -1 && message.subscriptionId != -1 && prevSIMId != message.subscriptionId
    return message.date - prevDateTime > MIN_DATE_TIME_DIFF_SECS || isSentFromDifferentKnownSIM
}

/** A new date header shows only after this many seconds without messages. */
internal const val MIN_DATE_TIME_DIFF_SECS = 300
