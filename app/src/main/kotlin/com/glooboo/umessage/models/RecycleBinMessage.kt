package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity

/** A message moved to the recycle bin, keyed like the messages table: see [MessageKey]. */
@Entity(
    tableName = "recycle_bin_messages",
    primaryKeys = ["id", "is_mms"],
)
data class RecycleBinMessage(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "is_mms") val isMMS: Boolean,
    @ColumnInfo(name = "deleted_ts") var deletedTS: Long
) {
    val key: MessageKey
        get() = MessageKey(id, isMMS)
}
