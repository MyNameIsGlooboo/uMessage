package com.glooboo.umessage.spam.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.CopyCodeActivity
import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.OtpCodes
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.W3aPrefs
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.spam.BlockedMessagesActivity

object BlockedNotifier {
    private const val CHANNEL_ID = "spamless_blocked"

    /** Own tag, so these ids never collide with message notifications (keyed by thread id, no tag). */
    private const val TAG = "spamless_blocked"

    /** Provider truth (4.6): the notices keyed by chat, one per Spam chat; own tag, as thread ids repeat held ids. */
    private const val CHAT_TAG = "spamless_blocked_chat"
    private const val GROUP = "spamless_blocked_group"
    private const val REQUEST_OPEN_BLOCKED = 0x5B10
    private const val SUMMARY_ID = 0x5B11
    private const val COPY_CODE_SALT = 0x5B12

    /** From this many blocked-message notifications on, a summary groups them (I5). */
    private const val SUMMARY_FROM = 2

    /** The text of a held message in its notification: its code as bullets when the user hides codes. */
    internal fun shownBody(body: String, hideCodes: Boolean): String = CodeMasker.maskIf(hideCodes, body)

    /** "Copy code": the invisible activity of the message notification, marked sensitive, never cleared. */
    private fun copyCodeAction(context: Context, code: String, id: Int): NotificationCompat.Action {
        val pending = PendingIntent.getActivity(
            context,
            id xor COPY_CODE_SALT,
            CopyCodeActivity.intent(context, code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            org.fossify.commons.R.drawable.ic_copy_vector,
            context.getString(R.string.onb_notif_copy_code),
            pending,
        )
            .setAuthenticationRequired(true)
            .build()
    }

    /** The notification id of one blocked message; [show] posts it and [cancel] removes it by the same number. */
    internal fun notificationId(blockedId: Long): Int = blockedId.hashCode()

    /**
     * The notice of [message]. Without [chatId] it is keyed by the held message (0.4.14). Provider truth (4.6, the
     * opt-in "notify me for texts from unknown senders"): with [chatId], a text from an unknown sender was stored in
     * that Spam chat; one quiet notice per chat, replaced by the next text of the chat. It opens Spam and is private on
     * the lock screen. Never for a blocked sender: the caller does not ask then.
     */
    fun show(context: Context, message: BlockedMessage, chatId: Long? = null) {
        val tag = if (chatId != null) CHAT_TAG else TAG
        val id = chatId?.hashCode() ?: notificationId(message.id)
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
            // "Hide codes on the lock screen" also hides the code of a held message in this notification.
            val hideCodes = runCatching { W3aPrefs.get(context).hideCodesOnLockScreen }.getOrDefault(false)
            val text = when {
                locked -> context.getString(R.string.new_message)
                message.hasText -> shownBody(message.body, hideCodes)
                else -> context.getString(R.string.spam_engine_mms_blocked)
            }
            // Copy code, like on a message notification; not while the Blocked tab is locked (no text is shown then).
            val code = if (!locked && message.hasText) OtpCodes.primary(message.body)?.value else null

            // Lock screen: no sender, no text.
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(context.getString(R.string.spam_engine_blocked_public_title))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(contentIntent)
                .setGroup(GROUP)
                .setAutoCancel(true)
            if (code != null) builder.addAction(copyCodeAction(context, code, id))
            manager.notify(tag, id, builder.build())
            postSummary(context, manager, contentIntent)
        } catch (_: Exception) {
        }
    }

    /** Removes the notice of Spam chat [threadId] (it was opened, blocked, moved to Messages or deleted). */
    fun cancelChat(context: Context, threadId: Long) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(CHAT_TAG, threadId.hashCode()) }
    }

    /**
     * I5: one summary for a burst of blocked messages, so 15 spam texts are one stack in the shade, not 15 rows. The
     * summary says nothing about the senders or the texts. The system removes it when its last notification goes.
     */
    private fun postSummary(context: Context, manager: NotificationManager, contentIntent: PendingIntent) {
        val count = manager.activeNotifications.count { (it.tag == TAG && it.id != SUMMARY_ID) || it.tag == CHAT_TAG }
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
            manager.activeNotifications.filter { it.tag == TAG || it.tag == CHAT_TAG }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    private fun openBlocked(context: Context): PendingIntent {
        // Provider truth: the main screen's Spam tab (behind the app lock when that covers Spam), not the old list.
        val open = if (ProviderTruthFlag.enabled) {
            MainTabLinks.intent(context, MainTabLinks.TAB_BLOCKED).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, BlockedMessagesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, REQUEST_OPEN_BLOCKED, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
