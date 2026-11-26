package com.glooboo.umessage.spam.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.draftsDB
import com.glooboo.umessage.extensions.forgetThread
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.readStoredMessage
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.MessageNoteKey
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.engine.SpamEngine
import kotlinx.coroutines.runBlocking

/** What auto-delete reads and deletes; on the phone [AndroidAutoDeleteSource]. */
internal interface AutoDeleteSource {
    /** uMessage holds the default SMS role: only then may it delete from the phone's store. */
    fun canDelete(): Boolean

    /** The chats shown in Spam now (valid rows, S9). */
    fun spamThreads(): List<SpamThread>

    /** The cached messages of [threadId]. */
    fun cached(threadId: Long): List<Message>

    /** Starred or noted: never deleted (S13). */
    fun isKept(message: Message): Boolean

    /**
     * M-D2: the store row of the cached [message] is the same message (same id, chat, sender, time and text) and was
     * received from the sender of [thread]. False for anything else, also when the store cannot be read.
     */
    fun storeMatches(message: Message, thread: SpamThread): Boolean

    /** Deletes [message] from the phone's store and its cache row; true when the store row is gone. */
    fun delete(message: Message): Boolean

    /** After deletes in [threadId]: the chat row dated again, or forgotten when the store has nothing left (B3-2). */
    fun afterThread(threadId: Long)
}

/**
 * S13, M-D2: auto-delete of Spam for the phone's store. Off by default ("Keep forever", [days] 0). With N days, only
 * received texts of chats in Spam that arrived while the chat was in Spam (dated at or after its spam_since) and are
 * older than N days are deleted. Sent, scheduled, starred and noted texts always stay. Before each permanent delete
 * the cached row is checked against the store row, so a stale cache can never delete another message. Needs the
 * default role; without it nothing is deleted.
 */
internal class SpamAutoDeleter(
    private val source: AutoDeleteSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns how many messages were deleted. */
    fun run(days: Int): Int {
        if (days <= 0 || !source.canDelete()) return 0
        val cutoff = clock() - days * DAY_MS
        var deleted = 0
        for (thread in source.spamThreads()) {
            // F-11: spam_since is never 0; a value of 0 or 1 is unknown, and then nothing of the chat goes.
            if (thread.spamSince <= 1L) continue
            val since = thread.spamSince
            val gone = source.cached(thread.threadId)
                .filter { it.isDue(since, cutoff) && !source.isKept(it) && source.storeMatches(it, thread) }
                .count { runCatching { source.delete(it) }.getOrDefault(false) }
            if (gone > 0) runCatching { source.afterThread(thread.threadId) }
            deleted += gone
        }
        return deleted
    }

    /** F-13: compared in whole seconds, as the cache keeps the date, so the text that put the chat in Spam counts. */
    private fun Message.isDue(since: Long, cutoff: Long): Boolean =
        type == Sms.MESSAGE_TYPE_INBOX && !isScheduled && millis() >= since / MILLIS * MILLIS && millis() < cutoff

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MILLIS = 1000L
    }
}

/** Auto-delete of Spam on this phone; nothing runs before the first sort. */
object SpamAutoDelete {
    private const val TAG = "uMessageReceive"

    @VisibleForTesting
    @Volatile
    internal var sourceFor: (Context) -> AutoDeleteSource = ::AndroidAutoDeleteSource

    /** Runs with the stored "Auto-delete of Spam" days. Never throws; returns how many messages were deleted. */
    fun run(context: Context): Int {
        val app = context.applicationContext
        return runCatching {
            // Spam is what the first sort published; before it, no chat is in Spam for good.
            if (!FirstRun.state(app).value.sortPublished) return 0
            val days = SpamEngine.repository(app).currentSettings().purgeAfterDays
            SpamAutoDeleter(sourceFor(app)).run(days)
        }.onFailure { Log.w(TAG, "Spam auto-delete failed: ${it.javaClass.simpleName}") }.getOrDefault(0)
    }
}

