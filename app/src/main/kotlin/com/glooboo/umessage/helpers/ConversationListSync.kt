package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Telephony.Threads
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.backup.PendingArchive
import com.glooboo.umessage.backup.PendingRecycled
import com.glooboo.umessage.extensions.clearExpiredScheduledMessages
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.forgetThread
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.getConversationsOrNull
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.getPrivateContactsIfAvailable
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.NewChatSorting
import com.glooboo.umessage.spam.SpamSorting
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
 *
 * J2 (list speed): in each job the steps that read the saved rows only (cached list, single chats, bump, republish)
 * run before the full pass. While a job runs, a republish or a single chat is also shown at once from a second
 * thread, and every publish reads the rows inside one lock, so the MC-03 order still holds.
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
    private val importMessages: (threadIds: List<Long>) -> Unit = {
        // PF2: fold the bodies cached before database version 15 first (cheap, resumable), then import as before.
        runCatching { MessageFoldBackfill.runIfNeeded(context) }
        MessageCacheImport.importIfNeeded(context, it)
    },
) {
    /**
     * J2: where the saved rows are shown while a job holds [executor] (a full pass can take seconds). Not a constructor
     * parameter: callers pass [importMessages] as a trailing lambda. Tests swap it for one they can wait for.
     */
    @VisibleForTesting
    internal var quickExecutor: Executor = Executor { Thread(it, "conversation-list-quick").start() }

    private val lock = Any()
    private var running = false
    private var started = false
    private var cachedPending = false
    private var fullPending = false
    private var bumpPending = false
    private var republishPending = false
    private val threadsPending = LinkedHashSet<Long>()

    /** J2: the list is out of sight; full passes asked for meanwhile wait for [requestReturn] and become one. */
    private var away = false
    private var fullHeld = false
    private var quickPending = false

    /** J2: every publish reads the saved rows inside this lock, so a list is never handed on after a newer one. */
    private val publishLock = Any()

    @Volatile
    private var publishCount = 0

    /** J2: when the list was opened or came back, for the timing lines ([ListTiming]). */
    @Volatile
    private var markAt = SystemClock.uptimeMillis()

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
        markAt = SystemClock.uptimeMillis()
        ListTiming.line(context) { "start; process up ${ListTiming.sinceProcessStart()} ms" }
        schedule()
    }

    /**
     * Something may have changed anywhere: deleted chats, settings, a restore. Reads every thread; while the list is
     * away (J2), once when it is shown again.
     */
    fun requestFull() {
        val held = synchronized(lock) {
            if (away) fullHeld = true else fullPending = true
            away
        }
        ListTiming.line(context) { "full pass asked; held=$held" }
        if (!held) schedule()
    }

    /** One chat changed (a message arrived, was sent or was read). Never a full pass. */
    fun requestThread(threadId: Long) {
        synchronized(lock) { threadsPending += threadId }
        publishQuickIfBusy()
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
        publishQuickIfBusy()
        schedule()
    }

    /** J2: the list went out of sight (another screen or app is in front). */
    fun requestAway() {
        synchronized(lock) { away = true }
    }

    /**
     * J2: the screen came back from another one or from another app. In one job: the saved rows as they are now
     * ([requestRepublish]), the bump ([requestBump]) and last a full pass held while the list was away.
     */
    fun requestReturn() {
        markAt = SystemClock.uptimeMillis()
        val held = synchronized(lock) {
            republishPending = true
            bumpPending = true
            releaseHeld()
        }
        ListTiming.line(context) { "return; held full pass=$held" }
        publishQuickIfBusy()
        schedule()
    }

    /** J2: a new screen shows the list (a rotation while it was away); a full pass held meanwhile runs now. */
    fun requestShown() {
        if (synchronized(lock) { releaseHeld() }) schedule()
    }

    /** Under [lock]: the list is in sight; true when a held full pass is now pending. */
    private fun releaseHeld(): Boolean {
        away = false
        val held = fullHeld
        if (held) fullPending = true
        fullHeld = false
        return held
    }

    private fun schedule() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        try {
            executor.execute(::drainOrRelease)
        } catch (e: Exception) {
            // A shut-down executor (the screen is gone): nothing left to publish to.
            synchronized(lock) { running = false }
        }
    }

    /**
     * J2: while a job runs, the saved rows are shown at once from [quickExecutor] instead of after it. Reads the saved
     * rows only and writes nothing, so it cannot undo what the job writes; [publishLock] keeps the order.
     */
    private fun publishQuickIfBusy() {
        synchronized(lock) {
            if (!running || quickPending) return
            quickPending = true
        }
        try {
            quickExecutor.execute {
                synchronized(lock) { quickPending = false }
                step { publishSaved("quick") }
            }
        } catch (e: Exception) {
            synchronized(lock) { quickPending = false }
        }
    }

    /** PF-B7: whatever ends [drain] early (an Error), the next request must still be able to start a job. */
    private fun drainOrRelease() {
        var finished = false
        try {
            drain()
            finished = true
        } finally {
            if (!finished) synchronized(lock) { running = false }
        }
    }

    private class Round(
        val cached: Boolean,
        val full: Boolean,
        val bump: Boolean,
        val republish: Boolean,
        val threads: List<Long>,
    )

    private fun drain() {
        while (true) {
            runRound(takeRound() ?: return)
        }
    }

    /** Takes everything asked for so far as one job; null (and the sync is free again) when nothing is. */
    private fun takeRound(): Round? {
        synchronized(lock) {
            if (nothingPending()) {
                running = false
                return null
            }
            val round = Round(cachedPending, fullPending, bumpPending, republishPending, threadsPending.toList())
            cachedPending = false
            fullPending = false
            bumpPending = false
            republishPending = false
            threadsPending.clear()
            return round
        }
    }

    /**
     * J2: the cheap steps first, all from the saved rows, so the list shows at once; the full read of the phone's store
     * comes last. With a full pass in the same round, a chat without a saved row is left to it (no store read twice).
     */
    private fun runRound(round: Round) {
        val before = publishCount
        var shown: List<Conversation>? = null
        if (round.cached) step { shown = showCached() }
        step {
            if (round.threads.isNotEmpty()) {
                round.threads.forEach { refreshThread(it, readStore = !round.full) }
                publishSaved("thread")
            }
        }
        step { if (round.bump) bump() }
        step { if (round.republish && publishCount == before) publishSaved("republish") }
        step { shown?.let(::clearExpiredScheduled) }
        step { if (round.full) timedFullPass() }
    }

    private fun nothingPending() =
        !(cachedPending || fullPending || bumpPending || republishPending) && threadsPending.isEmpty()

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Conversation list refresh failed", e)
        } catch (e: OutOfMemoryError) {
            // PF-B7: a huge inbox must cost one refresh, not every later one.
            Log.w(TAG, "Conversation list refresh ran out of memory", e)
        }
    }

    /**
     * The Messages list (S1). Provider truth (B-4): always without the chats in Spam. Before the first sort the table
     * holds only rows of texts that arrived as spam; the sort's transaction is the publish, so the list never waits for
     * the runner's state. With the flag off: every chat that is not archived, as in 0.4.14.
     */
    private fun saved(): ArrayList<Conversation> {
        val db = context.conversationsDB
        return ArrayList(if (ProviderTruthFlag.enabled) db.getMessagesList() else db.getNonArchived())
    }

    private fun publishSaved(kind: String = "saved", cached: Boolean = false) {
        publishRows(kind, cached) { saved() }
    }

    /** Reads and hands on one list inside [publishLock]; [kind] only names the step in the timing line. */
    private inline fun publishRows(kind: String, cached: Boolean, read: () -> ArrayList<Conversation>) =
        synchronized(publishLock) {
            val rows = read()
            publish(rows, cached)
            publishCount++
            ListTiming.line(context) { "publish $kind: ${rows.size} rows, ${SystemClock.uptimeMillis() - markAt} ms" }
            rows
        }

    /** The saved rows at once; the scheduled clean-up runs later in the round ([clearExpiredScheduled]). */
    private fun showCached(): List<Conversation> = publishRows("cached", cached = true) {
        runCatching { saved() }.getOrDefault(ArrayList())
    }

    /**
     * J2: what [clearExpiredScheduledMessages] did for every chat of [conversations], with one read of the scheduled
     * messages instead of one per chat. Only a chat with scheduled messages, or a placeholder chat, has anything to do.
     */
    private fun clearExpiredScheduled(conversations: List<Conversation>) {
        val byThread = runCatching { context.messagesDB.getAllScheduledMessages() }.getOrNull()
            ?.groupBy { it.threadId } ?: return
        conversations.filter { it.isScheduled || it.threadId in byThread }.forEach {
            runCatching { context.clearExpiredScheduledMessages(it.threadId, byThread[it.threadId].orEmpty()) }
        }
    }

    private fun timedFullPass() {
        val startedAt = SystemClock.uptimeMillis()
        fullPass()
        ListTiming.line(context) { "full pass took ${SystemClock.uptimeMillis() - startedAt} ms" }
    }

    /**
     * The one row of [threadId]: from the saved list when a receiver stored it, else from the phone's store. Without
     * [readStore] a missing row is left to the full pass that follows.
     */
    private fun refreshThread(threadId: Long, readStore: Boolean = true) {
        val db = context.conversationsDB
        if (db.getConversationWithThreadId(threadId) != null || !readStore) return
        val fresh = context.getConversations(threadId = threadId).firstOrNull() ?: return
        val temporary = db.getAllRows().firstOrNull { it.isScheduled && it.phoneNumber == fresh.phoneNumber }
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
        // B2-7: the real thread's row is written as an update of the placeholder's, so it is sorted here.
        SpamSorting.requestThreads(context, listOf(real.threadId))
    }

    private fun fullPass() {
        val db = context.conversationsDB
        // S1: every cached row, Spam chats included, so a Spam chat is never taken for a new one and rewritten.
        val cachedBefore = db.getAllRows()
        val privateContacts = context.getPrivateContactsIfAvailable()
        val lookups = ConversationLookups(context, bulk = true)
        ArchiveStore.protectArchives(context)
        val archiveKnown = ArchiveStore.isTrusted(context)

        // Refresh the newest threads first so the top of the list is current right away. The full pass below still
        // runs: it is what notices deleted threads and keeps the rest of the list, Archived and search up to date.
        val cachedById = cachedBefore.associateBy { it.threadId }
        val newest = context.getConversations(privateContacts = privateContacts, limit = firstPassSize, lookups = lookups)
        val changed = newest.filter { fresh ->
            val cached = cachedById[fresh.threadId]
            cached == null || !sameRow(cached, fresh, archiveKnown)
        }
        if (changed.isNotEmpty()) {
            changed.forEach { writeIfStillSaved(it, cachedById[it.threadId]) }
            publishSaved("newest", cached = true)
        }
        // The rows just written are cached now: they are neither new below (checked and sorted twice) nor changed.
        val cachedConversations = ArrayList(if (changed.isEmpty()) cachedBefore else db.getAllRows())

        // A failed read (no cursor, provider error) would look like "every thread was deleted" below and wipe the
        // cached list. Keep the cache as it is and try again on the next refresh.
        val conversations = context.getConversationsOrNull(privateContacts = privateContacts, lookups = lookups)
        if (conversations == null) {
            publishSaved("unread store")
            return
        }
        val freshById = conversations.associateBy { it.threadId }
        // B2-1: when the read lists no chat at all, or most of a big cache vanished at once, the read is not trusted
        // to say "deleted": only the cached rows go, as before step B, and everything comes back at the next good read.
        val massVanish = ProviderTruthFlag.enabled && isMassVanish(cachedConversations, freshById)
        if (massVanish) Log.w(TAG, "Most cached chats are missing from the store read; nothing of them is forgotten")
        // The newest thread of a number wins, as the old first-match search did.
        val freshByNumber by lazy { conversations.asReversed().associateBy { it.phoneNumber } }

        val cachedIds = cachedConversations.mapTo(HashSet()) { it.threadId }
        val added = ArrayList<Conversation>()
        conversations.forEach { fresh ->
            if (cachedIds.add(fresh.threadId)) {
                if (!ProviderTruthFlag.enabled) db.insertOrUpdate(fresh)
                cachedConversations.add(fresh)
                added += fresh
            }
        }
        // 3.2, B-6: new chat rows are cached with their sort (and their senders' history first), never before it.
        if (ProviderTruthFlag.enabled) NewChatSorting.cacheAndSort(context, added)

        val storeThreads by lazy { if (ProviderTruthFlag.enabled && !massVanish) storeThreadIds() else null }
        cachedConversations.forEach { cached ->
            val threadId = cached.threadId
            val isTemporaryThread = cached.isScheduled
            if (threadId !in freshById && !isTemporaryThread) {
                forgetVanished(threadId, storeThreads)
            }
            if (isTemporaryThread) {
                freshByNumber[cached.phoneNumber]?.let { adoptTemporaryThread(cached, it) }
            }
        }

        cachedConversations.forEach { cached ->
            val conv = freshById[cached.threadId]?.takeIf { !sameRow(cached, it, archiveKnown) }
            if (conv != null) {
                // FIXME: Scheduled message date is being reset here. Conversations with scheduled messages will have
                //  their original date.
                writeIfStillSaved(conv, null, wasSaved = true)
            }
        }

        // Chats a restore archived before their rows existed (see PendingArchive).
        runCatching {
            PendingArchive(context).applyTo(db) { context.updateConversationArchivedStatus(it, true) }
        }
        // Messages a restore put back in the recycle bin once their cached copies exist (see PendingRecycled).
        runCatching { PendingRecycled(context).applyTo(context.messagesDB) }

        publishSaved("full")

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
     * B2-1: an empty but complete read, or one that misses more than [MASS_VANISH_MIN] of the [cached] chats (read
     * before this pass added any) and more than half of them: an OEM permission layer that answers with an empty
     * result, a store just rebuilt. Placeholder chats of scheduled messages are not in the store and do not count.
     */
    private fun isMassVanish(cached: List<Conversation>, fresh: Map<Long, Conversation>): Boolean {
        val stored = cached.filter { !it.isScheduled }
        if (stored.isEmpty()) return false
        if (fresh.isEmpty()) return true
        val vanished = stored.count { it.threadId !in fresh }
        return vanished > MASS_VANISH_MIN && vanished * 2 > stored.size
    }

    /**
     * A cached chat the full read did not list. S19 (provider truth): when the phone's store has no such thread any
     * more (another app deleted it), everything the app kept of it goes. Otherwise (the flag is off, the thread only
     * has no recipients, the store could not be read, or the read looks like a mass disappearance) only the cached
     * row goes, as before.
     */
    private fun forgetVanished(threadId: Long, storeThreads: Set<Long>?) {
        if (ProviderTruthFlag.enabled && storeThreads != null && threadId !in storeThreads) {
            context.forgetThread(threadId, vanished = true)
        } else {
            context.conversationsDB.deleteThreadId(threadId)
        }
    }

    /** Every thread id of the phone's store that has messages, whatever its recipients; null when unreadable. */
    private fun storeThreadIds(): Set<Long>? = runCatching {
        context.contentResolver.query(
            "${Threads.CONTENT_URI}?simple=true".toUri(),
            arrayOf(Threads._ID),
            "${Threads.MESSAGE_COUNT} > 0",
            null,
            null,
        )?.use { cursor ->
            val ids = HashSet<Long>()
            while (cursor.moveToNext()) ids += cursor.getLong(0)
            ids
        }
    }.getOrNull()

    private fun sameRow(cached: Conversation, fresh: Conversation, archiveKnown: Boolean) =
        Conversation.areContentsTheSame(old = cached, new = fresh, compareArchived = archiveKnown)

    /**
     * Writes a changed row read earlier, unless a delete that ran meanwhile already took the saved row away: the stale
     * read would bring the chat back until the next pass. A row that was never saved is added as before.
     */
    private fun writeIfStillSaved(fresh: Conversation, cached: Conversation?, wasSaved: Boolean = cached != null) {
        if (wasSaved && context.conversationsDB.getConversationWithThreadId(fresh.threadId) == null) return
        if (cached == null) {
            context.insertOrUpdateConversation(keepRead(fresh))
        } else {
            context.insertOrUpdateConversation(keepRead(fresh), cached)
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
        if (changed) publishSaved("bump")
    }

    private companion object {
        const val TAG = "ConversationListSync"

        /** B2-1: fewer vanished chats than this in one pass are always trusted (a few chats deleted elsewhere). */
        const val MASS_VANISH_MIN = 20
    }
}

/**
 * J2: content-free timing lines under [TAG], so the list can be measured on the emulator
 * (`adb logcat -s uMessageList`). Debug and daily builds only (their package ends in ".debug"), never a Play build;
 * [Log.w] because daily builds strip the lower levels. Step names, counts and milliseconds only: never a message
 * text, a name or a number.
 */
internal object ListTiming {
    const val TAG = "uMessageList"
    private const val DEBUG_SUFFIX = ".debug"

    @Volatile
    private var enabled: Boolean? = null

    fun line(context: Context, text: () -> String) {
        val on = enabled ?: (BuildConfig.DEBUG || context.packageName.endsWith(DEBUG_SUFFIX)).also { enabled = it }
        if (on) runCatching { Log.w(TAG, text()) }
    }

    /** Milliseconds since this process started: the cold-start clock. */
    fun sinceProcessStart(): Long = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
}
