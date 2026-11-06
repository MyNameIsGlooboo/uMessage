package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.ui.theme.currentColorScheme
import org.fossify.commons.extensions.notificationManager

/**
 * B06: what a new text in a hidden chat shows: "New message" under the app name, with no sender, no text, no picture
 * and no actions (no Reply, Copy code or Mark as read). It is the same on the lock screen and in the shade, and it
 * does not carry the chat's shortcut. Tapping it opens the chat, which asks for the app lock first.
 */
class HiddenChatNotification(
    private val context: Context,
    /** Creates the normal message channel (id, name) when it is not there yet. */
    private val ensureChannel: (String, String) -> Unit,
) {
    /** Posts the notification for [threadId]; [muted] sends it to the quiet channel. */
    fun post(threadId: Long, muted: Boolean, alertOnlyOnce: Boolean) {
        val channelId = if (muted) {
            MutedChannel.ensure(context)
        } else {
            ensureChannel(NOTIFICATION_CHANNEL_ID, context.getString(R.string.channel_received_sms))
            NOTIFICATION_CHANNEL_ID
        }
        val id = threadId.hashCode()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, ThreadActivity::class.java).putExtra(THREAD_ID, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_messenger)
            .setColor(currentColorScheme(context).primary.toArgb())
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.new_message))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(channelId))
            .setPriority(if (muted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(alertOnlyOnce)
        if (!muted) {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(sound, AudioManager.STREAM_NOTIFICATION)
        }
        context.notificationManager.notify(id, builder.build())
    }

    private fun publicVersion(channelId: String): Notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_messenger)
            .setColor(currentColorScheme(context).primary.toArgb())
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.new_message))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
}
