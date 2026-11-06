package com.glooboo.umessage.ui.thread

import android.content.Context
import com.glooboo.umessage.extensions.createTemporaryThread
import com.glooboo.umessage.extensions.deleteMessage
import com.glooboo.umessage.extensions.moveMessageToRecycleBin
import com.glooboo.umessage.extensions.restoreMessageFromRecycleBin
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.extensions.updateScheduledMessagesThreadId
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message

/**
 * Removes [removed] from the phone and the app's database: a scheduled message loses its alarm and attachment
 * copies; any other goes to the recycle bin ([toRecycleBin]), comes back from it ([fromRecycleBin]) or is deleted.
 *
 * [remaining] are the thread's messages left on screen. When only scheduled ones are left they move to a new
 * temporary thread, whose id is returned; otherwise null. Runs in the background.
 */
fun Context.removeThreadMessages(
    removed: List<Message>,
    toRecycleBin: Boolean,
    fromRecycleBin: Boolean,
    threadId: Long,
    remaining: List<Message>,
    conversation: Conversation?,
    schedule: ThreadScheduleController,
): Long? {
    removed.forEach { message ->
        when {
            message.isScheduled -> schedule.cancel(message.id)
            toRecycleBin -> moveMessageToRecycleBin(message.id, message.isMMS, message)
            fromRecycleBin -> restoreMessageFromRecycleBin(message.id, message.isMMS)
            else -> deleteMessage(message.id, message.isMMS)
        }
    }
    updateLastConversationMessage(threadId)

    if (remaining.isEmpty() || !remaining.all { it.isScheduled }) return null
    val temporaryThreadId = generateRandomId()
    createTemporaryThread(remaining.last(), temporaryThreadId, conversation)
    updateScheduledMessagesThreadId(remaining, temporaryThreadId)
    return temporaryThreadId
}
