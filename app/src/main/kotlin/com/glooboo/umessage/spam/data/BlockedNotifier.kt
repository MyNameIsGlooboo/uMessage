package com.glooboo.umessage.spam.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.spam.BlockedMessagesActivity

object BlockedNotifier {
    private const val CHANNEL_ID = "spamless_blocked"

    /** Own tag, so these ids never collide with message notifications (keyed by thread id, no tag). */
    private const val TAG = "spamless_blocked"
    private const val REQUEST_OPEN_BLOCKED = 0x5B10

    fun show(context: Context, message: BlockedMessage) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.spam_engine_blocked_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            val open = Intent(context, BlockedMessagesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(
                context, REQUEST_OPEN_BLOCKED, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // I44: with the Blocked tab locked on its own, the notification does not name the sender or show the text.
            val locked = LockScope.blockedLocked(context)
            val publicTitle = context.getString(R.string.spam_engine_blocked_public_title)
            val title =
                if (locked) publicTitle else context.getString(R.string.spam_engine_blocked_title, message.address)
            val text = when {
                locked -> context.getString(R.string.new_message)
                message.hasText -> message.body
                else -> context.getString(R.string.spam_engine_mms_blocked)
            }

            // Lock screen: no sender, no text.
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(context.getString(R.string.spam_engine_blocked_public_title))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(TAG, message.id.hashCode(), notification)
        } catch (_: Exception) {
        }
    }
}
