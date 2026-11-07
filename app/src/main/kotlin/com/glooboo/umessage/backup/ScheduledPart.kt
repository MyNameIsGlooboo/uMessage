package com.glooboo.umessage.backup

import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import android.provider.Telephony.Sms.STATUS_NONE
import com.glooboo.umessage.extensions.getAddresses
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.interfaces.MessageRemindersDao
import com.glooboo.umessage.messaging.RepeatCalendar
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.messaging.nextOccurrence
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.models.ReminderBackup
import com.glooboo.umessage.models.ScheduledBackup
import com.glooboo.umessage.models.ScheduledRepeat
import com.glooboo.umessage.ui.thread.ThreadRecipients
import com.glooboo.umessage.ui.thread.isSchedulableTime

/**
 * Scheduled text messages and reminders. Only what can still happen is restored: a message or reminder whose time has
 * passed is left out (a repeating message moves to its next time instead), because its alarm would otherwise fire, and
 * send or notify, the moment the restore ends. Held (failed) messages and messages with attachments are not in the
 * backup. Restoring sets alarms only; nothing is sent or shown by the restore itself.
 *
 * [ChatLookup.conversationFor] gives the chat of some people when the phone already lists one; without it the message
 * starts a new chat, as when it was scheduled. [booker] saves a
 * restored message and its repeat (whose message id is a placeholder to replace) and sets its alarm; a message whose
 * `threadId == id` starts a new chat, as one scheduled to new people does.
 */
class ScheduledPart(
    private val scheduled: suspend () -> List<Message>,
    private val repeatOf: suspend (messageId: Long) -> ScheduledRepeat?,
    private val reminders: MessageRemindersDao,
    private val chats: ChatLookup,
    private val booker: suspend (message: Message, repeat: ScheduledRepeat?) -> Unit,
    private val arm: (MessageReminder) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> Long = ::generateRandomId,
) : AppDataPart {

    override suspend fun collect(request: AppDataRequest, base: AppDataBackup): AppDataBackup {
        if (!request.wholePhone) return base
        val time = now()
        val messages = if (request.sms || request.mms) scheduled().mapNotNull { toBackup(it) } else emptyList()
        val due = reminders.getAll().filter { it.remindAt > time }.mapNotNull { reminder ->
            chats.addressesOf(reminder.threadId).sorted().takeIf { it.isNotEmpty() }?.let {
                ReminderBackup(it, reminder.remindAt, reminder.snapshotBody, reminder.isMMS)
            }
        }
        return base.copy(scheduled = messages, reminders = due)
    }

    private suspend fun toBackup(message: Message): ScheduledBackup? {
        val addresses = message.participants.getAddresses().distinct().sorted()
        val textOnly = message.attachment?.attachments.isNullOrEmpty()
        if (message.isHeldScheduled() || !textOnly) return null
        if (addresses.isEmpty() || message.body.isBlank()) return null
        val repeat = repeatOf(message.id)?.takeIf { RepeatRule.fromName(it.rule) != RepeatRule.NONE }
        return ScheduledBackup(
            addresses = addresses,
            body = message.body,
            at = message.millis(),
            repeat = repeat?.rule,
            calendar = repeat?.calendar,
            anchorDay = repeat?.anchorDay ?: 0,
            anchorMonth = repeat?.anchorMonth ?: 0,
        )
    }

    override suspend fun restore(data: AppDataBackup): Int =
        restoreScheduled(data.scheduled) + restoreReminders(data.reminders)

    private suspend fun restoreScheduled(items: List<ScheduledBackup>): Int {
        if (items.isEmpty()) return 0
        val known = scheduled().mapTo(HashSet()) { identity(it.participants.getAddresses(), it.body, it.date.toLong()) }
        var taken = 0
        for (item in items) {
            val people = item.addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val at = dueTime(item)
            val fresh = people.isNotEmpty() && item.body.isNotBlank() && at != null &&
                known.add(identity(people, item.body, at / MILLIS))
            if (!fresh || at == null) continue
            booker(build(people, item.body, at), repeatFor(item))
            taken++
        }
        return taken
    }

    /** When the message goes out: its time when that is still ahead, else the next time of its repeat, else never. */
    private fun dueTime(item: ScheduledBackup): Long? {
        val time = now()
        if (isSchedulableTime(item.at, time)) return item.at
        val rule = RepeatRule.fromName(item.repeat)
        return nextOccurrence(
            item.at, rule, RepeatCalendar.fromName(item.calendar), item.anchorDay, item.anchorMonth, time,
        )
    }

    private fun build(people: List<String>, body: String, at: Long): Message {
        val id = newId()
        val thread = chats.conversationFor(people.toSet()) ?: id
        return Message(
            id = id,
            body = body,
            type = MESSAGE_TYPE_QUEUED,
            status = STATUS_NONE,
            participants = ArrayList(people.map { ThreadRecipients.contactForNumber(it) }),
            date = (at / MILLIS).toInt(),
            read = false,
            threadId = thread,
            isMMS = false,
            attachment = MessageAttachment(id, body, ArrayList()),
            senderPhoneNumber = "",
            senderName = "",
            senderPhotoUri = "",
            subscriptionId = -1,
            isScheduled = true,
        )
    }

    private fun repeatFor(item: ScheduledBackup): ScheduledRepeat? {
        val rule = RepeatRule.fromName(item.repeat)
        if (rule == RepeatRule.NONE || item.anchorDay <= 0) return null
        val calendar = RepeatCalendar.fromName(item.calendar)
        return ScheduledRepeat(0L, rule.name, calendar.name, item.anchorDay, item.anchorMonth)
    }

    private fun identity(addresses: Collection<String>, body: String, seconds: Long) =
        Triple(addresses.map(StarredMessages::normalizedAddress).toSortedSet().toList(), body, seconds)

    private suspend fun restoreReminders(items: List<ReminderBackup>): Int {
        if (items.isEmpty()) return 0
        val known = reminders.getAll().mapTo(HashSet()) { Triple(it.threadId, it.remindAt, it.snapshotBody) }
        var taken = 0
        for (item in items) {
            val threadId = if (item.remindAt <= now() || item.body.isBlank()) null else {
                chats.threadFor(item.addresses.toSet())
            }
            if (threadId == null || !known.add(Triple(threadId, item.remindAt, item.body))) continue
            // The message id is not known on this phone; the reminder finds its message again by thread and text.
            val saved = MessageReminder(
                messageId = 0L, isMMS = item.isMms, threadId = threadId, remindAt = item.remindAt,
                snapshotBody = item.body,
            )
            arm(saved.copy(id = reminders.insert(saved)))
            taken++
        }
        return taken
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
