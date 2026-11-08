package com.glooboo.umessage.messaging

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.AlarmManagerCompat
import org.fossify.commons.extensions.toast
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.receivers.ScheduledMessageReceiver

/**
 * All things related to scheduled messages are here.
 */

/**
 * The one PendingIntent for [messageId]'s alarm. Scheduling, rescheduling on app start or boot, and cancelling all
 * build it here, so they always match: same receiver, request code and flags, and only the message id as extra
 * (extras do not take part in the match; the thread id is not sent, as it changes when a scheduled message moves
 * to a temporary thread).
 */
fun Context.getScheduleSendPendingIntent(messageId: Long): PendingIntent {
    val intent = Intent(this, ScheduledMessageReceiver::class.java)
    intent.putExtra(SCHEDULED_MESSAGE_ID, messageId)

    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(this, messageId.toInt(), intent, flags)
}

/**
 * Sets the alarm that sends [message]. Uses an exact alarm when allowed; without the "Alarms & reminders" access
 * (off by default on Android 14+) it falls back to an inexact alarm, so the message is still sent, a little late,
 * instead of being dropped. Returns true if the alarm is exact. [warnIfInexact] shows a toast in that case.
 */
fun Context.scheduleMessage(message: Message, warnIfInexact: Boolean = true): Boolean {
    val pendingIntent = getScheduleSendPendingIntent(message.id)
    val triggerAtMillis = message.millis()

    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (AlarmManagerCompat.canScheduleExactAlarms(alarmManager)) {
        try {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
            config.setScheduledMessageInexact(message.id, false)
            return true
        } catch (_: SecurityException) {
            // Access revoked between the check and the call: fall through to the inexact alarm.
        }
    }

    AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    // Recorded so the bubble can say the time is approximate (RM-27); rescheduling with access clears it.
    config.setScheduledMessageInexact(message.id, true)
    if (warnIfInexact) {
        toast(R.string.scheduled_message_inexact, Toast.LENGTH_SHORT)
    }
    return false
}

/**
 * Removes [messageId]'s alarm. The alarm is cancelled through AlarmManager itself: cancelling only the PendingIntent
 * left the alarm registered (seen on an Android 15 emulator), and it later fired for a message that no longer exists.
 */
fun Context.cancelScheduleSendPendingIntent(messageId: Long) {
    val pendingIntent = getScheduleSendPendingIntent(messageId)
    (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
    pendingIntent.cancel()
    config.setScheduledMessageInexact(messageId, false)
}
