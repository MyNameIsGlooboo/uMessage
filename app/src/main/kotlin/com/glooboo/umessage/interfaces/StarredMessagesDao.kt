package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glooboo.umessage.models.StarredListRow
import com.glooboo.umessage.models.StarredMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface StarredMessagesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(starred: StarredMessage)

    @Query("DELETE FROM starred_messages WHERE id = :id AND is_mms = :isMMS")
    suspend fun delete(id: Long, isMMS: Boolean): Int

    @Query("SELECT * FROM starred_messages WHERE id = :id AND is_mms = :isMMS")
    suspend fun get(id: Long, isMMS: Boolean): StarredMessage?

    @Query("SELECT * FROM starred_messages ORDER BY starred_ts DESC")
    suspend fun getAll(): List<StarredMessage>

    @Query("SELECT * FROM starred_messages ORDER BY starred_ts DESC")
    fun observeAll(): Flow<List<StarredMessage>>

    /**
     * The Starred screen: one row per starred message, the most recently starred first, with the cached message it
     * points at (null columns when it is not cached). At most [limit] rows.
     */
    @Query(
        "SELECT s.id AS id, s.is_mms AS is_mms, s.thread_id AS thread_id, s.address AS address, s.date AS date, " +
            "s.starred_ts AS starred_ts, s.snapshot_body AS snapshot_body, m.body AS live_body, " +
            "m.type AS live_type, m.attachment AS live_attachment " +
            "FROM starred_messages s LEFT JOIN messages m ON m.id = s.id AND m.is_mms = s.is_mms " +
            "ORDER BY s.starred_ts DESC, s.date DESC, s.id DESC LIMIT :limit"
    )
    suspend fun getListRows(limit: Int): List<StarredListRow>

    @Query("SELECT * FROM starred_messages WHERE thread_id = :threadId")
    fun observeThread(threadId: Long): Flow<List<StarredMessage>>

    @Query("DELETE FROM starred_messages WHERE thread_id = :threadId")
    suspend fun deleteThread(threadId: Long): Int

    /** Blocking. A feature that deletes messages by itself (the auto-delete of codes) asks this and skips stars. */
    @Query("SELECT EXISTS(SELECT 1 FROM starred_messages WHERE id = :id AND is_mms = :isMMS)")
    fun isStarred(id: Long, isMMS: Boolean): Boolean
}
