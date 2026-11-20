package com.glooboo.umessage.ui.conversations

import android.content.Context
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.extensions.markThreadMessagesUnread
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.ui.spam.RuleNotes
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SenderMoves
import com.glooboo.umessage.ui.spam.SpamChatsPolicy
import com.glooboo.umessage.ui.spam.SpamChatsSource
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.notificationManager

/** Provider truth (U5, M-SP3): what one Block or "Report spam" from Messages changed, for its Undo. */
internal data class BlockMove(val receipts: List<BlockReceipt>, val threadIds: List<Long>, val unread: List<Long>)

/**
 * Provider truth (4.1): Block and "Report spam" from the Messages list (swipe, selection bar). The block rule is added
 * and the sender's automatic allow rules and group memberships go ([SenderMoves.blockSenders]); the rows hide at once
 * ([rows] with true), their notifications go, the chats are marked read and sorted again, so they move to Spam. Undo
 * puts back the rules, the memberships and the unread state (U5, M-SP3). It also keeps the Spam tab's number
 * ([onCount], 3.4). Every call does nothing and returns false while ProviderTruthFlag is off. Main thread, except the
 * work it starts itself.
 */
internal class MessagesSpamMoves(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The list's hidden thread ids; [rebuild] shows the list again after a change. */
    private val hidden: MutableSet<Long>,
    private val rebuild: () -> Unit,
    private val events: (ConversationsEvent) -> Unit,
    private val repository: () -> SpamRepository = { SpamRepositoryProvider.get(context) },
    private val requestSort: (Collection<Long>) -> Unit = { SpamSorting.requestThreads(context, it) },
    private val onCount: (Int) -> Unit = {},
) {
    private val moves = HashMap<Long, BlockMove>()

    private fun hide(ids: Collection<Long>) {
        hidden += ids
        rebuild()
    }

    private fun show(ids: Collection<Long>) {
        hidden -= ids.toSet()
        rebuild()
    }

    /** Block ([report] false) or "Report spam" (true, which equals Block, 4.1) on [conversations], Undo by [token]. */
    fun block(conversations: List<Conversation>, token: Long, report: Boolean): Boolean {
        if (!ProviderTruthFlag.enabled) return false
        val note = if (report) RuleNotes.REPORTED_SPAM else SenderBlocking.NOTE
        move(conversations, note, token) {
            if (report) {
                ConversationsEvent.ReportedSpam(it, conversations.size, archived = false)
            } else {
                ConversationsEvent.Blocked(it, conversations.size)
            }
        }
        return true
    }

    /**
     * The Spam tab's number (3.4): chats of unknown senders with unread texts that came after [seenAtMs]; blocked
     * senders never count. False (and nothing starts) while the flag is off.
     */
    fun observeCount(seenAtMs: Long): Boolean {
        if (!ProviderTruthFlag.enabled) return false
        countSeenAt = seenAtMs
        scope.launch { SpamSorting.changes.collect { refreshCount(countSeenAt) } }
        return true
    }

    @Volatile private var countSeenAt = 0L

    /** Reads the number again (a list was published, the tab was seen at [seenAtMs]). */
    fun refreshCount(seenAtMs: Long) {
        if (!ProviderTruthFlag.enabled) return
        countSeenAt = seenAtMs
        scope.launch {
            val count = withContext(Dispatchers.IO) { spamTabCount(context, seenAtMs) } ?: return@launch
            onCount(count)
        }
    }

    /** Blocks the senders of [conversations] with [note]; [event] gets the Undo token. */
    private fun move(
        conversations: List<Conversation>,
        note: String,
        token: Long,
        event: (Long) -> ConversationsEvent,
    ) {
        val threadIds = conversations.map { it.threadId }
        val unread = conversations.filter { !it.read }.map { it.threadId }
        hide(threadIds)
        scope.launch {
            val receipts = runCatching {
                SenderMoves.blockSenders(repository(), conversations.map { it.phoneNumber }, note)
            }.getOrElse {
                show(threadIds)
                events(ConversationsEvent.Failed)
                return@launch
            }
            withContext(Dispatchers.IO) {
                threadIds.forEach { runCatching { context.notificationManager.cancel(it.hashCode()) } }
                unread.forEach { runCatching { context.markThreadMessagesRead(it) } }
            }
            requestSort(threadIds)
            moves[token] = BlockMove(receipts, threadIds, unread)
            events(event(token))
        }
    }

    /** Undo: false when [token] is not one of these moves. */
    fun undo(token: Long): Boolean {
        val move = moves.remove(token) ?: return false
        scope.launch {
            runCatching { SenderMoves.undoBlocks(repository(), move.receipts) }
            withContext(Dispatchers.IO) {
                move.unread.forEach { runCatching { context.markThreadMessagesUnread(it) } }
            }
            requestSort(move.threadIds)
            show(move.threadIds)
            withContext(Dispatchers.IO) { refreshConversations() }
        }
        return true
    }

    /**
     * The Undo window closed. The sort has moved the chats by now; a chat that stays in Messages (a prefix the user
     * allowed) shows again, so no chat is ever in neither list.
     */
    fun commit(token: Long): Boolean {
        val move = moves.remove(token) ?: return false
        show(move.threadIds)
        return true
    }
}

/**
 * The ids of every saved chat, so a deleted chat's SIM entry can be dropped: with ProviderTruthFlag on every cached
 * row, Spam chats too (S1); else the non-archived and archived rows, as in 0.4.14. Blocking.
 */
internal fun liveChatIds(context: Context): Set<Long> {
    val db = context.conversationsDB
    return if (ProviderTruthFlag.enabled) {
        db.getAllRows().mapTo(HashSet()) { it.threadId }
    } else {
        (db.getNonArchived() + db.getAllArchived()).mapTo(HashSet()) { it.threadId }
    }
}

/** Provider truth (3.4): the Spam tab's number, from the Spam list; null when it could not be read. Blocking. */
internal fun spamTabCount(context: Context, seenAtMs: Long): Int? =
    runCatching { SpamChatsPolicy.tabCount(SpamChatsSource.load(context), seenAtMs) }.getOrNull()

/**
 * Deletes the chats [ids] from the phone's store. Returns the ids that went, and with ProviderTruthFlag on the message
 * of the first one that did not (S14: not the default app, nothing deleted). The role is checked once. Blocking.
 */
internal fun deleteChats(context: Context, ids: List<Long>): Pair<Set<Long>, Int?> {
    val honest = ProviderTruthFlag.enabled
    val isDefault = if (honest) {
        runCatching { DefaultSmsRole.status(context) == DefaultSmsRole.Status.Held }.getOrDefault(false)
    } else {
        null
    }
    val results = ids.associateWith {
        runCatching {
            context.deleteConversation(it, isDefault).also { result ->
                if (!honest || result == DeleteResult.DELETED) context.notificationManager.cancel(it.hashCode())
            }
        }.getOrNull()
    }
    val deleted = results.filterValues { it != null && (!honest || it == DeleteResult.DELETED) }.keys
    val message = results.values.firstNotNullOfOrNull { if (honest) it?.messageRes else null }
    return deleted to message
}
