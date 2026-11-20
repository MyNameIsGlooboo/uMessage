package com.glooboo.umessage.ui.conversations

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.sqlite.db.SimpleSQLiteQuery
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.ui.spam.RuleNotes
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SenderMoves
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.notificationManager

/** Provider truth (U5, M-SP3): what one Block or "Report spam" from Messages changed, for its Undo. */
internal data class BlockMove(
    val receipts: List<BlockReceipt>,
    val threadIds: List<Long>,
    /** The unread texts each chat had in the phone's store before the block marked it read. */
    val unread: Map<Long, UnreadTexts>,
)

/** The unread received texts of one chat in the phone's store, by row id, so an Undo makes exactly those unread. */
internal data class UnreadTexts(val sms: List<Long> = emptyList(), val mms: List<Long> = emptyList()) {
    val isEmpty: Boolean get() = sms.isEmpty() && mms.isEmpty()
}

/** Reads and restores [UnreadTexts] (the reviewer's "Undo restores ALL unread"). Blocking. */
internal object StoreUnread {
    private const val CHUNK = 500

    private val smsUnread = "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX} AND ${Sms.READ} = 0"
    private val mmsUnread =
        "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX} AND ${Mms.READ} = 0"

    fun of(context: Context, threadId: Long): UnreadTexts = UnreadTexts(
        sms = ids(context, Sms.CONTENT_URI, smsUnread, threadId),
        mms = ids(context, Mms.CONTENT_URI, mmsUnread, threadId),
    )

    /** Makes [texts] unread again in the store, and the chat unread in the list. */
    fun restore(context: Context, threadId: Long, texts: UnreadTexts) {
        val values = ContentValues().apply {
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
        }
        listOf(Sms.CONTENT_URI to texts.sms, Mms.CONTENT_URI to texts.mms).forEach { (uri, ids) ->
            ids.chunked(CHUNK).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                runCatching { context.contentResolver.update(uri, values, "_id IN ($marks)", args) }
            }
        }
        if (!texts.isEmpty) runCatching { context.conversationsDB.markUnread(threadId) }
    }

    private fun ids(context: Context, uri: Uri, selection: String, threadId: Long): List<Long> = runCatching {
        context.contentResolver.query(uri, arrayOf("_id"), selection, arrayOf(threadId.toString()), null)?.use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

/**
 * Provider truth (4.1): Block and "Report spam" from the Messages list (swipe, selection bar). The block rule is added
 * and the sender's automatic allow rules and group memberships go ([SenderMoves.blockSenders]); the rows hide at once
 * ([hidden]), their notifications go, the chats are marked read and sorted again, so they move to Spam. Undo puts
 * back the rules, the memberships and every unread text (U5, M-SP3). A chat that stays (a user allow rule still names
 * the sender, C-3, or the sender cannot be read) shows again at once and is not counted as blocked. It also keeps the
 * Spam tab's number ([onCount], 3.4). Every call does nothing and returns false while ProviderTruthFlag is off. Main
 * thread, except the work it starts itself.
 */
@Suppress("TooManyFunctions") // Block, Undo, commit and the tab number, each small.
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
        move(conversations, note, token) { id, count, stayed ->
            if (report) {
                ConversationsEvent.ReportedSpam(id, count, archived = false, stayed = stayed)
            } else {
                ConversationsEvent.Blocked(id, count, stayed)
            }
        }
        return true
    }

    /**
     * The Spam tab's number (3.4): chats of unknown senders with unread texts that came after [seenAtMs]; blocked
     * senders and chats hidden behind the app lock never count. False (and nothing starts) while the flag is off.
     */
    fun observeCount(seenAtMs: Long): Boolean {
        if (!ProviderTruthFlag.enabled) return false
        countSeenAt = seenAtMs
        scope.launch { SpamSorting.changes.collect { refreshCount(countSeenAt) } }
        return true
    }

    @Volatile private var countSeenAt = 0L

    /** Counts the reads started, so only the newest one is shown (C-13). Main thread. */
    private var countReads = 0

    /** Reads the number again (a list was published, the tab was seen at [seenAtMs]). */
    fun refreshCount(seenAtMs: Long) {
        if (!ProviderTruthFlag.enabled) return
        countSeenAt = seenAtMs
        val read = ++countReads
        scope.launch {
            val count = withContext(Dispatchers.IO) { spamTabCount(context, seenAtMs) } ?: return@launch
            if (read == countReads) onCount(count)
        }
    }

    /** Blocks the senders of [conversations] with [note]; [event] gets the Undo token and the chats that moved. */
    private fun move(
        conversations: List<Conversation>,
        note: String,
        token: Long,
        event: (Long, Int, Int) -> ConversationsEvent,
    ) {
        val threadIds = conversations.map { it.threadId }
        hide(threadIds)
        scope.launch {
            val unread = withContext(Dispatchers.IO) {
                conversations.filter { !it.read }.associate { it.threadId to StoreUnread.of(context, it.threadId) }
            }
            // D-4: a repository that cannot be had is a failed block, never a crash with the rows hidden.
            val repo = runCatching { repository() }.getOrNull()
            val receipts = repo?.let { r ->
                runCatching { SenderMoves.blockSenders(r, conversations.map { it.phoneNumber }, note) }.getOrNull()
            }
            if (repo == null || receipts == null) {
                show(threadIds)
                events(ConversationsEvent.Failed)
                return@launch
            }
            // C-3: a user allow rule that still names the sender keeps its chat in Messages: that block is taken back.
            val staying = receipts.filter { it.stillAllowedBy != null }
            SenderMoves.undoBlocks(repo, staying)
            val kept = receipts - staying.toSet()
            val moved = conversations.filter { c -> kept.any { it.address == c.phoneNumber.trim() } }
            val movedIds = moved.map { it.threadId }
            show(threadIds - movedIds.toSet())
            if (moved.isEmpty()) {
                SenderMoves.undoBlocks(repo, kept)
                val why = if (staying.isNotEmpty()) R.string.pt_block_still_allowed else null
                events(why?.let { ConversationsEvent.Message(it) } ?: ConversationsEvent.Failed)
                return@launch
            }
            movedIds.forEach { SpamChatGate.Known.note(it, spam = true) }
            withContext(Dispatchers.IO) {
                movedIds.forEach { id ->
                    runCatching { context.notificationManager.cancel(id.hashCode()) }
                    if (id in unread) runCatching { context.markThreadMessagesRead(id) }
                }
            }
            requestSort(movedIds)
            moves[token] = BlockMove(kept, movedIds, unread.filterKeys { it in movedIds })
            events(event(token, moved.size, threadIds.size - moved.size))
        }
    }

    /** Undo: false when [token] is not one of these moves. */
    fun undo(token: Long): Boolean {
        val move = moves.remove(token) ?: return false
        scope.launch {
            runCatching { SenderMoves.undoBlocks(repository(), move.receipts) }
            withContext(Dispatchers.IO) {
                move.unread.forEach { (id, texts) -> StoreUnread.restore(context, id, texts) }
            }
            requestSort(move.threadIds)
            show(move.threadIds)
            withContext(Dispatchers.IO) { refreshConversations() }
        }
        return true
    }

    /**
     * The Undo window closed, or its snackbar went away unanswered (C-2). The sort has moved the chats by now; a chat
     * that stays in Messages shows again, so no chat is ever in neither list.
     */
    fun commit(token: Long): Boolean {
        val move = moves.remove(token) ?: return false
        show(move.threadIds)
        return true
    }

    /** D-4: closes every open window (the list's screen starts again, no snackbar is on show). Returns their tokens. */
    fun closeAll(): Set<Long> = moves.keys.toSet().onEach { commit(it) }
}

