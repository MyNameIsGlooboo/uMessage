package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glooboo.umessage.models.ScheduledRepeat

@Dao
interface ScheduledRepeatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(repeat: ScheduledRepeat)

    @Query("SELECT * FROM scheduled_repeats WHERE message_id = :messageId")
    suspend fun get(messageId: Long): ScheduledRepeat?

    @Query("SELECT * FROM scheduled_repeats WHERE rule != 'NONE'")
    suspend fun getAllRepeating(): List<ScheduledRepeat>

    @Query("DELETE FROM scheduled_repeats WHERE message_id = :messageId")
    suspend fun delete(messageId: Long): Int
}
