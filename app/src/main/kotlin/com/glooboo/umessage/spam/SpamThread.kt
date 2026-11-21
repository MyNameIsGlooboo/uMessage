package com.glooboo.umessage.spam

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.NumberReaders
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.engine.defaultRegion

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

/**
 * The Spam rows of the app's own conversations.db for the incoming paths (step D, B-7): the sender test reads a
 * number with the one region list (R6, S8), so "+15550001234" and "(555) 000-1234" are one sender.
 */
object SpamThreads {
    /** The one reading of a sender, as the sorter and the filter read it. */
    fun readers(context: Context): NumberReaders {
        val app = context.applicationContext
        return NumberReaders(NumberNormalizer { defaultRegion(app) }) { SpamEngine.simRegions(app) }
    }

    fun same(readers: NumberReaders, a: String, b: String): Boolean = a == b || readers.same(a, b)

    /** A spam text from [address] arrives in [threadId]: its row first, then unread + 1 (6.1, R12, B-7). */
    fun noteArrival(context: Context, threadId: Long, address: String, cause: SpamCause, at: Long) {
        val readers = readers(context)
        MessagesDatabase.getInstance(context.applicationContext).ConversationsDao()
            .noteSpamArrival(threadId, address, cause, at) { a, b -> same(readers, a, b) }
        // H-3 (U9): a chat in Spam has no launcher or share shortcut, also when it got there by this text.
        ShortcutHelper.removeForSpam(context, threadId)
    }

    /** R1's safety check: [threadId] has a Spam row for the sender [address] (its chat row may not be cached yet). */
    fun isSpamThreadOf(context: Context, threadId: Long, address: String): Boolean {
        val row = MessagesDatabase.getInstance(context.applicationContext).ConversationsDao().getSpamThread(threadId)
            ?: return false
        return same(readers(context), row.sender, address)
    }
}
