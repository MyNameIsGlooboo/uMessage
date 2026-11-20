package com.glooboo.umessage.extensions

import android.content.Context
import com.glooboo.umessage.helpers.MessagingCache
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.messageNotes
import org.fossify.commons.extensions.notificationManager

/**
 * "Delete forever" for chats in the Recycle Bin folder: removes only the messages of those chats that are in the bin
 * (the bin entries and their provider messages). The chat's other messages stay; the chat itself goes only when
 * nothing is left of it. B2-9: a chat whose purge stopped (not the default app) is not tidied. B3-5: returns the
 * first result that is not [DeleteResult.DELETED] (only with ProviderTruthFlag on), so the screen can say why.
 */
fun Context.deleteRecycleBinMessagesOfConversations(threadIds: Iterable<Long>): DeleteResult {
    var first = DeleteResult.DELETED
    for (threadId in threadIds) {
        val result = emptyMessagesRecycleBinForConversation(threadId)
        if (result == DeleteResult.DELETED) {
            tidyConversationAfterBinPurge(threadId)
        } else if (first == DeleteResult.DELETED) {
            first = result
        }
    }
    return first
}

/**
 * Empties the whole bin, then tidies the chats it emptied (see [deleteRecycleBinMessagesOfConversations]). B3-5:
 * returns why it stopped, or [DeleteResult.DELETED].
 */
fun Context.emptyRecycleBinAndTidyConversations(): DeleteResult {
    val threadIds = messagesDB.getAllRecycleBinMessages().mapTo(HashSet()) { it.threadId }
    val result = emptyMessagesRecycleBin()
    if (result != DeleteResult.DELETED) return result
    threadIds.forEach { tidyConversationAfterBinPurge(it) }
    return result
}

/**
 * After bin messages of [threadId] were deleted: a chat that still has messages (in the provider, or live in the
 * cache, scheduled ones included) is refreshed; one with nothing left loses its row, labels, notes and shortcut.
 * B2-9 (provider truth): the store is read with the chats of blocked numbers (they are listed in Spam), and a read
 * that failed leaves the chat as it is: it is not "the thread is gone".
 */
fun Context.tidyConversationAfterBinPurge(threadId: Long) {
    val fromProvider = try {
        if (ProviderTruthFlag.enabled) {
            getConversationsOrNull(threadId, includeBlocked = true) ?: return
        } else {
            getConversations(threadId)
        }
    } catch (_: Exception) {
        return
    }
    if (fromProvider.isNotEmpty()) {
        // Provider truth: only a cached row is refreshed; a chat the list has not cached (a blocked number's, before
        // the first sort) is left to the list sync, which caches it with its sort.
        val cached = !ProviderTruthFlag.enabled || conversationsDB.getConversationWithThreadId(threadId) != null
        if (cached) insertOrUpdateConversation(fromProvider[0])
        return
    }
    if (messagesDB.getNonRecycledThreadMessages(threadId).isNotEmpty()) return

    // S19, B-13 (provider truth): one shared clean-up, the Spam row, draft and reminders included.
    if (ProviderTruthFlag.enabled) {
        forgetThread(threadId)
        return
    }
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
