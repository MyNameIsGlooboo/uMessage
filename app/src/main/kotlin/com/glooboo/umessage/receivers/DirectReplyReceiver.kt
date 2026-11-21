package com.glooboo.umessage.receivers

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.SimpleContactsHelper
import com.glooboo.umessage.extensions.*
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.REPLY
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.pickSendSubscriptionId
import com.glooboo.umessage.messaging.notifySendFailedInBackground
import com.glooboo.umessage.messaging.sendMessageDetailed

/**
 * Who a quick reply goes to: everyone in the conversation when it is a group ([threadAddresses] has more than one
 * number), otherwise the number the notification came from.
 */
internal fun replyRecipients(threadAddresses: List<String>, notificationAddress: String): List<String> {
    val group = threadAddresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return if (group.size > 1) group else listOf(notificationAddress)
}

/** The conversation's numbers, from the telephony provider. Empty when they cannot be read. */
private fun threadAddresses(context: Context, threadId: Long): List<String> {
    if (threadId == 0L) return emptyList()
    return runCatching {
        context.getThreadParticipants(threadId, null).mapNotNull { it.phoneNumbers.firstOrNull()?.normalizedNumber }
    }.getOrDefault(emptyList())
}

/** How many of a chat's newest rows are looked at to find the reply that was just sent. */
private const val REPLY_LOOKUP_ROWS = 5

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(THREAD_NUMBER) ?: return
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        var body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return

        body = context.removeDiacriticsIfNeeded(body)

        // Keep the process alive until the reply is in the provider and the notification is updated.
        runInBackground("DirectReplyReceiver") { handleReply(context, address, threadId, body) }
    }

    internal fun handleReply(context: Context, address: String, threadId: Long, body: String) {
        var messageId = 0L
        // A reply from a group conversation's notification goes to the whole group, not only to whoever wrote last.
        val recipients = replyRecipients(threadAddresses(context, threadId), address)
        var failed = false
        // M-P3: false until the reply reached the provider; a reply that never did is kept as the chat's draft.
        var handedOff = false
        try {
            val subscriptionId = getReplySubscriptionId(context, address, threadId)
            handedOff = context.sendMessageDetailed(
                body, recipients, subscriptionId, emptyList(), onFailure = { failed = true },
            ).handedOff
            // The reply just sent, not whatever the other side texted meanwhile (I19).
            val message = context.getMessages(
                threadId = threadId, includeScheduledMessages = false, limit = REPLY_LOOKUP_ROWS
            ).lastOrNull { !it.isReceivedMessage() }
            if (message != null) {
                context.messagesDB.insertOrUpdate(message)
                messageId = message.id

                context.updateLastConversationMessage(threadId)
            }
        } catch (e: Exception) {
            failed = true
            context.showErrorToast(e)
        }
        if (failed) {
            // The user is in the notification shade, not in the app: a toast alone is easily missed.
            runCatching { context.notifySendFailedInBackground(recipients, threadId) }
            if (!handedOff) runCatching { keepAsDraft(context, threadId, body) }
            // M-P3: the chat's notification is posted again as it was, which ends its reply spinner.
            runCatching { repostOriginal(context, threadId) }
        }

        // A failed reply keeps the failure notification only: re-posting the typed text as a received message
        // would hide it, and the chat was not read.
        if (failed) return

        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)
        // Already on a background thread (runInBackground); the notification work does not need the main thread.
        context.notificationHelper.showMessageNotification(
            messageId = messageId,
            isMms = false,
            address = address,
            body = body,
            threadId = threadId,
            bitmap = bitmap,
            sender = null,
            alertOnlyOnce = true,
            // A group keeps its group look after a reply (I19).
            notify = NotifyContext(isGroup = recipients.size > 1),
            offerSmartReplies = false
        )

        context.markThreadMessagesRead(threadId)
        context.conversationsDB.markRead(threadId)
    }

    /** M-P3: the typed text, never saved anywhere else, becomes the chat's draft (after a draft already there). */
    private fun keepAsDraft(context: Context, threadId: Long, body: String) {
        if (threadId == 0L || body.isBlank()) return
        val existing = context.getSmsDraft(threadId)
        if (existing.contains(body)) return
        context.saveSmsDraft(if (existing.isBlank()) body else "$existing\n$body", threadId)
    }

    /**
     * M-P3: the shade shows a spinner on a notification replied to until the app posts it again. The chat's message
     * notification is posted again unchanged, without sound or heads-up; nothing is posted when it is gone.
     */
    private fun repostOriginal(context: Context, threadId: Long) {
        if (threadId == 0L) return
        val manager = context.notificationManager
        val shown = manager.activeNotifications.firstOrNull { it.id == threadId.hashCode() && it.tag == null } ?: return
        val again = Notification.Builder.recoverBuilder(context, shown.notification).setOnlyAlertOnce(true).build()
        manager.notify(shown.id, again)
    }

    /** Same SIM the thread screen would pick; null means the system default SIM. */
    private fun getReplySubscriptionId(context: Context, address: String, threadId: Long): Int? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val activeIds = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            .map { it.subscriptionId }
        if (activeIds.size < 2) return null

        val lastMessage = runCatching {
            context.getMessages(threadId = threadId, includeScheduledMessages = false, limit = 1).lastOrNull()
        }.getOrNull()
        return pickSendSubscriptionId(
            activeSubscriptionIds = activeIds,
            savedForNumber = context.config.getUseSIMIdAtNumberOrNull(address),
            lastReceivedOn = lastMessage?.takeIf { it.isReceivedMessage() }?.subscriptionId,
            appDefault = context.config.defaultSimId,
            systemDefault = SmsManager.getDefaultSmsSubscriptionId(),
        )
    }
}
