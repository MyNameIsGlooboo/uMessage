package com.glooboo.umessage.spam

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.glooboo.umessage.spam.engine.SpamCause

/**
 * Provider truth (3.2): one one-to-one chat shown in Spam, in conversations.db (table spam_threads, schema 16). The
 * messages stay in the phone's store; this row only says where the chat is shown. ChatSorter writes it from the
 * sender's verdict and can always rebuild it.
 *
 * S9: a row counts only while the cached chat [threadId] still has [sender] as its number, so after a rebuilt store an
 * old row can never hide another person's chat. R12: [unread] is uMessage's own count of spam texts not looked at; it
 * is raised by one SQL update when a text arrives and a rebuild never writes it. [spamSince] is when the chat entered
 * Spam (S13): never zero, never moved back while the chat stays in Spam.
 */
@Entity(tableName = "spam_threads")
data class SpamThread(
    @PrimaryKey @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "cause") val cause: SpamCause,
    @ColumnInfo(name = "unread") val unread: Int = 0,
    @ColumnInfo(name = "spam_since") val spamSince: Long,
)

/** The columns of a cached chat that sorting reads (no snippet, no subquery). */
data class SortableChat(
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "phone_number") val phoneNumber: String,
    @ColumnInfo(name = "is_group_conversation") val isGroup: Boolean,
    @ColumnInfo(name = "is_scheduled") val isScheduled: Boolean,
) {
    /**
     * Only a real one-to-one chat can be in Spam: group chats always stay in Messages (R4), and a chat that holds only
     * scheduled messages is not in the phone's store.
     */
    val sortable: Boolean get() = !isGroup && !isScheduled
}