/** The phone's store, the message cache, the stars and the notes. */
@Suppress("TooManyFunctions")
internal class AndroidAutoDeleteSource(private val app: Context) : AutoDeleteSource {
    private val readers by lazy { SpamThreads.readers(app) }

    /** Stars by id, and by what a restored star (not linked to its message yet) still knows. */
    private val stars by lazy { runBlocking { app.starredDB.getAll() } }
    private val starredIds by lazy { stars.mapTo(HashSet()) { it.id to it.isMMS } }
    private val starredShapes by lazy {
        stars.mapTo(HashSet()) { Shape(it.isMMS, it.date, it.bodyHash, StarredMessages.normalizedAddress(it.address)) }
    }
    private val notedKeys = HashMap<Long, Set<String>>()

    private data class Shape(val isMms: Boolean, val date: Long, val bodyHash: Int, val address: String)

    override fun canDelete(): Boolean =
        runCatching { DefaultSmsRole.status(app) == DefaultSmsRole.Status.Held }.getOrDefault(false)

    override fun spamThreads(): List<SpamThread> = app.conversationsDB.getValidSpamThreads()

    override fun cached(threadId: Long): List<Message> = app.messagesDB.getThreadMessages(threadId)

    override fun isKept(message: Message): Boolean {
        if ((message.id to message.isMMS) in starredIds) return true
        val shape = Shape(
            message.isMMS, message.date.toLong(), StarredMessages.fingerprint(message.body),
            StarredMessages.normalizedAddress(StarredMessages.addressOf(message)),
        )
        if (shape in starredShapes) return true
        val noted = notedKeys.getOrPut(message.threadId) {
            runBlocking { app.messageNotes.notesForThread(message.threadId) }.keys
        }
        return MessageNoteKey.of(message) in noted
    }

    override fun storeMatches(message: Message, thread: SpamThread): Boolean = runCatching {
        val stored = app.readStoredMessage(message.id, message.isMMS)
        stored != null && stored.threadId == thread.threadId && stored.threadId == message.threadId &&
            stored.date == message.date && stored.body == message.body && stored.received &&
            same(stored.sender, message.senderPhoneNumber) && same(stored.sender, thread.sender)
    }.getOrDefault(false)

    override fun delete(message: Message): Boolean {
        val uri = ContentUris.withAppendedId(if (message.isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI, message.id)
        if (app.contentResolver.delete(uri, null, null) != 1) return false
        runCatching { app.messagesDB.delete(message.id, message.isMMS) }
        return true
    }

    override fun afterThread(threadId: Long) {
        // F-12: the chat's scheduled messages and draft are the app's only copy; a chat that has one is not forgotten.
        val keepsOwnData = app.messagesDB.getScheduledThreadMessages(threadId).isNotEmpty() ||
            app.draftsDB.getDraftById(threadId) != null
        val storeEmpty = storeIsEmpty(threadId)
        if (!keepsOwnData && storeEmpty) {
            // B3-2: a chat the delete emptied is forgotten here, so the mass-disappearance guard keeps nothing of it.
            app.forgetThread(threadId, vanished = true)
        } else {
            app.updateLastConversationMessage(threadId)
        }
        // No Spam text is left to announce (the chat is gone, or keeps only its own draft or scheduled text): the quiet
        // notice in the shade goes. A chat that still has texts keeps it.
        if (storeEmpty) BlockedNotifier.cancelChat(app, threadId)
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
    }

    /** Only a store that answered "nothing" for both kinds is empty; a failed read is not. */
    private fun storeIsEmpty(threadId: Long): Boolean = listOf(Sms.CONTENT_URI, Mms.CONTENT_URI).all { uri: Uri ->
        runCatching {
            app.contentResolver.query(uri, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf("$threadId"), null)
                ?.use { it.count }
        }.getOrNull() == 0
    }

    private fun same(a: String, b: String): Boolean =
        a == b || runCatching { SpamThreads.same(readers, a, b) }.getOrDefault(false)
}
