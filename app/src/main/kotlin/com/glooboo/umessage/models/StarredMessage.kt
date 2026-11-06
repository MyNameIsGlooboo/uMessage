package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A starred message, keyed like the messages table (see [MessageKey]). The messages table is a provider cache that
 * gets rebuilt, so the star lives here; [bodyHash] and [snapshotBody] let the app find the message again if the
 * provider hands out a new id.
 */
@Entity(
    tableName = "starred_messages",
    primaryKeys = ["id", "is_mms"],
)
data class StarredMessage(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "is_mms") val isMMS: Boolean,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "body_hash") val bodyHash: Int,
    @ColumnInfo(name = "starred_ts") val starredTS: Long,
    @ColumnInfo(name = "snapshot_body") val snapshotBody: String,
) {
    val key: MessageKey
        get() = MessageKey(id, isMMS)
}
