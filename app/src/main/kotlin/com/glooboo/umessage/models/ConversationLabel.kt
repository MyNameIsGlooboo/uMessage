package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Puts a conversation under a [Label]. */
@Entity(
    tableName = "conversation_labels",
    primaryKeys = ["thread_id", "label_id"],
)
data class ConversationLabel(
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "label_id") val labelId: Long,
)
