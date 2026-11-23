package com.glooboo.umessage.spam

import android.content.Context
import android.util.Log
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.markThreadMessagesUnread
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.engine.SenderJudge
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.notificationManager
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What one [ChatSorter.recompute] did. */
sealed interface SortResult {
    /**
     * The table now equals the verdicts. [added] chats entered Spam, [removed] left it, [changed] stayed with a new
     * cause or a new written form of the sender. [touched] are the threads whose place changed; [leftWithUnread]
     * those that left Spam with spam texts not looked at (they are marked unread in Messages, B-14); [entered] those
     * that entered Spam (their Messages notification is taken away).
     */
    data class Applied(
        val added: Int,
        val removed: Int,
        val changed: Int,
        val touched: Set<Long> = emptySet(),
        val leftWithUnread: Set<Long> = emptySet(),
        val entered: Set<Long> = emptySet(),
    ) : SortResult {
        val anyChange: Boolean get() = added + removed + changed > 0
    }

    /** S11: a read failed (rules, members, contacts). Nothing was written: the old table stays. */
    data class Failed(val why: String) : SortResult
}

/**
 * Provider truth (3.2, S9, S11, R12): sorts the cached chats into Messages and Spam. Every one-to-one chat is judged
 * with [SenderJudge.Snapshot.verdict], the same answer a new text gets, so the table always equals the verdict; one
 * snapshot is read per rebuild. The difference to the stored table is applied in one transaction, never "delete all,
 * write again": a row that stays keeps its unread count and spam_since (R12), a new row starts at 0 unread.
 *
 * A failed read is a failed rebuild (S11): when the snapshot cannot be read (SenderJudge throws on a failed rules or
 * members read, F-3), or a contact lookup could not answer while contacts are readable, nothing is written. Group
 * chats always stay in Messages (R4). A thread that is not cached is never touched here, so a row written for a new
 * text before its chat row exists survives (S9 makes such a row count only once the chat is cached with that sender).
 * A chat that leaves Spam with unread spam texts is marked unread ([markUnread], B-14, as 3.4 says for "Not spam").
 */
