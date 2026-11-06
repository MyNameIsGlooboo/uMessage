package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.ConversationWithSnippetOverride
import com.glooboo.umessage.spam.TextNormalizer

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

    @Query("UPDATE conversations SET archived = 1 WHERE thread_id = :threadId")
    fun moveToArchive(threadId: Long)

    @Query("UPDATE conversations SET archived = 0 WHERE thread_id = :threadId")
    fun unarchive(threadId: Long)

    @Query("DELETE FROM conversations WHERE thread_id = :threadId")
    fun deleteThreadId(threadId: Long)
}

/** SQL of the queries above that tests also check (query plans). */
object ConversationQueries {
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
