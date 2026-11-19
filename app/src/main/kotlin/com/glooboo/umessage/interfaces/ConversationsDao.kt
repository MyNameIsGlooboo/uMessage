package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.ConversationWithSnippetOverride
import com.glooboo.umessage.spam.SortableChat
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.engine.SpamCause

private const val CONVERSATION_SEARCH_ORDER = "ORDER BY date DESC LIMIT $SEARCH_RESULTS_LIMIT"

@Dao
interface ConversationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(conversation: Conversation): Long

    /*
     * DL-08: the preview under a chat is the body of its newest cached message that was really sent or received:
     * scheduled messages (not sent yet) never count, and neither do messages in the recycle bin. A recycle-bin entry
     * is matched by id and type together (MessageKey), since SMS 57 and MMS 57 are different messages. Equal dates
     * are broken by id so the choice is stable.
     */

    @Query(
        "SELECT (SELECT body FROM messages WHERE messages.thread_id = conversations.thread_id " +
            "AND messages.is_scheduled = 0 AND NOT EXISTS (SELECT 1 FROM recycle_bin_messages r " +
            "WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
            "ORDER BY messages.date DESC, messages.id DESC LIMIT 1) AS new_snippet, * FROM conversations WHERE archived = 0"
    )
    fun getNonArchivedWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getNonArchived(): List<Conversation> {
        return getNonArchivedWithLatestSnippet().map { it.toConversation() }
    }

    @Query(
        "SELECT (SELECT body FROM messages WHERE messages.thread_id = conversations.thread_id " +
            "AND messages.is_scheduled = 0 AND NOT EXISTS (SELECT 1 FROM recycle_bin_messages r " +
            "WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
            "ORDER BY messages.date DESC, messages.id DESC LIMIT 1) AS new_snippet, * FROM conversations WHERE archived = 1"
    )
    fun getAllArchivedWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getAllArchived(): List<Conversation> {
        return getAllArchivedWithLatestSnippet().map { it.toConversation() }
    }

    /*
     * S1, provider truth: the four chat lists. Every caller picks one. [getNonArchived] and [getAllArchived] above keep
     * their 0.4.14 meaning (every non-archived or archived row, Spam chats included) until their callers move over.
     */

    /** Every cached chat, Spam chats included: for the list sync, the SIM cache and the archive store. */
    @Query(ConversationQueries.ALL_ROWS)
    fun getAllRowsWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getAllRows(): List<Conversation> = getAllRowsWithLatestSnippet().map { it.toConversation() }

    /** The Messages list: not archived, not in Spam. */
    @Query(ConversationQueries.MESSAGES_LIST)
    fun getMessagesListWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getMessagesList(): List<Conversation> = getMessagesListWithLatestSnippet().map { it.toConversation() }

    /** The Archived list: archived, not in Spam. */
    @Query(ConversationQueries.ARCHIVED_LIST)
    fun getArchivedListWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getArchivedList(): List<Conversation> = getArchivedListWithLatestSnippet().map { it.toConversation() }

    /** The Spam list: every chat with a valid spam_threads row (S9), archived or not. */
    @Query(ConversationQueries.SPAM_LIST)
    fun getSpamListWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getSpamList(): List<Conversation> = getSpamListWithLatestSnippet().map { it.toConversation() }

    /** The columns ChatSorter judges, for every cached chat. */
    @Query("SELECT thread_id, phone_number, is_group_conversation, is_scheduled FROM conversations")
    fun getSortableChats(): List<SortableChat>

    /** Every spam_threads row, also those that no longer count (S9). */
    @Query("SELECT * FROM spam_threads")
    fun getSpamThreads(): List<SpamThread>

    /** The spam_threads rows that count: their cached chat still has the same sender and is one-to-one (S9). */
    @Query(ConversationQueries.VALID_SPAM_THREADS)
    fun getValidSpamThreads(): List<SpamThread>

    @Query("SELECT * FROM spam_threads WHERE thread_id = :threadId")
    fun getSpamThread(threadId: Long): SpamThread?

    /** A new Spam row. An existing row is kept as it is, so its unread count and spam_since survive (R12). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSpamThread(row: SpamThread): Long

    /** A Spam chat changes cause (BLOCKED, UNKNOWN); unread and spam_since stay. */
    @Query("UPDATE spam_threads SET cause = :cause WHERE thread_id = :threadId")
    fun updateSpamThreadCause(threadId: Long, cause: SpamCause): Int

    /** The same sender in another written form (S9): the row follows the cached number; unread and spam_since stay. */
    @Query("UPDATE spam_threads SET sender = :sender, cause = :cause WHERE thread_id = :threadId")
    fun updateSpamThreadSender(threadId: Long, sender: String, cause: SpamCause): Int

    /** R12: one spam text arrived in [threadId]. The only write of `unread` besides clearing it. */
    @Query("UPDATE spam_threads SET unread = unread + 1 WHERE thread_id = :threadId")
    fun raiseSpamUnread(threadId: Long): Int

    /**
     * 6.1 (for the incoming path, step D): a text judged Spam arrives in [threadId]. The row is written first (an
     * existing row keeps its cause and spam_since), then its unread count goes up by one, in one transaction (S9, R12).
     */
    @Transaction
    fun noteSpamArrival(threadId: Long, sender: String, cause: SpamCause, at: Long) {
        insertSpamThread(SpamThread(threadId, sender, cause, spamSince = at.coerceAtLeast(1L)))
        raiseSpamUnread(threadId)
    }

    /** Opening the chat, or "Mark all as read" ([clearAllSpamUnread]). */
    @Query("UPDATE spam_threads SET unread = 0 WHERE thread_id = :threadId")
    fun clearSpamUnread(threadId: Long): Int

    @Query("UPDATE spam_threads SET unread = 0 WHERE unread > 0")
    fun clearAllSpamUnread(): Int

    @Query("DELETE FROM spam_threads WHERE thread_id = :threadId")
    fun deleteSpamThread(threadId: Long): Int

    /** Whether [threadId] is shown in Spam now (a valid row, S9). */
    @Query("SELECT EXISTS(" + ConversationQueries.VALID_SPAM_THREADS + " AND s.thread_id = :threadId)")
    fun isInSpam(threadId: Long): Boolean

    /** Conversations with messages in the recycle bin; the preview is the newest of those. */
    @Query(ConversationQueries.IN_RECYCLE_BIN)
    fun getAllWithMessagesInRecycleBinWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getAllWithMessagesInRecycleBin(): List<Conversation> {
        return getAllWithMessagesInRecycleBinWithLatestSnippet().map { it.toConversation() }
    }

    /**
     * MC-19: the chats in the main list whose newest shown saved message (see DL-08 above) is newer than the chat
     * row, with that message's date. One index lookup per chat instead of grouping every saved message.
     */
    @Query(ConversationQueries.OLDER_THAN_MESSAGES)
    fun getNonArchivedOlderThanMessages(): List<ThreadLastDate>

    @Query("SELECT * FROM conversations WHERE thread_id = :threadId")
    fun getConversationWithThreadId(threadId: Long): Conversation?

    /** Every chat row as stored, without the newest-message preview subquery; for matching phone numbers in search. */
    @Query("SELECT * FROM conversations")
    fun getAllForNumberSearch(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE read = 0")
    fun getUnreadConversations(): List<Conversation>

    /** [pattern] is a [TextNormalizer.likeContains] pattern. Newest first, at most [SEARCH_RESULTS_LIMIT]. */
    @Query("SELECT * FROM conversations WHERE title LIKE :pattern ESCAPE '\\' $CONVERSATION_SEARCH_ORDER")
    fun getConversationsWithText(pattern: String): List<Conversation>

    @RawQuery
    fun getConversationsRaw(query: SupportSQLiteQuery): List<Conversation>

    /** Conversations whose title contains [text], read through [TextNormalizer.fold] on both sides ("علي" finds "علی"). */
    fun searchConversations(text: String): List<Conversation> {
        if (!TextNormalizer.needsFold(text)) return getConversationsWithText(TextNormalizer.likeContains(text))
        val sql = TextNormalizer.sqlFoldedLike("conversations", "thread_id", "title") + " $CONVERSATION_SEARCH_ORDER"
        return getConversationsRaw(SimpleSQLiteQuery(sql, arrayOf(TextNormalizer.likeContains(TextNormalizer.fold(text)))))
    }

    @Query("UPDATE conversations SET read = 1, unread_count = 0 WHERE thread_id = :threadId")
    fun markRead(threadId: Long)

    @Query("UPDATE conversations SET read = 0 WHERE thread_id = :threadId")
    fun markUnread(threadId: Long)

    /** Moves a chat up to its newest saved message without rewriting the rest of the row (it may be read by now). */
    @Query("UPDATE conversations SET date = :date, snippet = :snippet WHERE thread_id = :threadId")
    fun updateDateAndSnippet(threadId: Long, date: Int, snippet: String)

    /** Renames a chat without rewriting the rest of the row (its archived flag may have changed since it was read). */
    @Query("UPDATE conversations SET title = :title, uses_custom_title = :usesCustomTitle WHERE thread_id = :threadId")
    fun updateTitle(threadId: Long, title: String, usesCustomTitle: Boolean)

    @Query("UPDATE conversations SET archived = 1 WHERE thread_id = :threadId")
    fun moveToArchive(threadId: Long)

    @Query("UPDATE conversations SET archived = 0 WHERE thread_id = :threadId")
    fun unarchive(threadId: Long)

    @Query("DELETE FROM conversations WHERE thread_id = :threadId")
    fun deleteThreadId(threadId: Long)
}