class ChatSorter(
    private val db: MessagesDatabase,
    private val snapshot: () -> SenderJudge.Snapshot,
    private val contactsReadable: () -> Boolean,
    private val markUnread: (Long) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Sorts every cached chat, or only [threads] when given (a new chat row, a user action on one chat). [newRows]
     * are chat rows not cached yet: they are cached (insert-ignore, B-20) in the same transaction as their sort, so a
     * new row is never shown in Messages before it is sorted. Never throws: any failure is [SortResult.Failed] and
     * leaves the table, and the cache, as they were.
     */
    fun recompute(threads: Set<Long>? = null, newRows: List<Conversation> = emptyList()): SortResult {
        val result = runCatching {
            val dao = db.ConversationsDao()
            val cached = dao.getSortableChats()
            val cachedIds = cached.mapTo(HashSet()) { it.threadId }
            val extra = newRows.filter { it.threadId !in cachedIds }.distinctBy { it.threadId }
            val asked = threads?.let { it + extra.map { row -> row.threadId } }
            val fresh = extra.map { SortableChat(it.threadId, it.phoneNumber, it.isGroupConversation, it.isScheduled) }
            val chats = (cached + fresh).filter { asked == null || it.threadId in asked }
            if (chats.isEmpty()) return SortResult.Applied(0, 0, 0)
            val snap = snapshot()
            val wanted = wantedRows(chats, snap) ?: return SortResult.Failed("contacts")
            db.runInTransaction<SortResult.Applied> {
                extra.forEach { dao.insertOrIgnore(it) }
                apply(chats.map { it.threadId }, wanted, snap)
            }
        }.getOrElse { SortResult.Failed(it.javaClass.simpleName) }
        if (result is SortResult.Applied) result.leftWithUnread.forEach { runCatching { markUnread(it) } }
        return result
    }

    /**
     * G-B3, a dry run: how many cached chats a sort with [snap] would move between Messages and Spam (a chat shown in
     * Spam now, a valid row, that would leave it, or one that would enter it). A new cause or a new written form of the
     * sender moves nothing. Writes nothing. Null when a read failed, as [recompute] would fail.
     */
    fun countMoves(snap: SenderJudge.Snapshot): Int? = runCatching {
        val dao = db.ConversationsDao()
        val chats = dao.getSortableChats().distinctBy { it.threadId }
        val wanted = wantedRows(chats, snap) ?: return null
        val shown = dao.getValidSpamThreads().mapTo(HashSet()) { it.threadId }
        chats.count { (it.threadId in wanted) != (it.threadId in shown) }
    }.getOrNull()

    /** Thread id to its Spam row's sender and cause, for the chats that belong in Spam; null on a failed lookup. */
    private fun wantedRows(chats: List<SortableChat>, snap: SenderJudge.Snapshot): Map<Long, Wanted>? {
        val readable = runCatching { contactsReadable() }.getOrDefault(false)
        val verdicts = HashMap<String, SenderVerdict>()
        val wanted = HashMap<Long, Wanted>()
        for (chat in chats) {
            if (!chat.sortable) continue
            val verdict = verdicts.getOrPut(chat.phoneNumber) { snap.verdict(chat.phoneNumber) }
            // With the permission granted, "contacts unavailable" means a lookup that did not answer (S11).
            if (readable && verdict.reason == DecisionReason.CONTACTS_UNAVAILABLE) return null
            if (verdict is SenderVerdict.Spam) wanted[chat.threadId] = Wanted(chat.phoneNumber, verdict.cause)
        }
        return wanted
    }

    /** Inside the transaction: the stored rows of [judged] threads are read again and made equal to [wanted]. */
    private fun apply(judged: List<Long>, wanted: Map<Long, Wanted>, snap: SenderJudge.Snapshot): SortResult.Applied {
        val dao = db.ConversationsDao()
        val stored = dao.getSpamThreads().associateBy { it.threadId }
        var changed = 0
        val added = HashSet<Long>()
        val removed = HashSet<Long>()
        val unread = HashSet<Long>()
        val at = now().coerceAtLeast(1L)
        for (threadId in judged) {
            val want = wanted[threadId]
            val row = stored[threadId]
            when {
                want == null && row == null -> Unit
                want == null -> {
                    dao.deleteSpamThread(threadId)
                    removed += threadId
                    if ((row?.unread ?: 0) > 0) unread += threadId
                }
                row == null -> {
                    dao.insertSpamThread(SpamThread(threadId, want.sender, want.cause, spamSince = at))
                    added += threadId
                }
                row.sender == want.sender -> if (row.cause != want.cause) {
                    dao.updateSpamThreadCause(threadId, want.cause)
                    changed++
                }
                snap.readers.same(row.sender, want.sender) -> {
                    dao.updateSpamThreadSender(threadId, want.sender, want.cause)
                    changed++
                }
                else -> {
                    // S9: the row was another person's (a rebuilt store gave the id again): a new Spam entry.
                    dao.deleteSpamThread(threadId)
                    dao.insertSpamThread(SpamThread(threadId, want.sender, want.cause, spamSince = at))
                    added += threadId
                }
            }
        }
        return SortResult.Applied(added.size, removed.size, changed, added + removed, unread, entered = added)
    }

    private data class Wanted(val sender: String, val cause: SpamCause)

    companion object {
        @Volatile private var snapshots: SnapshotCache? = null

        /** The snapshot the sorter and the history check share (R-7). */
        fun snapshots(context: Context): SnapshotCache = snapshots ?: synchronized(this) {
            snapshots ?: run {
                val app = context.applicationContext
                SnapshotCache({ SpamEngine.filter(app).senderJudge.snapshot() }).also { snapshots = it }
            }
        }

        /**
         * The sorter of the app's own conversations.db, judging with [snapshots]. [fresh]: the kept snapshot is
         * dropped first, for a sort right after a fact was written (the history check's joins, a new text's fact)
         * whose change the triggers may not have seen yet.
         */
        fun forApp(context: Context, fresh: Boolean = false): ChatSorter {
            val app = context.applicationContext
            if (fresh) snapshots(app).invalidate()
            return ChatSorter(
                db = MessagesDatabase.getInstance(app),
                snapshot = { snapshots(app).get() },
                contactsReadable = { SpamEngine.contactChecker(app).canReadContacts() },
                markUnread = { app.markThreadMessagesUnread(it) },
            )
        }
    }
}

/**
 * Fable R-7: one [SenderJudge.Snapshot] kept until the rules, the group members or the settings change
 * ([invalidate]), or until it is [maxAgeMs] old (the SIM countries it froze may have changed). A failed read throws,
 * as [SenderJudge.snapshot] does, and nothing is kept.
 */
