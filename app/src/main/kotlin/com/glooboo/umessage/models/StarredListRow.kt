package com.glooboo.umessage.models

import androidx.room.ColumnInfo

/**
 * One starred message as the Starred screen reads it: the star ([StarredMessage]) joined with the cached message it
 * points at. The cached columns are null when the message or its chat is gone from the cache; the star keeps its own
 * copy of the text in [snapshotBody].
 */
data class StarredListRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "is_mms") val isMMS: Boolean,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "starred_ts") val starredTS: Long,
    @ColumnInfo(name = "snapshot_body") val snapshotBody: String,
    @ColumnInfo(name = "live_body") val liveBody: String?,
    @ColumnInfo(name = "live_type") val liveType: Int?,
    @ColumnInfo(name = "live_attachment") val liveAttachment: MessageAttachment?,
) {
    val key: MessageKey
        get() = MessageKey(id, isMMS)
}
