package com.glooboo.umessage.ui.thread

import android.content.Context
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import android.provider.Telephony.Sms.STATUS_NONE
import android.text.format.DateUtils
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.createTemporaryThread
import com.glooboo.umessage.extensions.deleteScheduledMessage
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.messaging.cancelScheduleSendPendingIntent
import com.glooboo.umessage.messaging.deleteScheduledAttachments
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.messaging.deleteScheduledRepeat
import com.glooboo.umessage.messaging.keepScheduledAttachments
import com.glooboo.umessage.messaging.saveScheduledRepeat
import com.glooboo.umessage.messaging.scheduleMessage
import org.fossify.commons.extensions.formatDate
import java.time.Instant
import java.time.ZoneId

/** A scheduled time must be at least this far ahead when the message is saved. */
internal const val MIN_SCHEDULE_LEAD_MILLIS = 1000L

/** Whether [millis] is still far enough ahead of [nowMillis] to be scheduled. */
fun isSchedulableTime(millis: Long, nowMillis: Long): Boolean = millis >= nowMillis + MIN_SCHEDULE_LEAD_MILLIS

/** Whether [millis] falls in a later year than [nowMillis], so the schedule banner must show the year. */
fun isInLaterYear(millis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    Instant.ofEpochMilli(millis).atZone(zone).year > Instant.ofEpochMilli(nowMillis).atZone(zone).year

/** The composer's schedule banner text: date and time, with the year only when it is not this year. */
fun scheduleLabel(
    context: Context,
    millis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    jalali: Boolean = false,
): String =
    if (jalali) {
        // TH-24: Jalali date, then the time of day in the user's clock style.
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val persian = java.util.Locale.getDefault().language == "fa"
        val day = JalaliDates.format(
            date,
            withYear = JalaliDates.fromGregorian(date).year != JalaliDates.fromGregorian(today).year,
            persian = persian,
        )
        val time = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        day + context.getString(R.string.ui_separator) + time
    } else if (isInLaterYear(millis, nowMillis)) {
        millis.formatDate(context)
    } else {
        DateUtils.formatDateTime(
            context, millis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
        )
    }

/**
 * The scheduled message as it is saved: for every [recipients] address, at [millis], with the composer's
 * [attachments]. A thread without messages yet gets a temporary thread id (the message id) until it is sent.
 */
fun buildScheduledMessage(
    text: String,
    recipients: ThreadRecipients,
    subscriptionId: Int,
    messageId: Long,
    millis: Long,
    threadId: Long,
    threadHasMessages: Boolean,
    isMms: Boolean,
    attachments: List<Attachment>,
): Message = Message(
    id = messageId,
    body = text,
    type = MESSAGE_TYPE_QUEUED,
    status = STATUS_NONE,
    participants = recipients.toArrayList(),
    date = (millis / 1000).toInt(),
    read = false,
    threadId = if (threadHasMessages) threadId else messageId,
    isMMS = isMms,
    attachment = MessageAttachment(messageId, text, ArrayList(attachments.map { it.copy(messageId = messageId) })),
    senderPhoneNumber = "",
    senderName = "",
    senderPhotoUri = "",
    subscriptionId = subscriptionId,
    isScheduled = true,
)

/** Saves, re-saves and cancels scheduled messages: their private attachment copies, the alarm and the database. */
class ThreadScheduleController(private val context: Context) {

    /**
     * Saves [picked] and sets its alarm. The picker's uris may be unreadable by the time it is sent, so the saved
     * message points at private copies. A new thread ([startsTemporaryThread]) gets a temporary conversation until a
     * real message is sent. [repeat] is how often it comes back (B04). Returns the message as saved. Runs in the
     * background; throws when saving fails.
     */
    fun save(
        picked: Message,
        startsTemporaryThread: Boolean,
        cachedConversation: Conversation?,
        repeat: RepeatRule = RepeatRule.NONE,
    ): Message {
        val message = context.keepScheduledAttachments(picked)
        if (startsTemporaryThread) {
            context.createTemporaryThread(message, message.threadId, cachedConversation)
        }
        context.conversationsDB.getConversationWithThreadId(message.threadId)?.let { conversation ->
            val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
            context.conversationsDB.insertOrUpdate(conversation.copy(date = nowSeconds, snippet = message.body))
        }
        context.saveScheduledRepeat(message, repeat)
        context.scheduleMessage(message)
        return message
    }

    /**
     * Deletes the scheduled message [messageId] and its alarm, and with it its repeat: no later copy is booked.
     * [keepAttachmentFiles] when the composer still shows its attachments (the user left schedule mode while editing
     * it); those copies go on the next app start. Runs in the background.
     */
    fun cancel(messageId: Long, keepAttachmentFiles: Boolean = false) {
        context.deleteScheduledMessage(messageId)
        context.cancelScheduleSendPendingIntent(messageId)
        context.deleteScheduledRepeat(messageId)
        if (!keepAttachmentFiles) context.deleteScheduledAttachments(messageId)
    }
}
