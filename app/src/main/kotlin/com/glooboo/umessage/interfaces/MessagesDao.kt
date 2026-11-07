@file:Suppress("MaxLineLength")
package com.glooboo.umessage.interfaces

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.spam.TextNormalizer

/** Search shows at most this many messages (and this many conversations), newest first (MC-17). */
const val SEARCH_RESULTS_LIMIT = 100

private const val SEARCH_ORDER = "ORDER BY date DESC, id DESC LIMIT $SEARCH_RESULTS_LIMIT"

/* I28, I56: the shortcut queries only prefilter in SQL; the caller keeps the rows that really hold a picture or link. */
private const val SHORTCUT_BASE = "SELECT * FROM messages WHERE is_scheduled = 0 AND NOT EXISTS " +
    "(SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms)"
private const val IMAGE_PREFILTER = "attachment LIKE '%image/%'"
private const val LINK_PREFILTER = "(body LIKE '%http%' OR body LIKE '%www.%')"
private const val SHORTCUT_ORDER = "ORDER BY date DESC, id DESC LIMIT :limit"

/** Newest non-scheduled message time per thread, in seconds. */
data class ThreadLastDate(
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "date") val date: Int,
)

/**
 * Rows in `messages` and `recycle_bin_messages` are keyed by (id, is_mms), see [com.glooboo.umessage.models.MessageKey]:
 * every query that targets one message takes both. The `*Scheduled*` helpers are the exception: scheduled ids are
 * unique among scheduled rows, and those helpers only ever match `is_scheduled = 1`.
 */