class SnapshotCache(
    private val build: () -> SenderJudge.Snapshot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    private val lock = Any()
    private var kept: SenderJudge.Snapshot? = null
    private var keptAt = 0L
    private var generation = 0L

    fun get(): SenderJudge.Snapshot {
        val startedAt: Long
        val startedGeneration: Long
        synchronized(lock) {
            val at = clock()
            kept?.let { if (at - keptAt in 0 until maxAgeMs) return it }
            startedAt = at
            startedGeneration = generation
        }
        val fresh = build()
        synchronized(lock) {
            // A change while it was read: use it once, keep nothing, so the next caller reads the change.
            if (generation == startedGeneration) {
                kept = fresh
                keptAt = startedAt
            }
        }
        return fresh
    }

    fun invalidate() = synchronized(lock) {
        generation++
        kept = null
    }

    private companion object {
        const val DEFAULT_MAX_AGE_MS = 60_000L
    }
}

/**
 * The app's sorting queue (step B): one background thread runs every queued sort and the first-run steps
 * ([FirstRun]), one at a time, so two never overlap. The queued sorts and their gate are [SortQueue].
 *
 * Triggers (3.2): rules (not their hit counts, B-3), group members or the filter switches changed ([install]); contacts
 * changed (App calls [requestFull] with [CONTACTS_DELAY_MS]); a new chat row was cached ([requestThreads],
 * [NewChatSorting]); app start ([requestFull]).
 */
object SpamSorting {
    private const val CHANGE_DELAY_MS = 300L

    /** S11: after a contacts change the sort waits this long, as the address book may still be written. */
    const val CONTACTS_DELAY_MS = 3_000L

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chat-sorter").apply { isDaemon = true }
    }

    @Volatile private var queue: SortQueue? = null

    @Volatile private var installed = false

    /** Grows by one each time the Spam table changed; a list screen can show its saved rows again (step C). */
    val changes: StateFlow<Long> = SpamChanges.changes

    /** Starts the triggers from the rules, the group members and the settings. Once per process. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val repo = SpamEngine.repository(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val snapshots = ChatSorter.snapshots(app)
        scope.launch {
            // B-3: a rule's hit count or last hit changes with every blocked text; that moves no chat.
            repo.rules.map { rules -> rules.map { it.copy(hitCount = 0, lastHit = 0L) } }
                .distinctUntilChanged().drop(1).collect {
                    snapshots.invalidate()
                    requestFull(app, CHANGE_DELAY_MS)
                }
        }
        scope.launch {
            repo.groupMembers.distinctUntilChanged().drop(1).collect {
                snapshots.invalidate()
                requestFull(app, CHANGE_DELAY_MS)
            }
        }
        scope.launch {
            var last: SpamSettings? = null
            repo.settings.collect { now ->
                val before = last
                last = now
                snapshots.invalidate()
                when {
                    before == null -> Unit
                    !before.filterOn && now.filterOn -> FirstRun.request(app, FirstRunTrigger.SWITCH_ON)
                    before.filterOn != now.filterOn || before.codeGroupOn != now.codeGroupOn ||
                        before.wroteGroupOn != now.wroteGroupOn -> requestFull(app, CHANGE_DELAY_MS)
                }
            }
        }
    }

    /** Every cached chat is sorted again, after [delayMs] (a later request moves the deadline later). */
    fun requestFull(context: Context, delayMs: Long = 0L) {
        queue(context).requestFull(delayMs)
    }

    /** These chats are sorted again (a new chat row was cached, a user action on one chat), as soon as possible. */
    fun requestThreads(context: Context, threadIds: Collection<Long>) {
        if (threadIds.isEmpty()) return
        queue(context).requestThreads(threadIds)
    }

    /** Runs [task] on the sorting thread, after the jobs queued before it. */
    internal fun execute(task: Runnable, delayMs: Long = 0L) {
        runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
    }

    /** On the sorting thread: one full sort, past the gate (the first run's own sort), caching [newRows] with it. */
    internal fun sortNow(context: Context, newRows: List<Conversation> = emptyList()): SortResult {
        val result = ChatSorter.forApp(context, fresh = true).recompute(newRows = newRows)
        published(context, result)
        return result
    }

    /** A first-run pass ended: the sorts that waited for it run now, as one full sort. */
    internal fun releaseGate(context: Context) = queue(context).releaseGate()

    private fun queue(context: Context): SortQueue = queue ?: synchronized(this) {
        queue ?: run {
            val app = context.applicationContext
            SortQueue(
                scheduler = { task, delayMs -> executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) },
                gate = { FirstRun.state(app).value },
                // A thread sort follows a fact written a moment ago (a new chat, a user action): a fresh snapshot.
                sort = { threads -> ChatSorter.forApp(app, fresh = threads != null).recompute(threads) },
                published = { published(app, it) },
            ).also { queue = it }
        }
    }

    /** After a sort that changed the table: the list screen shows its saved rows again, without reading the store. */
    internal fun published(context: Context, result: SortResult) {
        if (result !is SortResult.Applied || !result.anyChange) return
        SpamChanges.noteChanged()
        result.touched.firstOrNull()?.let { refreshConversations(it) }
        cancelEnteredNotifications(context, result)
    }

    /**
     * Lock privacy: a chat a sort moved into Spam on its own (a new rule, the filter switched on, a deleted contact,
     * the first sort) loses its Messages notification, which shows the sender, the text and Reply on the lock screen.
     * Only a chat still in Spam ([inSpam]): one a later sort took out ("Not spam", a contact added again) keeps the
     * notification of its new message. Off the main thread (it reads the Spam table), as every sort runs.
     */
    internal fun cancelEnteredNotifications(
        context: Context,
        result: SortResult,
        inSpam: (Long) -> Boolean = { inSpamTable(context, it) },
    ) {
        if (result !is SortResult.Applied) return
        result.entered.forEach { id ->
            runCatching { if (inSpam(id)) context.notificationManager.cancel(id.hashCode()) }
        }
    }

    private fun inSpamTable(context: Context, threadId: Long): Boolean =
        MessagesDatabase.getInstance(context).ConversationsDao().getSpamThread(threadId) != null
}

