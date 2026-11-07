package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.spam.TextNormalizer
import kotlinx.coroutines.flow.Flow

/** At most this many notes come back from a note search. */
const val NOTE_SEARCH_LIMIT = 200

@Dao
interface MessageNotesDao : MessageNotesQueryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: MessageNote)

    @Query("DELETE FROM message_notes WHERE note_key = :key")
    suspend fun delete(key: String): Int

    @Query("SELECT * FROM message_notes WHERE note_key = :key")
    suspend fun get(key: String): MessageNote?

    @Query("SELECT * FROM message_notes WHERE thread_id = :threadId ORDER BY message_date")
    suspend fun getForThread(threadId: Long): List<MessageNote>

    @Query("SELECT * FROM message_notes WHERE thread_id = :threadId ORDER BY message_date")
    fun observeThread(threadId: Long): Flow<List<MessageNote>>

    /** The chats that have at least one note (not the notes whose chat is not known yet). */
    @Query("SELECT DISTINCT thread_id FROM message_notes WHERE thread_id != 0")
    suspend fun getThreadIds(): List<Long>

    @Query("SELECT DISTINCT thread_id FROM message_notes WHERE thread_id != 0")
    fun observeThreadIds(): Flow<List<Long>>

    @Query("DELETE FROM message_notes WHERE thread_id = :threadId")
    suspend fun deleteThread(threadId: Long): Int
}

/** The bulk, binding and search queries of [MessageNotesDao], split out to keep it small; Room inherits them. */
interface MessageNotesQueryDao {
    @Query("SELECT * FROM message_notes ORDER BY updated_at DESC")
    suspend fun getAll(): List<MessageNote>

    /** Only the keys, for the cleanup that must not touch a noted message. */
    @Query("SELECT note_key FROM message_notes")
    suspend fun getAllKeys(): List<String>

    /** Notes of a restore whose chat is not known yet. */
    @Query("SELECT * FROM message_notes WHERE thread_id = 0")
    suspend fun getUnbound(): List<MessageNote>

    @Query("SELECT COUNT(*) FROM message_notes WHERE thread_id = 0")
    suspend fun countUnbound(): Int

    /** Notes whose chat has no cached message (the chat id changed, or the cache was rebuilt under other ids). */
    @Query(
        "SELECT * FROM message_notes WHERE thread_id != 0 AND NOT EXISTS " +
            "(SELECT 1 FROM messages m WHERE m.thread_id = message_notes.thread_id)"
    )
    suspend fun getStale(): List<MessageNote>

    @Query("UPDATE message_notes SET thread_id = :threadId WHERE note_key = :key")
    suspend fun setThread(key: String, threadId: Long): Int

    /** [pattern] is a TextNormalizer.likeContains pattern. Newest first, at most [NOTE_SEARCH_LIMIT]. */
    @Query(
        "SELECT * FROM message_notes WHERE note LIKE :pattern ESCAPE '${TextNormalizer.LIKE_ESCAPE}' " +
            "ORDER BY updated_at DESC LIMIT $NOTE_SEARCH_LIMIT"
    )
    suspend fun searchPlain(pattern: String): List<MessageNote>

    /** The folded search of TextNormalizer.sqlFoldedLike (a query over `message_notes`, one folded LIKE argument). */
    @RawQuery
    suspend fun searchRaw(query: SupportSQLiteQuery): List<MessageNote>
}
