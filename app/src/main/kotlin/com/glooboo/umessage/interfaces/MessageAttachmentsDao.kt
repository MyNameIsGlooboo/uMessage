package com.glooboo.umessage.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.glooboo.umessage.models.MessageAttachment

@Dao
interface MessageAttachmentsDao {
    @Query("SELECT * FROM message_attachments")
    fun getAll(): List<MessageAttachment>
}
