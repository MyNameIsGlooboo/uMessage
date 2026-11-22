package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.annotation.StringRes
import androidx.sqlite.db.SimpleSQLiteQuery
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause

/* Provider truth, the Spam tab (4.2: U19, U4, D1, "Mark all as read"). */

/**
 * What a choice of Spam chats can do (4.2). Unknown senders: "Not spam" and Block; blocked senders: "Unblock"; a mixed
 * choice only Delete (always offered beside these) and Mark as read.
 */
data class SpamSelection(
    val canNotSpam: Boolean = false,
    val canUnblock: Boolean = false,
    val canBlock: Boolean = false,
    val canMarkRead: Boolean = false,
)

/**
 * One chat of the Spam list: its cached row and its valid Spam row (S9). [received] is its newest received text
 * (body, date in seconds) when that is recent enough to hold a code to copy (C-14); [androidListed]: blocked by
 * Android's own block list only, no rule of the user (C-11).
 */
data class SpamChat(
    val conversation: Conversation,
    val spam: SpamThread,
    val received: Pair<String, Int>? = null,
    val androidListed: Boolean = false,
) {
    val threadId: Long get() = conversation.threadId
    val cause: SpamCause get() = spam.cause

    /** 3.4: uMessage's own count of spam texts not looked at, plus the unread texts the phone's store has. */
    val unread: Int get() = SpamChatsPolicy.unread(spam, conversation)
}

/** The rules of the Spam list, kept apart from the screen so they are easy to test. */
object SpamChatsPolicy {
    /** D1 C: a code on a Spam row can be copied for this long after it arrived, as on a Messages row. */
    const val CODE_MS = 10 * 60 * 1000L

    fun selection(causes: Collection<SpamCause>, anyUnread: Boolean): SpamSelection {
        if (causes.isEmpty()) return SpamSelection()
        val unknown = causes.all { it == SpamCause.UNKNOWN }
        val blocked = causes.all { it == SpamCause.BLOCKED }
        return SpamSelection(canNotSpam = unknown, canUnblock = blocked, canBlock = unknown, canMarkRead = anyUnread)
    }

    /** The one swipe of a row: "Unblock" for a blocked sender, else "Not spam". */
    fun swipeUnblocks(cause: SpamCause): Boolean = cause == SpamCause.BLOCKED

    fun unread(spam: SpamThread, chat: Conversation): Int = spam.unread.coerceAtLeast(0) + storeUnread(chat)

    /** Unread texts the phone's store holds for the chat (written by another app, or by the fail-open path). */
    fun storeUnread(chat: Conversation): Int = if (chat.read) 0 else chat.unreadCount.coerceAtLeast(1)

    /**
     * The Spam tab's number (3.4): chats of unknown senders with unread texts whose newest text came after the tab
     * was last open ([seenAtMs]). Opening the tab clears it; the rows stay bold until opened. A blocked sender never
     * counts.
     */
    fun tabCount(chats: Collection<SpamChat>, seenAtMs: Long): Int = chats.count {
        it.cause == SpamCause.UNKNOWN && it.unread > 0 && it.conversation.date * 1000L > seenAtMs
    }

    /**
     * D1 C: the code of the chat's newest received text ([received]: body, date in seconds), for ten minutes after it
     * arrived, read or not (spam is stored read). This is how a code from a blocked sender is reached. Any code shape
     * counts, also one too weak to allow its sender (owner decision of 20 Nov). A text the user sent never counts
     * (C-14).
     */
    fun code(received: Pair<String, Int>?, nowMs: Long, extract: (String) -> String? = ::extractCode): String? {
        val (body, date) = received ?: return null
        val age = nowMs - date * MS_PER_SECOND
        if (age !in 0..CODE_MS || body.isBlank()) return null
        return extract(body)
    }

    /** When the code of [received] stops showing (ms); null when it shows none. */
    fun codeEndsAt(received: Pair<String, Int>?): Long? = received?.let { it.second * MS_PER_SECOND + CODE_MS }

    /** Only a text this new can hold a code that still shows: older chats need no read of their newest text. */
    fun mayHoldFreshCode(chat: Conversation, nowMs: Long): Boolean = nowMs - chat.date * MS_PER_SECOND <= CODE_MS

