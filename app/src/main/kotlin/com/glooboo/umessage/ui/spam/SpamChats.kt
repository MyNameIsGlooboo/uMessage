package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.annotation.StringRes
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause

/* Provider truth, the Spam tab (4.2: U19, U4, D1, "Mark all as read"). Used only while ProviderTruthFlag is on. */

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

/** One chat of the Spam list: its cached row and its valid Spam row (S9). */
data class SpamChat(val conversation: Conversation, val spam: SpamThread) {
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
     * D1 C: the code of the chat's newest text, for ten minutes after it arrived, read or not (spam is stored read).
     * This is how a code from a blocked sender is reached. Any code shape counts, also one too weak to allow its
     * sender (owner decision of 20 Nov).
     */
    fun code(chat: Conversation, nowMs: Long, extract: (String) -> String? = ::extractCode): String? {
        val age = nowMs - chat.date * 1000L
        if (age !in 0..CODE_MS || chat.snippet.isBlank()) return null
        return extract(chat.snippet)
    }

    @StringRes
    fun causeRes(cause: SpamCause): Int = when (cause) {
        SpamCause.BLOCKED -> R.string.e_note_blocked_by_you
        SpamCause.UNKNOWN -> R.string.spam_reason_unknown_sender
    }

    /** U20: the older spam still kept only in uMessage: held rows the message migration has not moved yet. */
    fun olderSpam(blocked: List<BlockedMessage>): List<BlockedMessage> =
        blocked.filter { it.migratedMsgId == null }.sortedByDescending { it.receivedAt }

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
}

/** Where a sender's chat lives now (2.1). Null when the rules or members could not be read (F-3): "not sure". */
fun interface SenderPlace {
    fun verdict(address: String): SenderVerdict?

    companion object {
        /** The app's own judge, on a fresh snapshot: a rule changed a moment ago must count. Blocking. */
        fun of(context: Context): SenderPlace {
            val app = context.applicationContext
            return SenderPlace { address ->
                runCatching {
                    val snapshots = ChatSorter.snapshots(app)
                    snapshots.invalidate()
                    snapshots.get().verdict(address)
                }.getOrNull()
            }
        }
    }
}
