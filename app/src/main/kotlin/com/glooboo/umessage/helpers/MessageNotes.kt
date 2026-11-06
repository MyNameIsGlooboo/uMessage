package com.glooboo.umessage.helpers

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.interfaces.MessageNotesDao
import com.glooboo.umessage.interfaces.NOTE_SEARCH_LIMIT
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.spam.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.messageNotesDB: MessageNotesDao
    get() = getMessagesDB().MessageNotesDao()

/** The notes of this app, over the application context: `context.messageNotes.setNote(message, "rent")`. */
val Context.messageNotes: MessageNotes
    get() {
        val app = applicationContext
        return MessageNotes(app.messageNotesDB, messagesAtDate = { date ->
            app.getMessagesDB().MessagesDao().getMessagesAtDate(date)
        })
    }

/**
 * One private text note per message (see [MessageNote] and [MessageNoteKey]). Every function is a suspend function
 * that works on the IO dispatcher. Notes are never touched by a cache rebuild, because they live in their own table.
 *
 * Rules:
 * - the text is trimmed and cut to [MAX_LENGTH] characters; blank text deletes the note;
 * - saving again keeps `createdAt` and moves `updatedAt`;
 * - a note stays while its message is in the recycle bin, and goes when the message is deleted for good
 *   ([onMessageDeleted]) or its chat is ([onThreadDeleted]).
 *
 * [messagesAtDate] gives the cached messages of one second; it is how a restored note finds its chat again.
 */
@Suppress("TooManyFunctions")
class MessageNotes(
    private val dao: MessageNotesDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val messagesAtDate: (dateSeconds: Int) -> List<Message> = { emptyList() },
) {

    /** The note of [message], or null. */
    suspend fun noteFor(message: Message): MessageNote? = withContext(Dispatchers.IO) {
        dao.get(MessageNoteKey.of(message))
    }

    /**
     * The notes of one chat, by note key: `notes[MessageNoteKey.of(message)]` is the note of a message on screen.
     * Notes restored from a backup find their chat first (see [relink]).
     */
    suspend fun notesForThread(threadId: Long): Map<String, MessageNote> = withContext(Dispatchers.IO) {
        bindRestored()
        dao.getForThread(threadId).associateBy { it.key }
    }

    /** Same as [notesForThread], kept up to date. */
    fun observeThread(threadId: Long): Flow<Map<String, MessageNote>> =
        dao.observeThread(threadId).map { list -> list.associateBy { it.key } }

    /**
     * Saves [text] as the note of [message] and returns the saved note, or null when the text is blank (the note is
     * deleted then) or the message is a scheduled one, which has no stable identity yet.
     */
    suspend fun setNote(message: Message, text: String): MessageNote? = withContext(Dispatchers.IO) {
        val key = MessageNoteKey.of(message)
        val clean = clean(text)
        if (clean.isEmpty()) {
            dao.delete(key)
            return@withContext null
        }
        if (message.isScheduled) return@withContext null
        val now = clock()
        val old = dao.get(key)
        val note = MessageNote(
            key = key,
            threadId = message.threadId,
            messageDate = message.date,
            text = clean,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(note)
        note
    }

    /** Removes the note of [message]. True when there was one. */
    suspend fun deleteNote(message: Message): Boolean = withContext(Dispatchers.IO) {
        dao.delete(MessageNoteKey.of(message)) > 0
    }

    /** The chats that have notes. */
    suspend fun threadsWithNotes(): Set<Long> = withContext(Dispatchers.IO) {
        bindRestored()
        dao.getThreadIds().toSet()
    }

    /** Same as [threadsWithNotes], kept up to date (restored notes appear once their chat is known). */
    fun observeThreadsWithNotes(): Flow<Set<Long>> = dao.observeThreadIds().map { it.toSet() }.distinctUntilChanged()

    /** Notes whose text contains [text] (Persian and Arabic letter and digit variants match), newest first. */
    suspend fun search(text: String): List<MessageNote> = withContext(Dispatchers.IO) {
        val wanted = text.trim()
        when {
            wanted.isEmpty() -> emptyList()
            !TextNormalizer.needsFold(wanted) -> dao.searchPlain(TextNormalizer.likeContains(wanted))
            else -> {
                val sql = TextNormalizer.sqlFoldedLike("message_notes", "note_key", "note") +
                    " ORDER BY updated_at DESC LIMIT ${NOTE_SEARCH_LIMIT}"
                val pattern = TextNormalizer.likeContains(TextNormalizer.fold(wanted))
                dao.searchRaw(SimpleSQLiteQuery(sql, arrayOf(pattern)))
            }
        }
    }

    /** Called when a message is deleted for good (not when it only moves to the recycle bin). */
    suspend fun onMessageDeleted(key: String) = withContext(Dispatchers.IO) {
        dao.delete(key)
        Unit
    }

    /** Called when a chat is deleted: its notes go with it. */
    suspend fun onThreadDeleted(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId != 0L) dao.deleteThread(threadId)
        Unit
    }

    /** Every note, newest edit first, for a backup. */
    suspend fun exportAll(): List<MessageNote> = withContext(Dispatchers.IO) { dao.getAll() }

    /**
     * Adds the notes of a backup and returns how many were taken. Merge rule: a note this phone already has is
     * replaced only by a backup note with a later `updatedAt` (the newer edit wins, a tie keeps this phone's note);
     * `createdAt` is the earlier of the two. A note new to this phone gets chat 0 until [relink] finds its chat.
     * Blank or key-less entries are skipped and the text is cut to [MAX_LENGTH].
     */
    suspend fun restore(notes: List<MessageNote>): Int = withContext(Dispatchers.IO) {
        var taken = 0
        for (incoming in notes) {
            if (mergeRestored(incoming)) taken++
        }
        taken
    }

    /** Merges one backup note by the rule of [restore]; true when it was taken. */
    private suspend fun mergeRestored(incoming: MessageNote): Boolean {
        val text = clean(incoming.text)
        val existing = if (text.isEmpty() || incoming.key.isBlank()) null else dao.get(incoming.key)
        val skip = text.isEmpty() || incoming.key.isBlank() ||
            (existing != null && existing.updatedAt >= incoming.updatedAt)
        if (!skip) {
            dao.upsert(
                MessageNote(
                    key = incoming.key,
                    threadId = existing?.threadId ?: 0L,
                    messageDate = incoming.messageDate,
                    text = text,
                    createdAt = existing?.createdAt?.let { minOf(it, incoming.createdAt) } ?: incoming.createdAt,
                    updatedAt = incoming.updatedAt,
                )
            )
        }
        return !skip
    }

    /**
     * Gives every note without a chat (chat 0, after a restore) the chat of the cached message it belongs to.
     * Returns how many were bound. Never throws; notes whose message is not cached yet stay as they are.
     */
    suspend fun relink(): Int = withContext(Dispatchers.IO) { bindRestored() }

    private suspend fun bindRestored(): Int {
        if (dao.countUnbound() == 0) return 0
        var bound = 0
        runCatching {
            for (note in dao.getUnbound()) {
                val match = messagesAtDate(note.messageDate).firstOrNull { MessageNoteKey.of(it) == note.key }
                if (match != null && match.threadId != 0L) {
                    dao.setThread(note.key, match.threadId)
                    bound++
                }
            }
        }
        return bound
    }

    private fun clean(text: String): String = text.trim().take(MAX_LENGTH).trimEnd()

    companion object {
        const val MAX_LENGTH = 500
    }
}
