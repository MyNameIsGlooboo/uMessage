package com.glooboo.umessage.receivers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.*
import com.glooboo.umessage.helpers.REPLY
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.pickSendSubscriptionId
import com.glooboo.umessage.messaging.notifySendFailedInBackground
import com.glooboo.umessage.messaging.sendMessageCompat

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

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(THREAD_NUMBER) ?: return
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        var body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return

        body = context.removeDiacriticsIfNeeded(body)

        // Keep the process alive until the reply is in the provider and the notification is updated.
        val pending = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    handleReply(context, address, threadId, body)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }

    internal fun handleReply(context: Context, address: String, threadId: Long, body: String) {
        var messageId = 0L
        // A reply from a group conversation's notification goes to the whole group, not only to whoever wrote last.
        val recipients = replyRecipients(threadAddresses(context, threadId), address)
        var failed = false
        try {
            val subscriptionId = getReplySubscriptionId(context, address, threadId)
            context.sendMessageCompat(body, recipients, subscriptionId, emptyList(), onFailure = { failed = true })
            val message = context.getMessages(
                threadId = threadId, includeScheduledMessages = false, limit = 1
            ).lastOrNull()
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
        }

        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)
        Handler(Looper.getMainLooper()).post {
            context.notificationHelper.showMessageNotification(
                messageId = messageId,
                isMms = false,
                address = address,
                body = body,
                threadId = threadId,
                bitmap = bitmap,
                sender = null,
                alertOnlyOnce = true,
                offerSmartReplies = false
            )
        }

        context.markThreadMessagesRead(threadId)
        context.conversationsDB.markRead(threadId)
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
