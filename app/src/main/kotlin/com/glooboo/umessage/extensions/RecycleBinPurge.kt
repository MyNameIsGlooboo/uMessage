package com.glooboo.umessage.extensions

import android.content.Context
import com.glooboo.umessage.helpers.MessagingCache
import com.glooboo.umessage.helpers.messageNotes
import org.fossify.commons.extensions.notificationManager

/**
 * "Delete forever" for chats in the Recycle Bin folder: removes only the messages of those chats that are in the bin
 * (the bin entries and their provider messages). The chat's other messages stay; the chat itself goes only when
 * nothing is left of it.
 */
fun Context.deleteRecycleBinMessagesOfConversations(threadIds: Iterable<Long>) {
    for (threadId in threadIds) {
        emptyMessagesRecycleBinForConversation(threadId)
        tidyConversationAfterBinPurge(threadId)
    }
}

/** Empties the whole bin, then tidies the chats it emptied (see [deleteRecycleBinMessagesOfConversations]). */
fun Context.emptyRecycleBinAndTidyConversations() {
    val threadIds = messagesDB.getAllRecycleBinMessages().mapTo(HashSet()) { it.threadId }
    emptyMessagesRecycleBin()
    threadIds.forEach { tidyConversationAfterBinPurge(it) }
}

/**
 * After bin messages of [threadId] were deleted: a chat that still has messages (in the provider, or live in the
 * cache, scheduled ones included) is refreshed; one with nothing left loses its row, labels, notes and shortcut.
 */
fun Context.tidyConversationAfterBinPurge(threadId: Long) {
    val fromProvider = try {
        getConversations(threadId)
    } catch (_: Exception) {
        return
    }
    if (fromProvider.isNotEmpty()) {
        insertOrUpdateConversation(fromProvider[0])
        return
    }
    if (messagesDB.getNonRecycledThreadMessages(threadId).isNotEmpty()) return

    conversationsDB.deleteThreadId(threadId)
    kotlinx.coroutines.runBlocking { labelsDB.removeAllFromConversation(threadId) }
    kotlinx.coroutines.runBlocking { messageNotes.onThreadDeleted(threadId) }
    MessagingCache.participantsCache.remove(threadId)
    if (config.customNotifications.contains(threadId.toString())) {
        config.removeCustomNotificationsByThreadId(threadId)
        notificationManager.deleteNotificationChannel(threadId.toString())
    }
    if (shortcutHelper.getShortcut(threadId) != null) {
        shortcutHelper.removeShortcutForThread(threadId)
    }
}
