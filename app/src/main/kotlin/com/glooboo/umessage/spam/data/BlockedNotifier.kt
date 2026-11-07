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
    private const val HELD_CODE_CHANNEL_ID = "spamless_held_code"

    /** Own tag, so these ids never collide with message notifications (keyed by thread id, no tag). */
    private const val TAG = "spamless_blocked"
    private const val HELD_CODE_TAG = "spamless_held_code"
    private const val GROUP = "spamless_blocked_group"
    private const val REQUEST_OPEN_BLOCKED = 0x5B10
    private const val SUMMARY_ID = 0x5B11
    private const val HELD_CODE_ID = 0x5B12

    /** From this many blocked-message notifications on, a summary groups them (I5). */
    private const val SUMMARY_FROM = 2

    /** The notification id of one blocked message; [show] posts it and [cancel] removes it by the same number. */
    internal fun notificationId(blockedId: Long): Int = blockedId.hashCode()

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
            val contentIntent = openBlocked(context)
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
                .setGroup(GROUP)
                .setAutoCancel(true)
                .build()
            manager.notify(TAG, notificationId(message.id), notification)
            postSummary(context, manager, contentIntent)
        } catch (_: Exception) {
        }
    }

    /**
     * I5: one summary for a burst of blocked messages, so 15 spam texts are one stack in the shade, not 15 rows. The
     * summary says nothing about the senders or the texts. The system removes it when its last notification goes.
     */
    private fun postSummary(context: Context, manager: NotificationManager, contentIntent: PendingIntent) {
        val count = manager.activeNotifications.count { it.tag == TAG && it.id != SUMMARY_ID }
        if (count < SUMMARY_FROM) return
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.spam_engine_blocked_channel))
            .setNumber(count)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()
        manager.notify(TAG, SUMMARY_ID, summary)
    }

    /** I5: removes the notifications of blocked messages that were restored, deleted, or opened in their thread. */
    fun cancel(context: Context, blockedIds: Collection<Long>) {
        if (blockedIds.isEmpty()) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            blockedIds.forEach { manager.cancel(TAG, notificationId(it)) }
        }
    }

    /** The Blocked tab was opened: its notifications (and the held-code notice) have done their job. */
    fun cancelAll(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
            manager.cancel(HELD_CODE_TAG, HELD_CODE_ID)
        }
    }

    /**
     * I11: a quiet notice that a message that looks like a code was held because it carries a link. It names no sender
     * and shows no text; tapping it opens the Blocked tab, where the message can be read and moved to the inbox.
     */
    fun showHeldCode(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    HELD_CODE_CHANNEL_ID,
                    context.getString(R.string.s042_held_code_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            val notification = NotificationCompat.Builder(context, HELD_CODE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(context.getString(R.string.s042_held_code_title))
                .setContentText(context.getString(R.string.s042_held_code_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.s042_held_code_text)))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openBlocked(context))
                .setAutoCancel(true)
                .build()
            manager.notify(HELD_CODE_TAG, HELD_CODE_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun openBlocked(context: Context): PendingIntent {
        val open = Intent(context, BlockedMessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, REQUEST_OPEN_BLOCKED, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
