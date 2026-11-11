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
import com.glooboo.umessage.helpers.MessageFoldBackfill
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.spam.TextNormalizer

/** Search shows at most this many messages (and this many conversations), newest first (MC-17). */
const val SEARCH_RESULTS_LIMIT = 100

/** Search in one conversation reads at most this many matches. */
const val THREAD_SEARCH_LIMIT = 1000

private const val SEARCH_ORDER = "ORDER BY date DESC, id DESC LIMIT $SEARCH_RESULTS_LIMIT"

/* I28, I56: the shortcut queries only prefilter in SQL; the caller keeps the rows that really hold a picture or link. */
/** F5: what search and the shortcuts may show: sent or received messages that are not in the recycle bin. */
private const val SHOWN_ONLY = "is_scheduled = 0 AND NOT EXISTS " +
    "(SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms)"
private const val SHORTCUT_BASE = "SELECT * FROM messages WHERE $SHOWN_ONLY"
private const val IMAGE_PREFILTER = "attachment LIKE '%image/%'"
private const val LINK_PREFILTER = "(body LIKE '%http%' OR body LIKE '%www.%')"
private const val SHORTCUT_ORDER = "ORDER BY date DESC, id DESC LIMIT :limit"

/** A message whose folded body is still to be written: its row number and body (the backfill). */
data class UnfoldedBody(
    @ColumnInfo(name = "rid") val rowid: Long,
    @ColumnInfo(name = "body") val body: String,
)

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

    /** PF1: the newest [limit] cached messages of the thread (what opening a chat shows), not the whole thread. */
    @Query("SELECT * FROM messages WHERE thread_id = :threadId ORDER BY date DESC, id DESC LIMIT :limit")
    fun getNewestThreadMessages(threadId: Long, limit: Int): List<Message>

    /** PF1: like [getNewestThreadMessages], without the messages in the recycle bin. */
    @Query(
        "SELECT * FROM messages WHERE thread_id = :threadId AND NOT EXISTS " +
            "(SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
            "ORDER BY date DESC, id DESC LIMIT :limit"
    )
    fun getNewestNonRecycledThreadMessages(threadId: Long, limit: Int): List<Message>

    /** PF1: like [getNewestThreadMessages], only the messages in the recycle bin. */
    @Query(
        "SELECT * FROM messages WHERE thread_id = :threadId AND EXISTS " +
            "(SELECT 1 FROM recycle_bin_messages r WHERE r.id = messages.id AND r.is_mms = messages.is_mms) " +
            "ORDER BY date DESC, id DESC LIMIT :limit"
    )
    fun getNewestRecycleBinThreadMessages(threadId: Long, limit: Int): List<Message>

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

    /** One scheduled message by id, without loading every scheduled message. */
    @Query("SELECT * FROM messages WHERE id = :messageId AND is_scheduled = 1 LIMIT 1")
    fun getScheduledMessageById(messageId: Long): Message?

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
    @Query("SELECT * FROM messages WHERE $SHOWN_ONLY AND body LIKE :pattern ESCAPE '\\' $SEARCH_ORDER")
    fun getMessagesWithText(pattern: String): List<Message>

    /** [pattern] is a [TextNormalizer.likeContains] pattern. One thread, newest first, at most [THREAD_SEARCH_LIMIT]. */
    @Query(
        "SELECT * FROM messages WHERE thread_id = :threadId AND body LIKE :pattern ESCAPE '\\' AND $SHOWN_ONLY " +
            "ORDER BY date DESC, id DESC LIMIT $THREAD_SEARCH_LIMIT"
    )
    fun getThreadMessagesWithText(threadId: Long, pattern: String): List<Message>

    /** Like [searchMessages], within the one conversation [threadId] (search in conversation, TH-20). */
    fun searchThreadMessages(threadId: Long, text: String): List<Message> {
        if (!TextNormalizer.needsFold(text)) return getThreadMessagesWithText(threadId, TextNormalizer.likeContains(text))
        val sql = TextNormalizer.sqlFoldedLike("messages", "rowid", "body", "thread_id = $threadId AND $SHOWN_ONLY") +
            " ORDER BY date DESC, id DESC LIMIT $THREAD_SEARCH_LIMIT"
        return getMessagesRaw(SimpleSQLiteQuery(sql, arrayOf(TextNormalizer.likeContains(TextNormalizer.fold(text)))))
    }

    @RawQuery
    fun getMessagesRaw(query: SupportSQLiteQuery): List<Message>

    /** PF2: [pattern] is a [TextNormalizer.likeContains] pattern of an already folded text, matched on `body_folded`. */
    @Query("SELECT * FROM messages WHERE $SHOWN_ONLY AND body_folded LIKE :pattern ESCAPE '\\' $SEARCH_ORDER")
    fun getMessagesWithFoldedText(pattern: String): List<Message>

    /** Up to [limit] messages after row [afterRowid] whose folded body has not been written yet (the backfill). */
    @Query(
        "SELECT rowid AS rid, body FROM messages WHERE rowid > :afterRowid AND body_folded = '' AND body <> '' " +
            "ORDER BY rowid LIMIT :limit"
    )
    fun getUnfoldedBodies(afterRowid: Long, limit: Int): List<UnfoldedBody>

    /** Writes [folded] to row [rowid]; does nothing when the body changed (or the row was replaced) meanwhile. */
    @Query("UPDATE messages SET body_folded = :folded WHERE rowid = :rowid AND body = :body")
    fun updateBodyFolded(rowid: Long, body: String, folded: String): Int

    /** Writes a whole batch of the backfill in one transaction. */
    @Transaction
    fun updateBodiesFolded(batch: List<UnfoldedBody>) {
        for (row in batch) updateBodyFolded(row.rowid, row.body, TextNormalizer.fold(row.body))
    }

    /**
     * Messages whose body contains [text], read through [TextNormalizer.fold] on both sides, so "علي" finds "علی",
     * "123" finds "۱۲۳" and a zero-width non-joiner does not break a match. `%` and `_` are matched as typed (MC-14).
     *
     * PF2: the query is folded once here and compared with the saved `body_folded` column. Until
     * [com.glooboo.umessage.helpers.MessageFoldBackfill] has filled the column of every old row, the rows still
     * empty (`body_folded = ''` with a body) are searched the old way, with the SQL fold chain, and merged in; the
     * chain then runs over those rows only, and not at all once the backfill is complete.
     *
     * MC-17: newest first by date (kept in seconds for SMS and MMS alike; ids of the two are separate counters and
     * say nothing about age), at most [SEARCH_RESULTS_LIMIT].
     */
    fun searchMessages(text: String): List<Message> {
        if (!TextNormalizer.needsFold(text)) return getMessagesWithText(TextNormalizer.likeContains(text))
        val pattern = TextNormalizer.likeContains(TextNormalizer.fold(text))
        val fast = getMessagesWithFoldedText(pattern)
        if (MessageFoldBackfill.isComplete) return fast
        // rowid, not id: SMS 57 and MMS 57 share an id (see MessageKey), but every row has its own rowid.
        val unfolded = SHOWN_ONLY + " AND body_folded = '' AND body <> ''"
        val sql = TextNormalizer.sqlFoldedLike("messages", "rowid", "body", unfolded) + " $SEARCH_ORDER"
        val old = getMessagesRaw(SimpleSQLiteQuery(sql, arrayOf(pattern)))
        if (old.isEmpty()) return fast
        return (fast + old).distinctBy { it.key }
            .sortedWith(compareByDescending<Message> { it.date }.thenByDescending { it.id })
            .take(SEARCH_RESULTS_LIMIT)
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

    /**
     * F3: claims the scheduled message [id] for sending: flips it from queued (6) to outbox (4) in one statement, so of
     * any number of racing senders (alarm, app-start re-arm, "send now") exactly one gets 1 back and sends it.
     */
    @Query("UPDATE messages SET type = 4 WHERE id = :id AND is_scheduled = 1 AND type = 6")
    fun claimScheduledForSend(id: Long): Int

    /** Like [claimScheduledForSend], and also takes a message held as failed (5): the user's Retry or "Send now". */
    @Query("UPDATE messages SET type = 4 WHERE id = :id AND is_scheduled = 1 AND type IN (5, 6)")
    fun claimScheduledOrHeldForSend(id: Long): Int

    /** Keeps only the attachments of the scheduled message [id] that have not been handed off yet (MS6). */
    @Query("UPDATE messages SET attachment = :attachment WHERE id = :id AND is_scheduled = 1")
    fun updateScheduledAttachment(id: Long, attachment: MessageAttachment?): Int

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

    /** DA3: changes whenever the cache gets a new row; O(1), so it can say "nothing new since the last look". */
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM messages")
    fun cacheStamp(): Long
}