/**
 * The queued sorts of [SpamSorting]. Requests that arrive while a job waits are merged into one deadline (B-17): a
 * thread sort runs as soon as it can, a full sort waits for the latest debounce asked for. A failed sort is tried again
 * a little later, at most [MAX_RETRIES] times in a row (B-10).
 *
 * The gate ([gate]): before the first sort is published every queued sort waits, and while a first-run pass works
 * every full sort waits; they run as one full sort once the pass ends ([releaseGate]), so no chat moves before the sort
 * of 3.5. B2-4: once the first sort was published, a sort of single chats (a block, "Not spam", a new chat) also runs
 * while a later pass works or is stalled ([FirstRunState.allowsThreadSort]): a stalled pass must not freeze the
 * user's own actions.
 */
internal class SortQueue(
    /** Runs the task after the delay, on the one sorting thread. */
    private val scheduler: (task: Runnable, delayMs: Long) -> Future<*>,
    private val gate: () -> FirstRunState,
    /** Sorts these threads, or every cached chat when null. */
    private val sort: (threads: Set<Long>?) -> SortResult,
    private val published: (SortResult) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var fullPending = false
    private var missed = false
    private val threadsPending = HashSet<Long>()
    private var future: Future<*>? = null
    private var dueAt = 0L
    private var failures = 0

    fun requestFull(delayMs: Long = 0L) {
        synchronized(lock) { fullPending = true }
        schedule(delayMs, urgent = false)
    }

    fun requestThreads(threadIds: Collection<Long>) {
        if (threadIds.isEmpty()) return
        synchronized(lock) { threadsPending += threadIds }
        schedule(0L, urgent = true)
    }

    /** A first-run pass ended: the sorts that waited for it run now, as one full sort. */
    fun releaseGate() {
        val wasMissed = synchronized(lock) { missed.also { missed = false } }
        if (wasMissed) requestFull()
    }

    /** B-17: one deadline. A thread sort pulls it to the earliest time asked; a full sort alone pushes it later. */
    private fun schedule(delayMs: Long, urgent: Boolean) {
        synchronized(lock) {
            val now = clock()
            val wanted = now + delayMs
            val pending = future
            val due = when {
                pending == null || pending.isDone -> wanted
                urgent || threadsPending.isNotEmpty() -> minOf(dueAt, wanted)
                else -> maxOf(dueAt, wanted)
            }
            if (pending != null && !pending.isDone) {
                if (due == dueAt) return
                pending.cancel(false)
            }
            dueAt = due
            future = runCatching { scheduler(Runnable { drain() }, (due - now).coerceAtLeast(0L)) }.getOrNull()
        }
    }

    private fun drain() {
        val full: Boolean
        val threads: Set<Long>
        synchronized(lock) {
            future = null
            full = fullPending
            threads = HashSet(threadsPending)
            fullPending = false
            threadsPending.clear()
        }
        if (!full && threads.isEmpty()) return
        val allowed = allowed(full, threads)
        if (allowed?.isEmpty() == true) return
        val result = sort(allowed)
        if (result is SortResult.Failed) {
            Log.w(TAG, "Sort failed (${result.why}); the old table stays")
            val again = synchronized(lock) { (failures < MAX_RETRIES).also { if (it) failures++ } }
            if (again) requestFull(RETRY_MS)
        } else {
            synchronized(lock) { failures = 0 }
        }
        published(result)
    }

    /**
     * What may be sorted now: null for every chat, else these threads (empty: nothing). What may not run now waits
     * for the end of the pass, as one full sort.
     */
    private fun allowed(full: Boolean, threads: Set<Long>): Set<Long>? {
        val state = gate()
        if (full && state.allowsFullSort) return null
        val now = if (state.allowsThreadSort) threads else emptySet()
        if (full || now.size < threads.size) synchronized(lock) { missed = true }
        return now
    }

    companion object {
        private const val TAG = "uMessageSort"
        const val RETRY_MS = 30_000L
        const val MAX_RETRIES = 3
    }
}

