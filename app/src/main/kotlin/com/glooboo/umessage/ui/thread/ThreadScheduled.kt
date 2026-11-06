package com.glooboo.umessage.ui.thread

import android.content.Context
import android.util.Log
import android.telephony.PhoneNumberUtils
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.createTemporaryThread
import com.glooboo.umessage.extensions.getAddresses
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.updateScheduledMessagesThreadId
import com.glooboo.umessage.models.Message

/**
 * A message scheduled for someone with no conversation yet waits in a temporary thread of its own, so opening the
 * real conversation with that person (from a link, a contact or the "not sent" notification) showed "No messages
 * yet". This moves such messages for exactly [addresses] into the real thread [threadId] and removes the temporary
 * threads. Returns the messages moved, empty when there were none.
 */
fun Context.adoptScheduledMessages(threadId: Long, addresses: List<String>): List<Message> {
    if (threadId <= 0L || addresses.isEmpty()) return emptyList()
    return try {
        // Only into the phone's own thread for these people: a temporary one is itself waiting to be adopted.
        if (getThreadId(addresses.toSet()) != threadId) return emptyList()
        val target = conversationsDB.getConversationWithThreadId(threadId)

        val temporaryThreads = HashMap<Long, Boolean>()
        fun isTemporary(id: Long) = temporaryThreads.getOrPut(id) {
            conversationsDB.getConversationWithThreadId(id)?.isScheduled == true
        }

        val waiting = messagesDB.getAllScheduledMessages().filter {
            it.threadId != threadId && sameAddresses(it.participants.getAddresses(), addresses) && isTemporary(it.threadId)
        }
        if (waiting.isEmpty()) return emptyList()

        updateScheduledMessagesThreadId(waiting, threadId)
        val temporary = waiting.map { it.threadId }.distinct()
        if (target == null) {
            // Keep the conversation in the list until its first real message arrives.
            createTemporaryThread(waiting.maxBy { it.date }, threadId, conversationsDB.getConversationWithThreadId(temporary.first()))
        }
        temporary.forEach { conversationsDB.deleteThreadId(it) }
        waiting.map { it.copy(threadId = threadId) }
    } catch (e: Exception) {
        Log.w("ThreadScheduled", "Could not move scheduled messages into the thread", e)
        emptyList()
    }
}

/** Whether two address lists name the same people, whatever the number format. */
internal fun sameAddresses(first: List<String>, second: List<String>): Boolean {
    val a = first.distinct()
    val b = second.distinct()
    if (a.size != b.size) return false
    return a.all { x -> b.any { y -> x == y || PhoneNumberUtils.compare(x, y) } }
}
