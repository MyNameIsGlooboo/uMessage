package com.glooboo.umessage.spam.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
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

    /** J1: own tag for the summary, so no held message (keyed by its id under [TAG]) can replace it. */
    private const val SUMMARY_TAG = "spamless_blocked_summary"
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
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            // J1-10: a failed read of the shade must never stop the cancel itself.
            val active = runCatching { manager.activeNotifications }.getOrDefault(emptyArray())
            manager.cancel(CHAT_TAG, threadId.hashCode())
            dropLoneSummary(manager, active, setOf(CHAT_TAG to threadId.hashCode()))
        }
    }

    /**
     * I5: one summary for a burst of blocked messages, so 15 spam texts are one stack in the shade, not 15 rows. The
     * summary says nothing about the senders or the texts. It goes with its last notice: the user's swipe takes it
     * along, and [dropLoneSummary] removes it after the app's own cancels.
     */
    private fun postSummary(context: Context, manager: NotificationManager, contentIntent: PendingIntent) {
        val active = manager.activeNotifications
        val count = active.count { Kind.isNotice(it) }
        // J1: posted once. Posting it again for a new count lifts the whole bundle, every older notice with it.
        if (count < SUMMARY_FROM || active.any { Kind.isSummary(it) }) return
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(context.getString(R.string.spam_engine_blocked_channel))
            // J1: posted once, so a count or a time on it would only go stale; the shade counts the notices itself.
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_TAG, SUMMARY_ID, summary)
    }

    /**
     * J1-4: the app's own cancels leave the summary in place. Removes it when no notice is left but the [gone] ones
     * (tag and id), which the system may still list right after their cancel.
     */
    private fun dropLoneSummary(
        manager: NotificationManager,
        active: Array<StatusBarNotification>,
        gone: Set<Pair<String?, Int>>,
    ) {
        if (active.none { Kind.isNotice(it) && (it.tag to it.id) !in gone }) {
            active.filter { Kind.isSummary(it) }.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /** What kind of posted notification this is (kept apart so the notifier stays small). */
    private object Kind {
        /** A held-message or Spam chat notice, not the summary. */
        fun isNotice(n: StatusBarNotification): Boolean = (n.tag == TAG || n.tag == CHAT_TAG) && !isSummary(n)

        /** Ours, or one an older version posted under [TAG] before the update. */
        fun isSummary(n: StatusBarNotification): Boolean = (n.tag == SUMMARY_TAG || n.tag == TAG) &&
            n.id == SUMMARY_ID && n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
    }

    /** I5: removes the notifications of blocked messages that were restored, deleted, or opened in their thread. */
    fun cancel(context: Context, blockedIds: Collection<Long>) {
        if (blockedIds.isEmpty()) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val active = runCatching { manager.activeNotifications }.getOrDefault(emptyArray())
            // J1: a summary of the version before has held message 23313's key; cancelling it would clear the group.
            val keys = blockedIds.map { TAG to notificationId(it) }
                .filterNot { key -> active.any { Kind.isSummary(it) && it.tag == key.first && it.id == key.second } }
            keys.forEach { manager.cancel(it.first, it.second) }
            dropLoneSummary(manager, active, keys.toSet())
        }
    }

    /** The Blocked tab was opened: its notifications (and the held-code notice) have done their job. */
    fun cancelAll(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications.filter { it.tag == TAG || it.tag == CHAT_TAG || it.tag == SUMMARY_TAG }
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