    @StringRes
    fun causeRes(cause: SpamCause, androidListed: Boolean = false): Int = when {
        androidListed -> R.string.e_note_system_list
        cause == SpamCause.BLOCKED -> R.string.e_note_blocked_by_you
        else -> R.string.spam_reason_unknown_sender
    }

    private const val MS_PER_SECOND = 1000L

    private fun extractCode(text: String): String? = runCatching { OtpCodeExtractor.extract(text) }.getOrNull()
}

/** Reads the Spam list from the app's conversations.db: every chat with a valid Spam row (S9). Blocking. */
object SpamChatsSource {
    fun load(context: Context): List<SpamChat> = load(context.conversationsDB)

    fun load(dao: ConversationsDao): List<SpamChat> {
        val rows = dao.getValidSpamThreads().associateBy { it.threadId }
        return dao.getSpamList().mapNotNull { chat -> rows[chat.threadId]?.let { SpamChat(chat, it) } }
    }

    /**
     * The newest received text of [threadId] in the app's message cache (body, date in seconds), for "Copy code"
     * (C-14). Null when there is none or it could not be read. Blocking.
     */
    fun newestReceived(db: MessagesDatabase, threadId: Long): Pair<String, Int>? = runCatching {
        val query = SimpleSQLiteQuery(NEWEST_RECEIVED, arrayOf<Any>(threadId))
        db.openHelper.readableDatabase.query(query).use {
            if (it.moveToFirst()) it.getString(0) to it.getInt(1) else null
        }
    }.getOrNull()

    private const val NEWEST_RECEIVED = "SELECT body, date FROM messages WHERE thread_id = ? AND type = 1 " +
        "AND is_scheduled = 0 ORDER BY date DESC, id DESC LIMIT 1"

    /**
     * Marks [chats] read (3.4): uMessage's own count goes to 0, and the store's unread texts are marked read. Returns
     * how many chats had something unread. Blocking.
     */
    fun markRead(context: Context, chats: Collection<SpamChat>): Int =
        markRead(context.conversationsDB, chats) { context.markThreadMessagesRead(it) }

    fun markRead(dao: ConversationsDao, chats: Collection<SpamChat>, markStoreRead: (Long) -> Unit): Int =
        chats.count { chat ->
            val had = chat.unread > 0
            runCatching { dao.clearSpamUnread(chat.threadId) }
            if (SpamChatsPolicy.storeUnread(chat.conversation) > 0) runCatching { markStoreRead(chat.threadId) }
            had
        }

    /**
     * "Mark all as read" (answer 16, C-7): every Spam row's own count goes to 0 in one update, and only the chats with
     * unread texts in the store are written there. Returns how many chats had something unread. Blocking.
     */
    fun markAllRead(context: Context, chats: Collection<SpamChat>): Int =
        markAllRead(context.conversationsDB, chats) { context.markThreadMessagesRead(it) }

    fun markAllRead(dao: ConversationsDao, chats: Collection<SpamChat>, markStoreRead: (Long) -> Unit): Int {
        val had = chats.count { it.unread > 0 }
        runCatching { dao.clearAllSpamUnread() }
        chats.filter { SpamChatsPolicy.storeUnread(it.conversation) > 0 }
            .forEach { runCatching { markStoreRead(it.threadId) } }
        return had
    }
}

/** Where a sender's chat lives now (2.1). Null when the rules or members could not be read (F-3): "not sure". */
fun interface SenderPlace {
    fun verdict(address: String): SenderVerdict?

    companion object {
        /**
         * The app's own judge on the snapshot the sorter shares (R-7). [fresh]: the kept snapshot is dropped first, for
         * a read right after the screen's own write (a rule removed a moment ago must count); else the kept one is
         * used, which the sorter drops whenever rules, members or settings change (C-17). Blocking.
         */
        fun of(context: Context, fresh: Boolean = false): SenderPlace {
            val app = context.applicationContext
            return SenderPlace { address ->
                runCatching {
                    val snapshots = ChatSorter.snapshots(app)
                    if (fresh) snapshots.invalidate()
                    snapshots.get().verdict(address)
                }.getOrNull()
            }
        }
    }
}
