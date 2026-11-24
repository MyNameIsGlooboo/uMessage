package com.glooboo.umessage.spam.mms

import android.content.Context
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.engine.SpamEngine

/**
 * 4.6: the opt-in "notify me for texts from unknown senders" notice of an MMS stored in a Spam chat, as the SMS path
 * posts it. Only when the option is on (stored under the old key notify_on_block, read from the repository the
 * verdict was judged with); quiet, one per chat, private on the lock screen. Never throws.
 */
class AppUnknownNotice(context: Context) : UnknownNotice {
    private val app = context.applicationContext

    override fun post(threadId: Long, sender: String, body: String, at: Long) {
        if (!isOn(app)) return
        val shown = BlockedMessage(
            address = sender, body = body, subscriptionId = -1, isMms = true, receivedAt = at,
            reason = DecisionReason.UNKNOWN_SENDER,
        )
        runCatching { BlockedNotifier.show(app, shown, chatId = threadId) }
    }

    override fun cancel(threadId: Long) {
        // One quiet notice per chat is shared by SMS and MMS: the cancel is by chat, and also clears an SMS one.
        runCatching { BlockedNotifier.cancelChat(app, threadId) }
    }

    companion object {
        /**
         * 4.6: "notify me for texts from unknown senders", stored under the old key notify_on_block. Read from the
         * repository the verdict was judged with (the app's one repository; SpamEngine's when none is installed). One
         * place for the SMS and the MMS paths; false when it cannot be read.
         */
        fun isOn(context: Context): Boolean = runCatching {
            val repo = runCatching { UMessage.repository(context) }.getOrNull() as? DefaultSpamRepository
                ?: SpamEngine.repository(context)
            repo.currentSettings().notifyOnBlock
        }.getOrDefault(false)
    }
}
