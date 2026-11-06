package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.helpers.MARK_AS_READ
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.refreshConversations

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MARK_AS_READ -> {
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                context.notificationManager.cancel(threadId.hashCode())
                val pending = goAsync()
                try {
                    ensureBackgroundThread {
                        try {
                            context.markThreadMessagesRead(threadId)
                            context.conversationsDB.markRead(threadId)
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
    }
}