/** SQL of the queries above that tests also check (query plans). */
object ConversationQueries {
    /** The newest shown message of the chat (DL-08), as `new_snippet`, then every column of the row. */
    private const val WITH_SNIPPET = "SELECT (SELECT body FROM messages WHERE messages.thread_id = " +
        "conversations.thread_id AND messages.is_scheduled = 0 AND NOT EXISTS (SELECT 1 FROM recycle_bin_messages r " +
        "WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
        "ORDER BY messages.date DESC, messages.id DESC LIMIT 1) AS new_snippet, * FROM conversations"

    /** S9: the chat has a spam_threads row for its own sender and is one-to-one. */
    private const val IN_SPAM = "(conversations.is_group_conversation = 0 AND EXISTS (SELECT 1 FROM spam_threads s " +
        "WHERE s.thread_id = conversations.thread_id AND s.sender = conversations.phone_number))"

    const val ALL_ROWS = WITH_SNIPPET
    const val MESSAGES_LIST = "$WITH_SNIPPET WHERE archived = 0 AND NOT $IN_SPAM"
    const val ARCHIVED_LIST = "$WITH_SNIPPET WHERE archived = 1 AND NOT $IN_SPAM"
    const val SPAM_LIST = "$WITH_SNIPPET WHERE $IN_SPAM"

