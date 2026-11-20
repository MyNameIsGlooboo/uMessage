package com.glooboo.umessage.ui.thread

import android.content.Context
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.ui.conversations.StoreUnread
import com.glooboo.umessage.ui.conversations.UnreadTexts
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SenderMoves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.notificationManager

/**
 * Provider truth (C-6): the Block switch of the chat's details screen. Switched on it blocks as 4.1 says (the
 * sender's automatic allow rules and memberships go, the chat is marked read, its notification goes, it is sorted
 * again) and keeps the receipt, so switching it off while the screen is open undoes it fully (U5, M-SP3: rules,
 * memberships, unread). Switched off without a receipt of its own it removes the sender's block rules. A block that
 * blocked nothing, or that a user allow rule outranks (C-3), is taken back and reports false: the switch goes back.
 */
internal class DetailsSenderSwitch(
    private val context: Context,
    private val repository: () -> SpamRepository,
    private val requestSort: (Long) -> Unit = { SpamSorting.requestThreads(context, listOf(it)) },
    private val markRead: (Long) -> Unit = { context.markThreadMessagesRead(it) },
    private val cancelNotification: (Long) -> Unit = { context.notificationManager.cancel(it.hashCode()) },
    private val unreadOf: (Long) -> UnreadTexts = { StoreUnread.of(context, it) },
    private val restoreUnread: (Long, UnreadTexts) -> Unit = { id, texts -> StoreUnread.restore(context, id, texts) },
) {
    private var receipts: List<BlockReceipt> = emptyList()
    private var unread = UnreadTexts()

    /** Switched on. True when the chat is blocked now. */
    suspend fun block(address: String, threadId: Long): Boolean {
        val repo = repository()
        val made = SenderMoves.blockSenders(repo, listOf(address))
        if (made.isEmpty() || made.all { it.stillAllowedBy != null }) {
            SenderMoves.undoBlocks(repo, made)
            return false
        }
        receipts = made
        withContext(Dispatchers.IO) {
            unread = if (threadId > 0) unreadOf(threadId) else UnreadTexts()
            if (threadId > 0) {
                runCatching { cancelNotification(threadId) }
                if (!unread.isEmpty) runCatching { markRead(threadId) }
            }
        }
        if (threadId > 0) requestSort(threadId)
        return true
    }

    /** Switched off: the block of this screen is undone fully, or else the sender's block rules go. */
    suspend fun unblock(address: String, threadId: Long) {
        val repo = repository()
        if (receipts.isNotEmpty()) {
            SenderMoves.undoBlocks(repo, receipts)
            val texts = unread
            receipts = emptyList()
            unread = UnreadTexts()
            if (threadId > 0) withContext(Dispatchers.IO) { runCatching { restoreUnread(threadId, texts) } }
        } else {
            SenderBlocking.unblock(repo, address)
        }
        if (threadId > 0) requestSort(threadId)
    }
}