@Dao
interface MessagesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRecycleBinEntry(recycleBinMessage: RecycleBinMessage)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(vararg message: Message)

    /** Caches [messages] in one transaction, keeping any row already cached under the same (id, is_mms). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnoreAll(messages: List<Message>)

    @Query("SELECT * FROM messages")
    fun getAll(): List<Message>

    @Query("SELECT * FROM messages WHERE id = :id AND is_mms = :isMMS")
    fun getMessage(id: Long, isMMS: Boolean): Message?

    @Query("SELECT * FROM recycle_bin_messages")
    fun getAllRecycleBinEntries(): List<RecycleBinMessage>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND messages.is_mms = recycle_bin_messages.is_mms WHERE recycle_bin_messages.id IS NOT NULL")
    fun getAllRecycleBinMessages(): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND messages.is_mms = recycle_bin_messages.is_mms WHERE recycle_bin_messages.id IS NULL AND is_scheduled = 1")
    fun getAllScheduledMessages(): List<Message>

    /**
     * DL-13: recycle-bin entries deleted before [timestamp], read from the bin itself. Reading them through their
     * cached copies missed every entry whose copy was never cached, so those stayed forever.
     */
    @Query("SELECT * FROM recycle_bin_messages WHERE deleted_ts < :timestamp")
    fun getOldRecycleBinEntries(timestamp: Long): List<RecycleBinMessage>

    /**
     * I02, I45: read, received or sent messages dated before [before] (seconds) that are not in the recycle bin,
     * oldest first, [limit] rows from [offset]. The automatic cleanup pages through them and picks in memory.
     */
    @Query("SELECT * FROM messages WHERE is_scheduled = 0 AND read = 1 AND date < :before AND type IN (1, 2) AND NOT EXISTS (SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms) ORDER BY date ASC, id ASC LIMIT :limit OFFSET :offset")
    fun getRetentionCandidates(before: Int, limit: Int, offset: Int): List<Message>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId")
    fun getThreadMessages(threadId: Long): List<Message>

    /** DL-12: any one message of the thread, for its participants, without loading the whole chat. */
    @Query("SELECT * FROM messages WHERE thread_id = :threadId LIMIT 1")
    fun getAnyThreadMessage(threadId: Long): Message?

    /** Per thread, the date of the newest cached message that was sent or received and is not in the recycle bin. */
    @Query(
        "SELECT thread_id, MAX(date) AS date FROM messages WHERE is_scheduled = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
            "GROUP BY thread_id"
    )
    fun getLastMessageDates(): List<ThreadLastDate>

    /** The threads with a message sent or received on the SIM [subscriptionId] (the SIM tile of the search page). */
    @Query("SELECT DISTINCT thread_id FROM messages WHERE subscription_id = :subscriptionId AND is_scheduled = 0")
    fun getThreadIdsOnSim(subscriptionId: Int): List<Long>

    /** DL-08: the message a chat's preview shows: its newest sent or received one outside the recycle bin. */
    @Query(
        "SELECT * FROM messages WHERE thread_id = :threadId AND is_scheduled = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
            "ORDER BY date DESC, id DESC LIMIT 1"
    )
    fun getNewestShownMessage(threadId: Long): Message?

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND messages.is_mms = recycle_bin_messages.is_mms WHERE recycle_bin_messages.id IS NULL AND thread_id = :threadId")
    fun getNonRecycledThreadMessages(threadId: Long): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND messages.is_mms = recycle_bin_messages.is_mms WHERE recycle_bin_messages.id IS NOT NULL AND thread_id = :threadId")
    fun getThreadMessagesFromRecycleBin(threadId: Long): List<Message>

    @Query("SELECT messages.* FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id AND messages.is_mms = recycle_bin_messages.is_mms WHERE recycle_bin_messages.id IS NULL AND thread_id = :threadId AND is_scheduled = 1")
    fun getScheduledThreadMessages(threadId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId AND id = :messageId AND is_scheduled = 1")
    fun getScheduledMessageWithId(threadId: Long, messageId: Long): Message

    @Query("SELECT COUNT(*) FROM recycle_bin_messages")
    fun getArchivedCount(): Int

    /** Messages of [threadId] that are in the recycle bin. */
    @Query(
        "SELECT COUNT(*) FROM messages JOIN recycle_bin_messages r " +
            "ON r.id = messages.id AND r.is_mms = messages.is_mms WHERE messages.thread_id = :threadId"
    )
    fun getRecycleBinMessageCountForThread(threadId: Long): Int

    /** Recycle-bin messages the bin can show and delete: those with a cached copy. */
    @Query(
        "SELECT COUNT(*) FROM messages JOIN recycle_bin_messages r " +
            "ON r.id = messages.id AND r.is_mms = messages.is_mms"
    )
    fun getRecycleBinMessageCount(): Int

    /** [pattern] is a [TextNormalizer.likeContains] pattern. Newest first, at most [SEARCH_RESULTS_LIMIT]. */
    @Query("SELECT * FROM messages WHERE body LIKE :pattern ESCAPE '\\' $SEARCH_ORDER")
    fun getMessagesWithText(pattern: String): List<Message>

    @RawQuery
    fun getMessagesRaw(query: SupportSQLiteQuery): List<Message>

    /**
     * Messages whose body contains [text], read through [TextNormalizer.fold] on both sides, so "علي" finds "علی",
     * "123" finds "۱۲۳" and a zero-width non-joiner does not break a match. `%` and `_` are matched as typed (MC-14).
     *
     * MC-17: newest first by date (kept in seconds for SMS and MMS alike; ids of the two are separate counters and
     * say nothing about age), at most [SEARCH_RESULTS_LIMIT].
     */
    fun searchMessages(text: String): List<Message> {
        if (!TextNormalizer.needsFold(text)) return getMessagesWithText(TextNormalizer.likeContains(text))
        // rowid, not id: SMS 57 and MMS 57 share an id (see MessageKey), but every row has its own rowid.
        val sql = TextNormalizer.sqlFoldedLike("messages", "rowid", "body") + " $SEARCH_ORDER"
        return getMessagesRaw(SimpleSQLiteQuery(sql, arrayOf(TextNormalizer.likeContains(TextNormalizer.fold(text)))))
    }

    @Query("UPDATE messages SET read = 1 WHERE id = :id AND is_mms = :isMMS")
    fun markRead(id: Long, isMMS: Boolean)

    @Query("UPDATE messages SET read = 1 WHERE thread_id = :threadId")
    fun markThreadRead(threadId: Long)

    @Query("UPDATE messages SET type = :type WHERE id = :id AND is_mms = :isMMS")
    fun updateType(id: Long, isMMS: Boolean, type: Int): Int

    @Query("UPDATE messages SET status = :status WHERE id = :id AND is_mms = :isMMS")
    fun updateStatus(id: Long, isMMS: Boolean, status: Int): Int

    /** Sets the type of the scheduled message [id] (never a provider message that happens to share the id). */
    @Query("UPDATE messages SET type = :type WHERE id = :id AND is_scheduled = 1")
    fun updateScheduledType(id: Long, type: Int): Int

    @Transaction
    fun delete(id: Long, isMMS: Boolean) {
        deleteFromMessages(id, isMMS)
        deleteFromRecycleBin(id, isMMS)
    }

    @Query("DELETE FROM messages WHERE id = :id AND is_mms = :isMMS")
    fun deleteFromMessages(id: Long, isMMS: Boolean)

    @Query("DELETE FROM recycle_bin_messages WHERE id = :id AND is_mms = :isMMS")
    fun deleteFromRecycleBin(id: Long, isMMS: Boolean)

    /**
     * Moves the scheduled message [id] to [threadId]. An update, not an insert: a message cancelled or sent in the
     * meantime (its row deleted by another thread) stays gone instead of coming back.
     */
    @Query("UPDATE messages SET thread_id = :threadId WHERE id = :id AND is_scheduled = 1")
    fun moveScheduledMessage(id: Long, threadId: Long)

    /** Deletes the scheduled message [id] (never a provider message that happens to share the id). */
    @Query("DELETE FROM messages WHERE id = :id AND is_scheduled = 1")
    fun deleteScheduledMessage(id: Long)

    @Transaction
    fun deleteThreadMessages(threadId: Long) {
        deleteThreadMessagesFromRecycleBin(threadId)
        deleteAllThreadMessages(threadId)
    }

    @Query("DELETE FROM messages WHERE thread_id = :threadId")
    fun deleteAllThreadMessages(threadId: Long)

    @Query(
        "DELETE FROM recycle_bin_messages WHERE EXISTS (SELECT 1 FROM messages WHERE messages.thread_id = :threadId " +
            "AND messages.id = recycle_bin_messages.id AND messages.is_mms = recycle_bin_messages.is_mms)"
    )
    fun deleteThreadMessagesFromRecycleBin(threadId: Long)

    /**
     * Drops the cached copies of provider messages. Scheduled messages and recycle-bin entries are kept: this table
     * is their only copy.
     */
    @Query(
        "DELETE FROM messages WHERE is_scheduled = 0 AND NOT EXISTS (SELECT 1 FROM recycle_bin_messages " +
            "WHERE recycle_bin_messages.id = messages.id AND recycle_bin_messages.is_mms = messages.is_mms)"
    )
    fun deleteCachedMessages()

    /** Newest messages that may hold a picture (their attachment text names an image type), at most [limit]. */
    @Query("$SHORTCUT_BASE AND $IMAGE_PREFILTER $SHORTCUT_ORDER")
    fun getImageCandidates(limit: Int): List<Message>

    @Query("$SHORTCUT_BASE AND thread_id = :threadId AND $IMAGE_PREFILTER $SHORTCUT_ORDER")
    fun getThreadImageCandidates(threadId: Long, limit: Int): List<Message>

    /** Newest messages whose text may hold a web link, at most [limit]. */
    @Query("$SHORTCUT_BASE AND $LINK_PREFILTER $SHORTCUT_ORDER")
    fun getLinkCandidates(limit: Int): List<Message>

    @Query("$SHORTCUT_BASE AND thread_id = :threadId AND $LINK_PREFILTER $SHORTCUT_ORDER")
    fun getThreadLinkCandidates(threadId: Long, limit: Int): List<Message>

    /** I13: the messages sent or received at [date] (seconds), to find a starred message again under a new id. */
    @Query("SELECT * FROM messages WHERE date = :date AND is_scheduled = 0")
    fun getMessagesAtDate(date: Int): List<Message>
}
