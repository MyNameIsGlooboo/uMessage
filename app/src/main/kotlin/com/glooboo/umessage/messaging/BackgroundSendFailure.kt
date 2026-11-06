package com.glooboo.umessage.messaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fossify.commons.helpers.SimpleContactsHelper
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.notificationHelper

/**
 * Posts the "message not sent" notification for a send that started outside the app (a quick reply from a
 * notification, another app's "respond via message"), where a toast is never seen. [threadId] 0 means the thread of
 * [addresses]. Safe to call from any thread.
 */
fun Context.notifySendFailedInBackground(addresses: List<String>, threadId: Long = 0L) {
    val helper = SimpleContactsHelper(this)
    val recipientName = addresses.joinToString { address ->
        runCatching { helper.getNameFromPhoneNumber(address) }.getOrDefault(address)
    }
    val thread = threadId.takeIf { it != 0L } ?: getThreadId(addresses.toSet())
    Handler(Looper.getMainLooper()).post {
        runCatching { notificationHelper.showSendingFailedNotification(recipientName, thread) }
    }
}
