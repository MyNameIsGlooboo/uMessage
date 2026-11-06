package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.ui.theme.currentColorScheme

private const val REMINDER_CHANNEL_ID = "umessage_reminders"

/**
 * I51: the notification of a due reminder. Tapping it opens the thread at the message ([target]; the thread alone when
 * the message cannot be found any more). On the lock screen it follows "Hide message content": the text only with
 * "Sender and message", the name with "Sender", nothing but "Reminder" otherwise.
 */
class ReminderNotification(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun show(reminder: MessageReminder, target: MessageKey?) {
        NotificationChannel(
            REMINDER_CHANNEL_ID,
            context.getString(R.string.reminder_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).also { notificationManager.createNotificationChannel(it) }

        // B06: the reminder of a hidden chat names neither the chat nor the message.
        val hidden = HiddenChats.get(context).isMarked(reminder.threadId)
        val name = if (hidden) {
            ""
        } else {
            runCatching { context.conversationsDB.getConversationWithThreadId(reminder.threadId)?.title }
                .getOrNull().orEmpty()
        }
        val lockScreen = context.config.lockScreenVisibilitySetting
        val body = if (hidden) "" else reminder.snapshotBody
        val builder = builder(title(name), body.ifBlank { context.getString(R.string.reminder_open_hint) })
            .setContentIntent(openIntent(reminder, target))
        if (lockScreen == LOCK_SCREEN_SENDER_MESSAGE && !hidden) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            val shownName = name.takeIf { lockScreen == LOCK_SCREEN_SENDER }.orEmpty()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            builder.setPublicVersion(
                builder(title(shownName), context.getString(R.string.reminder_open_hint)).build()
            )
        }
        notificationManager.notify(reminder.id.toInt(), builder.build())
    }

    private fun title(name: String): String =
        if (name.isBlank()) {
            context.getString(R.string.reminder_title)
        } else {
            context.getString(R.string.reminder_title_named, name)
        }

    private fun builder(title: String, text: String) = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setColor(currentColorScheme(context).primary.toArgb())
        .setSmallIcon(R.drawable.ic_messenger)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)

    private fun openIntent(reminder: MessageReminder, target: MessageKey?): PendingIntent {
        val intent = Intent(context, ThreadActivity::class.java).putExtra(THREAD_ID, reminder.threadId)
        if (target != null) {
            intent.putExtra(SEARCHED_MESSAGE_ID, target.id)
            intent.putExtra(SEARCHED_MESSAGE_IS_MMS, target.isMMS)
        }
        return PendingIntent.getActivity(
            context, reminder.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
