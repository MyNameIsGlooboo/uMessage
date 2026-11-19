package com.glooboo.umessage.spam

import android.content.Context
import android.util.Log
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.spam.engine.SenderJudge
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What one [ChatSorter.recompute] did. */
sealed interface SortResult {
    /**
     * The table now equals the verdicts. [added] chats entered Spam (the "N chats are now in Spam" card counts them),
     * [removed] left it, [changed] stayed with a new cause or a new written form of the sender. [touched] are the
     * threads whose place changed.
     */
    data class Applied(
        val added: Int,
        val removed: Int,
        val changed: Int,
        val touched: Set<Long> = emptySet(),
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
 */
class ChatSorter(
    private val db: MessagesDatabase,
    private val snapshot: () -> SenderJudge.Snapshot,
    private val contactsReadable: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Sorts every cached chat, or only [threads] when given (a new chat row, a user action on one chat). Never throws:
     * any failure is [SortResult.Failed] and leaves the table as it was.
     */
    fun recompute(threads: Set<Long>? = null): SortResult {
        return runCatching {
            val chats = db.ConversationsDao().getSortableChats().filter { threads == null || it.threadId in threads }
            if (chats.isEmpty()) return SortResult.Applied(0, 0, 0)
            val snap = snapshot()
            val wanted = wantedRows(chats, snap) ?: return SortResult.Failed("contacts")
            db.runInTransaction<SortResult.Applied> { apply(chats.map { it.threadId }, wanted, snap) }
        }.getOrElse { SortResult.Failed(it.javaClass.simpleName) }
    }

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
        var added = 0
        var removed = 0
        var changed = 0
        val touched = HashSet<Long>()
        val at = now().coerceAtLeast(1L)
        for (threadId in judged) {
            val want = wanted[threadId]
            val row = stored[threadId]
            when {
                want == null && row == null -> Unit
                want == null -> {
                    dao.deleteSpamThread(threadId)
                    removed++
                    touched += threadId
                }
                row == null -> {
                    dao.insertSpamThread(SpamThread(threadId, want.sender, want.cause, spamSince = at))
                    added++
                    touched += threadId
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
                    added++
                    touched += threadId
                }
            }
        }
        return SortResult.Applied(added, removed, changed, touched)
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
 * The app's sorting queue (step B): one background thread runs every sort and the first-run steps ([FirstRun]), one at
 * a time, so two never overlap. Requests that arrive while a job waits are merged. Nothing here runs while
 * [ProviderTruthFlag] is off.
 *
 * Triggers (3.2): rules, group members or the filter switches changed ([install]); contacts changed (App calls
 * [requestFull] with [CONTACTS_DELAY_MS]); a new chat row was cached ([requestThreads]); app start ([requestFull]).
 * Before the first sort is published, and while a first-run pass works, a full sort waits: it runs once the pass ends
 * ([FirstRunState.allowsFullSort], [releaseGate]), so no chat moves before the sort of 3.5.
 */
object SpamSorting {
    private const val TAG = "uMessageSort"
    private const val CHANGE_DELAY_MS = 300L

    /** S11: after a contacts change the sort waits this long, as the address book may still be written. */
    const val CONTACTS_DELAY_MS = 3_000L

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chat-sorter").apply { isDaemon = true }
    }
    private val lock = Any()
    private var fullPending = false
    private var missed = false
    private var scheduled = false
    private val threadsPending = HashSet<Long>()

    @Volatile private var installed = false

    private val changeCount = MutableStateFlow(0L)

    /** Grows by one each time the Spam table changed; a list screen can show its saved rows again (step C). */
    val changes: StateFlow<Long> = changeCount.asStateFlow()

    /** Starts the triggers from the rules, the group members and the settings. Once per process; flag on only. */
    fun install(context: Context) {
        if (!ProviderTruthFlag.enabled || installed) return
        installed = true
        val app = context.applicationContext
        val repo = SpamEngine.repository(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val snapshots = ChatSorter.snapshots(app)
        scope.launch {
            repo.rules.drop(1).collect {
                snapshots.invalidate()
                requestFull(app, CHANGE_DELAY_MS)
            }
        }
        scope.launch {
            repo.groupMembers.drop(1).collect {
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

    /** Every cached chat is sorted again, after [delayMs]. */
    fun requestFull(context: Context, delayMs: Long = 0L) {
        if (!ProviderTruthFlag.enabled) return
        synchronized(lock) { fullPending = true }
        schedule(context.applicationContext, delayMs)
    }

    /** These chats are sorted again (a new chat row was cached, a user action on one chat). */
    fun requestThreads(context: Context, threadIds: Collection<Long>) {
        if (!ProviderTruthFlag.enabled || threadIds.isEmpty()) return
        synchronized(lock) { threadsPending += threadIds }
        schedule(context.applicationContext, 0L)
    }

    /** Runs [task] on the sorting thread, after the jobs queued before it. */
    internal fun execute(task: Runnable, delayMs: Long = 0L) {
        runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
    }

    /** On the sorting thread: one full sort, past the gate (the first run's own sort). */
    internal fun sortNow(context: Context): SortResult {
        val result = ChatSorter.forApp(context, fresh = true).recompute()
        published(result)
        return result
    }

    /** A first-run pass ended: a full sort that waited for it runs now. */
    internal fun releaseGate(context: Context) {
        val wasMissed = synchronized(lock) { missed.also { missed = false } }
        if (wasMissed) requestFull(context)
    }

    private fun schedule(app: Context, delayMs: Long) {
        synchronized(lock) {
            if (scheduled) return
            scheduled = true
        }
        execute(Runnable { drain(app) }, delayMs)
    }

    private fun drain(app: Context) {
        val full: Boolean
        val threads: Set<Long>
        synchronized(lock) {
            scheduled = false
            full = fullPending
            threads = HashSet(threadsPending)
            fullPending = false
            threadsPending.clear()
        }
        val state = FirstRun.state(app).value
        val allowed = if (full) state.allowsFullSort else state.sortPublished
        if (!allowed) {
            synchronized(lock) { missed = true }
            return
        }
        val result = ChatSorter.forApp(app, fresh = !full).recompute(if (full) null else threads)
        if (result is SortResult.Failed) Log.w(TAG, "Sort failed (${result.why}); the old table stays")
        published(result)
    }

    private fun published(result: SortResult) {
        if (result !is SortResult.Applied || !result.anyChange) return
        changeCount.value++
        // A saved row of a changed chat: the list screen shows its saved rows again, without reading the store.
        result.touched.firstOrNull()?.let { refreshConversations(it) }
    }
}
