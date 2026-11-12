package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glooboo.umessage.models.MessageReminder
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageRemindersDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: MessageReminder): Long

    @Query("SELECT * FROM message_reminders WHERE id = :id")
    suspend fun get(id: Long): MessageReminder?

    @Query("SELECT * FROM message_reminders ORDER BY remind_at")
    suspend fun getAll(): List<MessageReminder>

    @Query("SELECT * FROM message_reminders ORDER BY remind_at")
    fun observeAll(): Flow<List<MessageReminder>>

    @Query("SELECT * FROM message_reminders WHERE message_id = :messageId AND is_mms = :isMMS")
    suspend fun getForMessage(messageId: Long, isMMS: Boolean): List<MessageReminder>

    @Query("SELECT * FROM message_reminders WHERE thread_id = :threadId")
    suspend fun getForThread(threadId: Long): List<MessageReminder>

    @Query("DELETE FROM message_reminders WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM message_reminders WHERE message_id = :messageId AND is_mms = :isMMS")
    suspend fun deleteForMessage(messageId: Long, isMMS: Boolean): Int
}