/** The Undo token of a Block or "Report spam" event; null for any other event. */
internal fun ConversationsEvent.moveToken(): Long? = when (this) {
    is ConversationsEvent.Blocked -> token
    is ConversationsEvent.ReportedSpam -> token
    else -> null
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

/**
 * Provider truth (3.4, C-7, C-13): the Spam tab's number with one COUNT query: valid Spam rows (S9) of unknown
 * senders with unread texts (uMessage's own count or the store's) whose newest text came after [seenAtMs]; chats
 * hidden behind the app lock are left out, as in the list. Null when it could not be read. Blocking.
 */
internal fun spamTabCount(context: Context, seenAtMs: Long): Int? = runCatching {
    SpamTabCount.count(MessagesDatabase.getInstance(context), seenAtMs, HiddenChats.get(context).hiddenIds())
}.getOrNull()

/** The SQL of [spamTabCount], kept apart so a test runs it on its own database. */
internal object SpamTabCount {
    private const val BASE = "SELECT COUNT(*) FROM spam_threads s JOIN conversations c ON c.thread_id = s.thread_id " +
        "WHERE c.is_group_conversation = 0 AND c.phone_number = s.sender AND s.cause = 'UNKNOWN' " +
        "AND (s.unread > 0 OR c.read = 0) AND c.date > ?"

    fun count(db: MessagesDatabase, seenAtMs: Long, hidden: Set<Long>): Int {
        val notHidden = if (hidden.isEmpty()) "" else " AND c.thread_id NOT IN (${hidden.joinToString(",")})"
        val query = SimpleSQLiteQuery(BASE + notHidden, arrayOf<Any>(seenAtMs / MS_PER_SECOND))
        return db.openHelper.readableDatabase.query(query).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private const val MS_PER_SECOND = 1000L
}

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
