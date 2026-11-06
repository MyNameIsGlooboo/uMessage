package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelsDao {
    @Insert
    suspend fun insertLabel(label: Label): Long

    @Update
    suspend fun updateLabel(label: Label)

    @Query("SELECT * FROM labels ORDER BY sort_order, id")
    suspend fun getLabels(): List<Label>

    @Query("SELECT * FROM labels ORDER BY sort_order, id")
    fun observeLabels(): Flow<List<Label>>

    @Query("DELETE FROM conversation_labels WHERE label_id = :labelId")
    suspend fun deleteLabelLinks(labelId: Long): Int

    @Query("DELETE FROM labels WHERE id = :labelId")
    suspend fun deleteLabelRow(labelId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToConversation(link: ConversationLabel)

    @Query("DELETE FROM conversation_labels WHERE thread_id = :threadId AND label_id = :labelId")
    suspend fun removeFromConversation(threadId: Long, labelId: Long): Int

    @Query("DELETE FROM conversation_labels WHERE thread_id = :threadId")
    suspend fun removeAllFromConversation(threadId: Long): Int

    @Query("SELECT * FROM conversation_labels")
    fun observeLinks(): Flow<List<ConversationLabel>>

    @Query("SELECT thread_id FROM conversation_labels WHERE label_id = :labelId")
    suspend fun getThreadIds(labelId: Long): List<Long>

    @Query(
        "SELECT l.* FROM labels l JOIN conversation_labels c ON c.label_id = l.id " +
            "WHERE c.thread_id = :threadId ORDER BY l.sort_order, l.id"
    )
    suspend fun getLabelsOfConversation(threadId: Long): List<Label>
}
