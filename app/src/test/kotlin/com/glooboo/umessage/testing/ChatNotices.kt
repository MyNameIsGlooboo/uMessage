package com.glooboo.umessage.testing

import android.app.NotificationManager
import android.content.Context
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.data.BlockedNotifier

/**
 * The quiet notice of a Spam chat (4.6), posted as [BlockedNotifier.show] posts it, so a test can see that an action
 * on the chat removes it. The number is made up.
 */
internal object ChatNotices {
    private const val TAG = "spamless_blocked_chat"

    fun post(context: Context, threadId: Long) {
        val message = BlockedMessage(
            id = threadId, address = "+15550001234", body = "Offer", subscriptionId = 1, isMms = false,
            receivedAt = 1L, reason = DecisionReason.UNKNOWN_SENDER,
        )
        BlockedNotifier.show(context, message, chatId = threadId)
        check(isShown(context, threadId)) { "the notice was not posted" }
    }

    fun isShown(context: Context, threadId: Long): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.tag == TAG && it.id == threadId.hashCode() }
}
