package com.glooboo.umessage.helpers

import android.content.Context
import android.util.Log
import com.glooboo.umessage.backup.PendingArchive
import com.glooboo.umessage.extensions.clearExpiredScheduledMessages
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.getConversationsOrNull
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.getPrivateContactsIfAvailable
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import java.util.concurrent.Executor

/**
 * Keeps the saved conversation list in step with the phone's message store and hands every new list to [publish].
 *
 * MC-03: all work runs one job at a time on [executor], so an older result can never be published after a newer one.
 * Requests that arrive while a job runs are merged: any number of full refreshes asked for meanwhile become one more
 * pass, and a full pass also covers the single chats asked for before it started.
 *
 * MC-02: a change to one chat ([requestThread]) updates that chat only. The receivers have usually stored the new row
 * already, so this is a read of the saved list; the phone's store is read for that one thread only when the row is
 * missing. Only [requestFull] reads every thread.
 */
class ConversationListSync(
    private val context: Context,
    private val executor: Executor,
    private val publish: (conversations: ArrayList<Conversation>, cached: Boolean) -> Unit,
    /**
     * Where the message cache import (DL-10) runs. Not on [executor]: on a first start the import reads every message
     * on the phone, and the chat of a message that arrives meanwhile must not wait for it.
     */
    private val importExecutor: Executor = Executor { Thread(it, "message-cache-import").start() },
    /** Copies whole threads into the message cache; does nothing once complete or while a run is going. */
    private val importMessages: (threadIds: List<Long>) -> Unit = { MessageCacheImport.importIfNeeded(context, it) },
) {
    private val lock = Any()
    private var running = false
    private var started = false
    private var cachedPending = false
    private var fullPending = false
    private var bumpPending = false
    private var republishPending = false
    private val threadsPending = LinkedHashSet<Long>()

    /** Threads refreshed before the full pass; a bit more than a screenful. */
    private val firstPassSize = 50

    /**
     * Shows the saved list, then reads every thread once. Later calls do nothing: the list a rotated or recreated
     * screen shows is kept up to date by the requests below, not by reading everything again.
     */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            cachedPending = true
            fullPending = true
        }
        schedule()
    }

    /** Something may have changed anywhere: deleted chats, settings, a restore. Reads every thread. */
    fun requestFull() {
        synchronized(lock) { fullPending = true }
        schedule()
    }

    /** One chat changed (a message arrived, was sent or was read). */
    fun requestThread(threadId: Long) {
        synchronized(lock) { threadsPending += threadId }
        schedule()
    }

    /** MC-19: the screen came back; chats whose saved messages are newer than their row move up. */
    fun requestBump() {
        synchronized(lock) { bumpPending = true }
        schedule()
    }

    /** The screen came back from another one (the chat): show the saved rows as they are now, read flags included. */
    fun requestRepublish() {
        synchronized(lock) { republishPending = true }
        schedule()
    }

    private fun schedule() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        try {
            executor.execute(::drain)
        } catch (e: Exception) {
            // A shut-down executor (the screen is gone): nothing left to publish to.
            synchronized(lock) { running = false }
        }
    }

    private fun drain() {
        while (true) {
            val cached: Boolean
            val full: Boolean
            val bump: Boolean
            val republish: Boolean
            val threads: List<Long>
            synchronized(lock) {
                if (nothingPending()) {
                    running = false
                    return
                }
                cached = cachedPending
                full = fullPending
                bump = bumpPending
                republish = republishPending
                threads = threadsPending.toList()
                cachedPending = false
                fullPending = false
                bumpPending = false
                republishPending = false
                threadsPending.clear()
            }
            step { if (cached) showCached() }
            step {
                if (full) {
                    fullPass()
                } else if (threads.isNotEmpty()) {
                    threads.forEach(::refreshThread)
                    publishSaved()
                }
            }
            step { if (bump) bump() }
            step { if (republish) publishSaved() }
        }
    }

    private fun nothingPending() =
        !(cachedPending || fullPending || bumpPending || republishPending) && threadsPending.isEmpty()

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Conversation list refresh failed", e)
        }
    }

    private fun saved(): ArrayList<Conversation> = ArrayList(context.conversationsDB.getNonArchived())

    private fun publishSaved() = publish(saved(), false)

    private fun showCached() {
        val conversations = runCatching { saved() }.getOrDefault(ArrayList())
        publish(conversations, true)
        conversations.forEach { runCatching { context.clearExpiredScheduledMessages(it.threadId) } }
    }

    /** The one row of [threadId]: from the saved list when a receiver stored it, else from the phone's store. */
    private fun refreshThread(threadId: Long) {
        val db = context.conversationsDB
        if (db.getConversationWithThreadId(threadId) != null) return
        val fresh = context.getConversations(threadId = threadId).firstOrNull() ?: return
        val temporary = (db.getNonArchived() + db.getAllArchived())
            .firstOrNull { it.isScheduled && it.phoneNumber == fresh.phoneNumber }
        if (temporary != null) {
            adoptTemporaryThread(temporary, fresh)
        } else {
            context.insertOrUpdateConversation(fresh, null)
        }
    }

    /** A scheduled message's placeholder chat becomes the real thread it was sent in. */
    private fun adoptTemporaryThread(temporary: Conversation, real: Conversation) {
        context.conversationsDB.deleteThreadId(temporary.threadId)
        context.messagesDB.getScheduledThreadMessages(temporary.threadId).forEach { message ->
            context.messagesDB.insertOrUpdate(message.copy(threadId = real.threadId))
        }
        context.insertOrUpdateConversation(real, temporary)
    }

    private fun fullPass() {
        val db = context.conversationsDB
        val cachedConversations = ArrayList(db.getNonArchived() + db.getAllArchived())
        val privateContacts = context.getPrivateContactsIfAvailable()
        val lookups = ConversationLookups(context, bulk = true)

        // Refresh the newest threads first so the top of the list is current right away. The full pass below still
        // runs: it is what notices deleted threads and keeps the rest of the list, Archived and search up to date.
        val cachedById = cachedConversations.associateBy { it.threadId }
        val newest = context.getConversations(privateContacts = privateContacts, limit = firstPassSize, lookups = lookups)
        val changed = newest.filter { fresh ->
            val cached = cachedById[fresh.threadId]
            cached == null || !Conversation.areContentsTheSame(old = cached, new = fresh)
        }
        if (changed.isNotEmpty()) {
            changed.forEach { context.insertOrUpdateConversation(keepRead(it), cachedById[it.threadId]) }
            publish(saved(), true)
        }

        // A failed read (no cursor, provider error) would look like "every thread was deleted" below and wipe the
        // cached list. Keep the cache as it is and try again on the next refresh.
        val conversations = context.getConversationsOrNull(privateContacts = privateContacts, lookups = lookups)
        if (conversations == null) {
            publishSaved()
            return
        }
        val freshById = conversations.associateBy { it.threadId }
        // The newest thread of a number wins, as the old first-match search did.
        val freshByNumber by lazy { conversations.asReversed().associateBy { it.phoneNumber } }

        val cachedIds = cachedConversations.mapTo(HashSet()) { it.threadId }
        conversations.forEach { fresh ->
            if (cachedIds.add(fresh.threadId)) {
                db.insertOrUpdate(fresh)
                cachedConversations.add(fresh)
            }
        }

        cachedConversations.forEach { cached ->
            val threadId = cached.threadId
            val isTemporaryThread = cached.isScheduled
            if (threadId !in freshById && !isTemporaryThread) {
                db.deleteThreadId(threadId)
            }
            if (isTemporaryThread) {
                freshByNumber[cached.phoneNumber]?.let { adoptTemporaryThread(cached, it) }
            }
        }

        cachedConversations.forEach { cached ->
            val conv = freshById[cached.threadId]?.takeIf { !Conversation.areContentsTheSame(old = cached, new = it) }
            if (conv != null) {
                // FIXME: Scheduled message date is being reset here. Conversations with scheduled messages will have
                //  their original date.
                context.insertOrUpdateConversation(keepRead(conv))
            }
        }

        // Chats a restore archived before their rows existed (see PendingArchive).
        runCatching { PendingArchive(context).applyTo(db) }

        publishSaved()

        // DL-10 / MC-16: every message of every thread goes into the cache search reads, resuming where an earlier
        // start stopped. Does nothing once complete or while a run is still going.
        val threadIds = conversations.map { it.threadId }
        try {
            importExecutor.execute { runCatching { importMessages(threadIds) } }
        } catch (e: Exception) {
            Log.w(TAG, "Message cache import not started", e)
        }
    }

    /**
     * A snapshot read from the phone's store before the user opened a chat must not turn the row it was just marked
     * read in back to unread: when the saved row is read now and the snapshot has nothing newer, the row stays read.
     */
    internal fun keepRead(fresh: Conversation): Conversation {
        val now = context.conversationsDB.getConversationWithThreadId(fresh.threadId) ?: return fresh
        val stale = now.read && !fresh.read && fresh.date <= now.date
        return if (stale) fresh.copy(read = true, unreadCount = 0) else fresh
    }

    /** A chat whose newest saved message is newer than the chat row jumps to the top again. */
    private fun bump() {
        val db = context.conversationsDB
        var changed = false
        db.getNonArchivedOlderThanMessages().forEach { newer ->
            val conversation = db.getConversationWithThreadId(newer.threadId) ?: return@forEach
            // DL-08: never an unsent scheduled message or one in the recycle bin.
            val snippet = context.messagesDB.getNewestShownMessage(newer.threadId)?.body ?: conversation.snippet
            db.updateDateAndSnippet(newer.threadId, newer.date, snippet)
            changed = true
        }
        if (changed) publishSaved()
    }

    private companion object {
        const val TAG = "ConversationListSync"
    }
}
