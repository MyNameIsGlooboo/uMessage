package com.glooboo.umessage.messaging

import android.content.Context
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.createTemporaryThread
import com.glooboo.umessage.extensions.getAddresses
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ScheduledRepeat
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The repeat rules of scheduled messages (B04). All of it runs on a background thread. A repeating message is one
 * scheduled row at a time: when it was handed to the system, [bookNextRepeat] books the next copy as a new row, so
 * a message that failed (and is held) never books another one: the repeat pauses, it can never loop.
 */

/** The repeat of scheduled message [messageId], or null when it does not repeat. */
fun Context.getScheduledRepeat(messageId: Long): ScheduledRepeat? = runBlocking {
    getMessagesDB().ScheduledRepeatsDao().get(messageId)
}?.takeIf { RepeatRule.fromName(it.rule) != RepeatRule.NONE }

/** Remembers that [message] repeats by [rule], counted in the calendar of the user's date style; NONE removes it. */
fun Context.saveScheduledRepeat(message: Message, rule: RepeatRule) {
    val dao = getMessagesDB().ScheduledRepeatsDao()
    runBlocking {
        if (rule == RepeatRule.NONE) {
            dao.delete(message.id)
        } else {
            val calendar = if (w3cPrefs.dateStyle == DateStyle.GREGORIAN) {
                RepeatCalendar.GREGORIAN
            } else {
                RepeatCalendar.JALALI
            }
            dao.insertOrUpdate(newScheduledRepeat(message.id, rule, calendar, message.millis()))
        }
    }
}

/** Forgets the repeat of scheduled message [messageId]. */
fun Context.deleteScheduledRepeat(messageId: Long) {
    runCatching { runBlocking { getMessagesDB().ScheduledRepeatsDao().delete(messageId) } }
}

/**
 * Books the next copy of the repeating scheduled [message]: a new row (new id, same text, people, SIM and its own
 * copies of the attachments) at the next time after [nowMillis], with its alarm. Returns it, or null when [message]
 * does not repeat or the copy failed. The caller deletes [message] itself afterwards; call this before deleting its
 * attachment copies.
 */
fun Context.bookNextRepeat(message: Message, nowMillis: Long = System.currentTimeMillis()): Message? {
    val repeat = getScheduledRepeat(message.id) ?: return null
    val nextMillis = nextOccurrence(
        message.millis(),
        RepeatRule.fromName(repeat.rule),
        RepeatCalendar.fromName(repeat.calendar),
        repeat.anchorDay,
        repeat.anchorMonth,
        nowMillis,
    ) ?: return null
    return saveNextCopy(message, repeat, nextMillis)
}

private fun Context.saveNextCopy(message: Message, repeat: ScheduledRepeat, nextMillis: Long): Message? {
    val newId = generateRandomId()
    // The thread of the people, once it exists: the first message of a new conversation made a temporary one.
    val realThreadId = getThreadId(message.participants.getAddresses().toSet())
    val threadId = if (realThreadId != 0L) realThreadId else newId
    val copy = message.copy(
        id = newId,
        type = MESSAGE_TYPE_QUEUED,
        date = (nextMillis / 1000).toInt(),
        threadId = threadId,
        attachment = message.attachment?.let { attachment ->
            val moved = attachment.attachments.map { it.copy(messageId = newId) }
            attachment.copy(id = newId, attachments = ArrayList(moved))
        },
    )
    val kept = try {
        keepScheduledAttachments(copy)
    } catch (_: IOException) {
        deleteScheduledAttachments(newId)
        return null
    }
    messagesDB.insertOrUpdate(kept)
    runBlocking { getMessagesDB().ScheduledRepeatsDao().insertOrUpdate(repeat.copy(messageId = newId)) }
    if (threadId == newId) {
        // Only the list entry of a new conversation: the message is saved and its alarm set either way.
        runCatching {
            createTemporaryThread(kept, newId, conversationsDB.getConversationWithThreadId(message.threadId))
        }
    }
    scheduleMessage(kept, warnIfInexact = false)
    return kept
}
