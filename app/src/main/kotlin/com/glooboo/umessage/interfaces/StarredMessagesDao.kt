package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM starred_messages WHERE thread_id = :threadId")
    fun observeThread(threadId: Long): Flow<List<StarredMessage>>

    @Query("DELETE FROM starred_messages WHERE thread_id = :threadId")
    suspend fun deleteThread(threadId: Long): Int

    /** Blocking. A feature that deletes messages by itself (the auto-delete of codes) asks this and skips stars. */
    @Query("SELECT EXISTS(SELECT 1 FROM starred_messages WHERE id = :id AND is_mms = :isMMS)")
    fun isStarred(id: Long, isMMS: Boolean): Boolean
}