/**
 * B-6: chat rows the list or a receiver caches for the first time, after the first sort was published. The senders'
 * history is checked first (a sender the user wrote to, or one with an old code, joins its group), then the rows are
 * cached and sorted in one transaction, so a new row never shows in Messages before its sort. B2-3: this also holds
 * while a later pass works, as a sort of single chats is let through then (B2-4). More than [BATCH] rows at once (a
 * restore, a store rebuilt by another app) are cached at once and get a history check and sort pass without a card,
 * which waits behind a running pass. Before the first sort is published the rows are cached as before and the first
 * run sorts them.
 */
object NewChatSorting {
    const val BATCH = 50

    fun cacheAndSort(context: Context, rows: List<Conversation>) {
        if (rows.isEmpty()) return
        forApp(context.applicationContext).cacheAndSort(rows)
    }

    private fun forApp(app: Context) = NewChatSorter(
        dao = MessagesDatabase.getInstance(app).ConversationsDao(),
        state = { FirstRun.state(app).value },
        checkHistory = { rows ->
            val history = HistoryCheck.forApp(app)
            rows.forEach { history.checkSender(it.phoneNumber, it.threadId) }
        },
        sorter = { ChatSorter.forApp(app, fresh = true) },
        requestThreads = { SpamSorting.requestThreads(app, it) },
        requestPass = { FirstRun.request(app, FirstRunTrigger.NEW_CHATS) },
        published = { SpamSorting.published(app, it) },
    )
}

/** What [NewChatSorting] does, on the stores and queues it is given. */
internal class NewChatSorter(
    private val dao: ConversationsDao,
    private val state: () -> FirstRunState,
    /** The history check of these one-to-one chats' senders. */
    private val checkHistory: (List<Conversation>) -> Unit,
    private val sorter: () -> ChatSorter,
    private val requestThreads: (Collection<Long>) -> Unit,
    /** A history check and sort pass (NEW_CHATS); it runs after a pass that works now. */
    private val requestPass: () -> Unit,
    private val published: (SortResult) -> Unit,
) {
    fun cacheAndSort(rows: List<Conversation>) {
        if (rows.isEmpty()) return
        val ids = rows.map { it.threadId }
        val ready = state().sortPublished
        if (!ready || rows.size > NewChatSorting.BATCH) {
            rows.forEach { dao.insertOrUpdate(it) }
            if (ready) requestPass() else requestThreads(ids)
            return
        }
        checkHistory(rows.filter { !it.isGroupConversation && !it.isScheduled })
        val result = sorter().recompute(ids.toSet(), newRows = rows)
        if (result is SortResult.Failed) {
            // The rows are cached anyway (a failed sort must not lose a chat); the queue tries the sort again.
            rows.forEach { dao.insertOrIgnore(it) }
            requestThreads(ids)
        } else {
            published(result)
        }
    }
}
