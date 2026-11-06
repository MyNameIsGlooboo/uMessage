package com.glooboo.umessage.messaging

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.receivers.ReminderReceiver
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId

/** Intent extra: the id of the [MessageReminder] that is due. */
const val REMINDER_ID = "reminder_id"

/**
 * Request codes of reminder alarms start here, far below the ids of scheduled messages (a billion and up), so the
 * two kinds of alarm can never be mixed up.
 */
private const val REMINDER_REQUEST_BASE = 100_000

private const val SOON_HOURS = 3L
private const val MORNING_HOUR = 9

/** The quick choices of the reminder dialog. */
enum class ReminderChoice { IN_THREE_HOURS, TOMORROW_MORNING }

/** When [choice] is, counted from [nowMillis]: three hours on, or 9:00 tomorrow on the phone's clock. */
fun reminderTime(choice: ReminderChoice, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    return when (choice) {
        ReminderChoice.IN_THREE_HOURS -> now.plusHours(SOON_HOURS).toInstant().toEpochMilli()
        ReminderChoice.TOMORROW_MORNING ->
            now.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }
}

/**
 * The one PendingIntent of reminder [reminderId]. Setting, re-arming and cancelling build it here so they match
 * (same receiver, request code and flags; the id is only an extra).
 */
fun Context.getReminderPendingIntent(reminderId: Long): PendingIntent {
    val intent = Intent(this, ReminderReceiver::class.java).putExtra(REMINDER_ID, reminderId)
    return PendingIntent.getBroadcast(
        this,
        REMINDER_REQUEST_BASE + reminderId.toInt(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Sets the alarm of [reminder]: exact when "Alarms & reminders" is allowed, else an inexact alarm that may be a little
 * late, so the reminder is not dropped. Returns true if the alarm is exact. Never throws on a refused permission.
 */
fun Context.armReminder(reminder: MessageReminder): Boolean {
    val pendingIntent = getReminderPendingIntent(reminder.id)
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (AlarmManagerCompat.canScheduleExactAlarms(alarmManager)) {
        try {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager, AlarmManager.RTC_WAKEUP, reminder.remindAt, pendingIntent
            )
            return true
        } catch (_: SecurityException) {
            // Access revoked between the check and the call: fall through to the inexact alarm.
        }
    }
    AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, reminder.remindAt, pendingIntent)
    return false
}

/** Saves a reminder for [message] at [remindAt] and sets its alarm. Returns the reminder; runs in the background. */
fun Context.addMessageReminder(message: Message, remindAt: Long): Pair<MessageReminder, Boolean> {
    val dao = getMessagesDB().MessageRemindersDao()
    val saved = MessageReminder(
        messageId = message.id,
        isMMS = message.isMMS,
        threadId = message.threadId,
        remindAt = remindAt,
        snapshotBody = message.body,
    )
    val reminder = saved.copy(id = runBlocking { dao.insert(saved) })
    return reminder to armReminder(reminder)
}

/** Removes reminder [reminderId] and its alarm. */
fun Context.cancelReminder(reminderId: Long) {
    val pendingIntent = getReminderPendingIntent(reminderId)
    (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
    pendingIntent.cancel()
    runCatching { runBlocking { getMessagesDB().MessageRemindersDao().delete(reminderId) } }
}

/** A deleted message takes its reminders with it. */
fun Context.cancelRemindersForMessage(key: MessageKey) {
    runCatching {
        val dao = getMessagesDB().MessageRemindersDao()
        runBlocking { dao.getForMessage(key.id, key.isMMS) }.forEach { cancelReminder(it.id) }
    }
}

/**
 * Sets the alarm of every saved reminder again: after boot, a package update, a time change, a change of the
 * "Alarms & reminders" access and on app start. A reminder whose time passed meanwhile fires at once; it is deleted
 * when it fires, so it fires once and never again.
 */
fun Context.rearmReminders() {
    val reminders = runCatching { runBlocking { getMessagesDB().MessageRemindersDao().getAll() } }
        .getOrDefault(emptyList())
    reminders.forEach { runCatching { armReminder(it) } }
}

/**
 * The message [reminder] is about, as it is now: ids change when the messages are imported again, so a message that
 * is gone or reads differently is looked for in its thread by the same text. Null: only the thread can be opened.
 */
fun Context.resolveReminderTarget(reminder: MessageReminder): MessageKey? {
    val messages = getMessagesDB().MessagesDao()
    val wanted = StarredMessages.fingerprint(reminder.snapshotBody)
    val same = { message: Message -> StarredMessages.fingerprint(message.body) == wanted }
    val current = messages.getMessage(reminder.messageId, reminder.isMMS)
    if (current != null && same(current)) return reminder.key
    return messages.getThreadMessages(reminder.threadId).firstOrNull(same)?.key
}
