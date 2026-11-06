package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A private note a user attached to one message. It stays on the device (and in the user's own backup file).
 *
 * The note is tied to a stable [key] (see MessageNoteKey), not to the provider id, because the id of a message
 * changes after a restore or import. [threadId] is only a cache so the chat list can ask "which chats have notes"
 * without scanning; 0 means "not known yet" (a restored note) and is filled in again from the message cache.
 * [messageDate] is the message time in seconds; it lets the app find the message again to fill in [threadId].
 */
@Entity(
    tableName = "message_notes",
    indices = [Index(value = ["thread_id"])],
)
data class MessageNote(
    @PrimaryKey @ColumnInfo(name = "note_key") val key: String,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "message_date") val messageDate: Int,
    @ColumnInfo(name = "note") val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
