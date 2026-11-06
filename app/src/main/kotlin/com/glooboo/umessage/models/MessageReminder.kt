package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A reminder to look at a message again at [remindAt]; [snapshotBody] is what the notification shows. */
@Entity(tableName = "message_reminders")
data class MessageReminder(
    @ColumnInfo(name = "id") @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "is_mms") val isMMS: Boolean,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "remind_at") val remindAt: Long,
    @ColumnInfo(name = "snapshot_body") val snapshotBody: String,
) {
    val key: MessageKey
        get() = MessageKey(messageId, isMMS)
}
