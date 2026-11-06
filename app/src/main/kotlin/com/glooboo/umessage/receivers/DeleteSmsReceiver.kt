package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.deleteMessage
import com.glooboo.umessage.extensions.moveMessageToRecycleBin
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.helpers.IS_MMS
import com.glooboo.umessage.helpers.MESSAGE_ID
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages

class DeleteSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(MESSAGE_ID, 0L)
        val isMms = intent.getBooleanExtra(IS_MMS, false)
        context.notificationManager.cancel(threadId.hashCode())
        val pending = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    // Same rule as deleting in the thread screen: with the recycle bin on, it goes there first.
                    if (context.config.useRecycleBin) {
                        context.moveMessageToRecycleBin(messageId, isMms)
                    } else {
                        context.deleteMessage(messageId, isMms)
                    }
                    context.updateLastConversationMessage(threadId)
                    refreshMessages()
                    refreshConversations()
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }
}