    const val VALID_SPAM_THREADS = "SELECT s.* FROM spam_threads s JOIN conversations c ON c.thread_id = s.thread_id " +
        "WHERE c.is_group_conversation = 0 AND c.phone_number = s.sender"

    private const val SHOWN_M = "m.is_scheduled = 0 AND NOT EXISTS (SELECT 1 FROM recycle_bin_messages r " +
        "WHERE r.id = m.id AND r.is_mms = m.is_mms)"

    const val OLDER_THAN_MESSAGES = "SELECT thread_id, newest AS date FROM (SELECT c.thread_id, c.date AS row_date, " +
        "(SELECT m.date FROM messages m WHERE m.thread_id = c.thread_id AND " + SHOWN_M +
        " ORDER BY m.date DESC LIMIT 1) AS newest FROM conversations c WHERE c.archived = 0) WHERE newest > row_date"

    /**
     * DL-07: the chats are found from the recycle bin, which is small, instead of searching every chat's messages
     * for a binned one.
     */
    const val IN_RECYCLE_BIN = "SELECT (SELECT body FROM messages JOIN recycle_bin_messages r ON r.id = messages.id " +
        "AND r.is_mms = messages.is_mms WHERE messages.thread_id = conversations.thread_id " +
        "ORDER BY messages.date DESC, messages.id DESC LIMIT 1) AS new_snippet, * FROM conversations " +
        "WHERE thread_id IN (SELECT messages.thread_id FROM recycle_bin_messages r JOIN messages " +
        "ON messages.id = r.id AND messages.is_mms = r.is_mms)"
}
