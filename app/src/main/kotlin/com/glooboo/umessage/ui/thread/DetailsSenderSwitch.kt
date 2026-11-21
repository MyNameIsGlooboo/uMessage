package com.glooboo.umessage.ui.thread

import android.content.Context
import androidx.lifecycle.ViewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.ui.conversations.StoreUnread
import com.glooboo.umessage.ui.conversations.UnreadTexts
import com.glooboo.umessage.ui.lock.SpamChatGate
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
    /** Keeps this screen's block across a rotation (D-5): a view model of the screen. */
    private val memory: DetailsBlockMemory = DetailsBlockMemory(),
    private val requestSort: (Long) -> Unit = { SpamSorting.requestThreads(context, listOf(it)) },
    private val markRead: (Long) -> Unit = { context.markThreadMessagesRead(it) },
    /** The chat's message notification and its quiet Spam notice (4.6). */
    private val cancelNotification: (Long) -> Unit = {
        context.notificationManager.cancel(it.hashCode())
        BlockedNotifier.cancelChat(context, it)
    },
    private val unreadOf: (Long) -> UnreadTexts = { StoreUnread.of(context, it) },
    private val restoreUnread: (Long, UnreadTexts) -> Unit = { id, texts -> StoreUnread.restore(context, id, texts) },
) {
    private var receipts: List<BlockReceipt>
        get() = memory.receipts
        set(value) {
            memory.receipts = value
        }
    private var unread: UnreadTexts
        get() = memory.unread
        set(value) {
            memory.unread = value
        }

    /** What switching it on did. */
    enum class Outcome { BLOCKED, STILL_ALLOWED, NOTHING_BLOCKED }

    /**
     * Switched on. [Outcome.BLOCKED]: the chat is blocked now. Otherwise the switch goes back and the screen says why
     * (D-5): a user allow rule still lets the sender in, or nothing could be blocked.
     */
    suspend fun block(address: String, threadId: Long): Outcome {
        val repo = runCatching { repository() }.getOrNull() ?: return Outcome.NOTHING_BLOCKED
        val made = runCatching { SenderMoves.blockSenders(repo, listOf(address)) }.getOrNull().orEmpty()
        if (made.isEmpty()) return Outcome.NOTHING_BLOCKED
        if (made.all { it.stillAllowedBy != null }) {
            SenderMoves.undoBlocks(repo, made)
            return Outcome.STILL_ALLOWED
        }
        receipts = made
        if (threadId > 0) SpamChatGate.Known.note(threadId, spam = true)
        withContext(Dispatchers.IO) {
            unread = if (threadId > 0) unreadOf(threadId) else UnreadTexts()
            if (threadId > 0) {
                runCatching { cancelNotification(threadId) }
                if (!unread.isEmpty) runCatching { markRead(threadId) }
            }
        }
        if (threadId > 0) requestSort(threadId)
        return Outcome.BLOCKED
    }

    /** [block], and when it did not happen [say] gets why (D-5). True when the chat is blocked now. */
    suspend fun blockOrSay(address: String, threadId: Long, say: (Int) -> Unit): Boolean {
        val why = when (block(address, threadId)) {
            Outcome.BLOCKED -> return true
            Outcome.STILL_ALLOWED -> R.string.pt_block_still_allowed
            Outcome.NOTHING_BLOCKED -> R.string.thread_block_failed
        }
        say(why)
        return false
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

/** The details screen's block that its switch can still undo fully, kept across a rotation (D-5). */
internal class DetailsBlockMemory : ViewModel() {
    var receipts: List<BlockReceipt> = emptyList()
    var unread = UnreadTexts()
}
