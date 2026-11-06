package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.glooboo.umessage.models.Attachment

@Dao
interface AttachmentsDao {
    @Query("SELECT * FROM attachments")
    fun getAll(): List<Attachment>
}
