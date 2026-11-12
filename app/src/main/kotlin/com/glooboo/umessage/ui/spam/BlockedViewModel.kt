package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.glooboo.umessage.helpers.refreshConversations
import kotlinx.coroutines.withContext
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode

/**
 * All blocked messages of one sender, newest first. The sender address is the group key: the newest message's address
 * as it was written. Messages written in another form of the same number (I20) share the group, so a screen that
 * was given any of those addresses still finds it with [hasAddress].
 */
data class SenderGroup(val address: String, val messages: List<BlockedMessage>) {
    val latest: BlockedMessage get() = messages.first()

    /** How many of the sender's held messages were not opened yet. */
    val unreadCount: Int get() = messages.count { it.unread }

    /** Any held message is unread: the row is drawn bold like an unread chat on the Messages tab. */
    val unread: Boolean get() = unreadCount > 0

    /** Whether [other] is this group's key or the address of one of its messages. */
    fun hasAddress(other: String): Boolean = address == other || messages.any { it.address == other }
}

/** What a "Not spam" from a blocked chat did: how many messages reached the inbox, so the screen can open the chat. */
data class NotSpamDone(val addresses: List<String>, val toInbox: Int, val allowed: List<String> = emptyList())

data class BlockedUiState(
    val loading: Boolean = true,
    val groups: List<SenderGroup> = emptyList(),
    val stats: SpamStats = SpamStats(0, 0, 0),
    val selected: Set<String> = emptySet(),
    val sims: List<SimInfo> = emptyList(),
    /** Current unknown-sender mode, shown on the stats card with a shortcut to the spam settings. */
    val mode: UnknownSenderMode = UnknownSenderMode.SMART,
    /** Rules by id, so each blocked message can say which rule blocked it (SU-22). */
    val rules: Map<Long, SpamRule> = emptyMap(),
    /** Blocked messages older than this many days are deleted; 0 keeps them (SU-23). */
    val purgeAfterDays: Int = 30,
    /** The chip chosen under the banner (ID-14); [groups] only holds what it lets through. */
    val filter: BlockedFilter = BlockedFilter.All,
    /** The text typed in the search box (spamui-20); [groups] only holds the senders that answer it. */
    val query: String = "",
    /** How many senders each chip would list, over all held messages (rows, not messages). */
    val filterCounts: Map<BlockedFilter, Int> = emptyMap(),
    /** All held messages, whatever the filter: zero means the tab is truly empty. */
    val heldCount: Int = groups.sumOf { it.messages.size },
    /** Messages moved to the inbox lately, newest first, behind the "N restored" link (BR-10). */
    val restored: List<RestoredEntry> = emptyList(),
    /**
     * A restore or a delete is still inside its undo window. The sender thread stays open while it is, so its Undo
     * snackbar is not lost with the screen.
     */
    val hasPendingUndo: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

sealed interface BlockedEvent {
    /**
     * Shown with an Undo action. The messages are in the inbox already (and the allow rules stored for [senders],
     * when [allowed] > 0); Undo takes both back. [count] messages went to the inbox; [mediaLost] of the picture
     * messages among all restored ones lost their pictures (never downloaded, download failed or expired), which
     * the snackbar says plainly (SU-17). Picture messages with neither pictures nor text are not in [count]:
     * nothing of them reached the inbox.
     */
    data class Restored(
        val token: Long,
        val count: Int,
        val allowed: Int = 0,
        val mediaLost: Int = 0,
        val senders: List<String> = emptyList(),
    ) : BlockedEvent

    /** [count] kept picture messages could not be put in the inbox. They are still in Blocked. */
    data class RestoreFailed(val count: Int) : BlockedEvent

    /** Shown with an Undo action; the deletion becomes final unless the user chooses Undo. */
    data class Deleted(val token: Long, val count: Int) : BlockedEvent
    data object Failed : BlockedEvent

    /** The unknown-sender mode was changed from this screen; Undo puts [previous] back (P1-*). */
    data class ModeChanged(val previous: UnknownSenderMode, val mode: UnknownSenderMode) : BlockedEvent
}

/**
 * Groups messages by sender; groups and their messages are sorted newest first. [keyOf] says which addresses are one
 * sender (I20); the group is named by its newest message's address.
 */
fun groupBySender(messages: List<BlockedMessage>, keyOf: (String) -> String = { it }): List<SenderGroup> = messages
    .groupBy { keyOf(it.address) }
    .map { (_, list) ->
        val newestFirst = list.sortedByDescending { it.receivedAt }
        SenderGroup(newestFirst.first().address, newestFirst)
    }
    .sortedByDescending { it.latest.receivedAt }

/**
 * The Blocked tab and the sender thread. [ignoreFilter] is set for the thread: it shows (and acts on) every held
 * message of its sender, whatever chip the tab has chosen.
 */
class BlockedViewModel(
    application: Application,
    private val repository: SpamRepository,
    private val restoredLog: RestoredLog = RestoredLog.get(application),
    private val ignoreFilter: Boolean = false,
) : AndroidViewModel(application) {

    private val senderKeys = SenderKeys.forDevice(application)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val sims = MutableStateFlow<List<SimInfo>>(emptyList())

    /** The search box's text (spamui-20). Kept while the screen is alive, not stored; the thread ignores it. */
    private val query = MutableStateFlow("")

    /** The chip selection survives leaving the screen; a failing preferences read just means "All". */
    private val filter = MutableStateFlow(
        runCatching {
            BlockedFilters.decode(application.getSharedPreferences(FILTER_PREFS, 0).getString(FILTER_KEY, null))
        }.getOrDefault(BlockedFilter.All),
    )

    /** The ids hidden while an undo window is open, and whether any window is open. */
    private data class Pending(val ids: List<Long>, val any: Boolean)

    private data class Aux(
        val settings: SpamSettings,
        val rules: List<SpamRule>,
        val filter: BlockedFilter,
        val restored: List<RestoredEntry>,
    )

    /** Deletions waiting for the undo window to close, by token. Hidden from the list meanwhile. */
    private val pendingDeletes = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())

    /**
     * "Not spam" and "Release once" actions that can still be undone, by token. The messages are put in the inbox
     * at once ([startRestore]); their rows stay hidden until Undo puts them back or the undo window closes.
     */
    private val pendingRestores = MutableStateFlow<Map<Long, PendingRestore>>(emptyMap())

    /** Tokens of "Delete all" deletions that also clear the restored list once they are final. */
    private val clearLogTokens = mutableSetOf<Long>()
    private var nextToken = 1L

    /** Restores being committed or undone, so the snackbar and the undo-window timer cannot run one twice. */
    private val committing = mutableSetOf<Long>()

    /** Restoring, allowing, undoing and committing run one at a time, so Undo always sees what it reverses. */
    private val restoreWork = Mutex()

    /** Set when the screen is gone for good: a restore that finishes later then cleans up after itself. */
    @Volatile private var cleared = false

    /**
     * [allowAddresses] are the senders whose allow rules go with the restore: none for "Release once" and none for
     * names anyone can fake. [receipts] is what the repository did with each message, null while it is still at
     * work; the Undo snackbar ([announced]) waits for them. A [silent] restore comes from a blocked chat: its rows
     * stay on screen while it runs, it shows no Undo snackbar, and the screen opens the normal chat instead.
     */
    private data class PendingRestore(
        val ids: List<Long>,
        val addresses: List<String>,
        val allowAddresses: List<String> = emptyList(),
        val silent: Boolean = false,
        /** Sender and arrival time by id, for the restored list; the rows are gone once restored. */
        val messages: Map<Long, BlockedMessage> = emptyMap(),
        val receipts: Map<Long, RestoreReceipt>? = null,
        /** The restored-list entries written for this restore, so Undo can take them out again. */
        val logged: List<RestoredEntry> = emptyList(),
        /** The rules the restore added, so Undo can remove them; null when none were added. */
        val allowChange: SenderBlocking.AllowChange? = null,
        val announced: Boolean = false,
    ) {
        private fun count(vararg outcomes: RestoreOutcome) = receipts.orEmpty().values.count { it.outcome in outcomes }

        val toInbox: Int get() = count(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val mediaLost: Int get() = count(RestoreOutcome.TEXT_ONLY, RestoreOutcome.NOTHING)

        /** Kept picture messages the inbox did not take. They are still in Blocked. */
        val failed: Int
            get() = receipts.orEmpty().values.count { it.outcome == RestoreOutcome.FAILED && it.message != null }

        /** All rows while the restore runs; afterwards a message that could not be restored shows again. */
        val claimed: List<Long>
            get() = receipts?.let { r -> ids.filter { r[it]?.outcome != RestoreOutcome.FAILED } } ?: ids

        /** The rows hidden from the list: a silent restore keeps its rows until it is done. */
        val hidden: List<Long> get() = if (silent && receipts == null) emptyList() else claimed

        val ready: Boolean get() = !announced && receipts != null

        fun event(token: Long): BlockedEvent.Restored {
            val allowed = if (allowChange != null) allowAddresses else emptyList()
            return BlockedEvent.Restored(token, toInbox, allowed.size, mediaLost, allowed)
        }
    }

    /** What the repository and the rules did for one restore. */
    private class RestoreWork(
        val receipts: Map<Long, RestoreReceipt>,
        val logged: List<RestoredEntry>,
        val change: SenderBlocking.AllowChange?,
    )

    private val events = Channel<BlockedEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val doneEvents = Channel<NotSpamDone>(Channel.BUFFERED)

    /** One entry for every "Not spam" started with `openChat`, once its messages are in the inbox (or failed to be). */
    val notSpamDone = doneEvents.receiveAsFlow()

    val state: StateFlow<BlockedUiState> = combine(
        repository.blocked,
        repository.stats,
        combine(repository.settings, repository.rules, filter, restoredLog.entries, ::Aux),
        combine(pendingDeletes, pendingRestores) { deletes, restores ->
            Pending(
                ids = deletes.values.flatten() + restores.values.flatMap { it.hidden },
                any = deletes.isNotEmpty() || restores.isNotEmpty(),
            )
        },
        combine(selected, sims, query) { s, i, q -> Triple(s, i, q) },
    ) { blocked, stats, aux, pending, (sel, simList, typed) ->
        val hidden = pending.ids.toSet()
        val held = blocked.filter { it.id !in hidden }
        // A SIM chip is only offered with two SIMs; a stored SIM choice that is gone falls back to All.
        val simIds = if (simList.size >= 2) simList.map { it.subscriptionId } else emptyList()
        val chosen = aux.filter
        val active = when {
            ignoreFilter -> BlockedFilter.All
            chosen is BlockedFilter.Sim && chosen.subscriptionId !in simIds -> BlockedFilter.All
            else -> chosen
        }
        val found = groupBySender(BlockedFilters.apply(active, held), senderKeys::keyOf)
        val groups = if (ignoreFilter) found else BlockedFilters.searchGroups(found, typed)
        val keys = groups.map { it.address }.toSet()
        BlockedUiState(
            loading = false,
            groups = groups,
            filter = active,
            query = if (ignoreFilter) "" else typed,
            filterCounts = BlockedFilters.counts(held, simIds, senderKeys::keyOf),
            heldCount = held.size,
            stats = stats,
            selected = sel intersect keys,
            sims = simList,
            mode = aux.settings.mode,
            rules = aux.rules.associateBy { it.id },
            purgeAfterDays = aux.settings.purgeAfterDays,
            restored = aux.restored,
            hasPendingUndo = pending.any,
        )
    }.flowOn(Dispatchers.Default) // grouping, filtering and the link checks stay off the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockedUiState())

    init {
        viewModelScope.launch {
            sims.value = withContext(Dispatchers.IO) { SimDirectory.load(getApplication()) }
        }
        viewModelScope.launch {
            runCatching { repository.purgeExpired() }
            // The restored list goes with the blocked messages the purge deletes (spamui-15): its entries live no
            // longer than the purge setting, and 30 days at most.
            runCatching {
                val days = repository.settings.first().purgeAfterDays
                if (days > 0) restoredLog.dropOlderThan(days)
            }
        }
    }

    /**
     * Changes the unknown-sender mode through the same repository call Spam settings uses, then offers Undo. The
     * caller confirms before [UnknownSenderMode.OFF].
     */
    fun setMode(mode: UnknownSenderMode) {
        val previous = state.value.mode
        if (mode == previous) return
        viewModelScope.launch {
            runCatching { repository.updateSettings { it.copy(mode = mode) } }
                .onSuccess { events.trySend(BlockedEvent.ModeChanged(previous, mode)) }
                .onFailure { events.trySend(BlockedEvent.Failed) }
        }
    }

    /** Undo of [setMode]: puts the old mode back without another snackbar. */
    fun undoMode(previous: UnknownSenderMode) {
        viewModelScope.launch {
            runCatching { repository.updateSettings { it.copy(mode = previous) } }
                .onFailure { events.trySend(BlockedEvent.Failed) }
        }
    }

    /** The search box changed (spamui-20): the list keeps the senders whose messages or number hold [text]. */
    fun setQuery(text: String) {
        if (ignoreFilter || query.value == text) return
        query.value = text
    }

    /** Picks a chip under the banner (ID-14) and remembers it. */
    fun setFilter(value: BlockedFilter) {
        if (ignoreFilter || filter.value == value) return
        filter.value = value
        selected.value = emptySet()
        runCatching {
            getApplication<Application>().getSharedPreferences(FILTER_PREFS, 0)
                .edit().putString(FILTER_KEY, BlockedFilters.encode(value)).apply()
        }
    }

    /**
     * "Release once" (ID-16): puts this one message in the inbox without allowing the sender. It is the restore
     * "Not spam" does, minus the allow rule, with the same Undo.
     */
    fun releaseOnce(messageId: Long) = releaseMessages(setOf(messageId))

    /**
     * "Move to inbox" for the messages chosen in the sender thread (spamui-11): one restore for all of them, without
     * allowing the sender, with a single Undo.
     */
    fun releaseMessages(messageIds: Set<Long>) {
        // A double tap can arrive before the state hides the message; the pending list is the up-to-date one (L4).
        val pending = pendingIds()
        val messages = state.value.groups.flatMap { it.messages }
            .filter { it.id in messageIds && it.id !in pending }
        if (messages.isEmpty()) return
        val token = nextToken++
        val restore = PendingRestore(
            messages.map { it.id }, emptyList(), messages = messages.associateBy { it.id },
        )
        pendingRestores.update { it + (token to restore) }
        startRestore(token)
    }

    /**
     * The sender's thread is open: its held messages count as read, so the row on the tab is no longer bold. Only
     * messages still unread are written, and the state follows from the repository's flow.
     */
    fun markRead(address: String) {
        val ids = state.value.groups.filter { it.hasAddress(address) }
            .flatMap { g -> g.messages.filter { it.unread }.map { it.id } }
        if (ids.isEmpty()) return
        viewModelScope.launch { runCatching { repository.markBlockedRead(ids) } }
    }

    fun toggleSelected(address: String) = selected.update { if (address in it) it - address else it + address }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll() { selected.value = state.value.groups.map { it.address }.toSet() }

    /**
     * "Not spam": in one step, puts the senders' messages in the inbox and stores an allow rule for each sender
     * (a number rule, or a sender-id rule), then offers one Undo for both. Senders whose names anyone can fake get no
     * rule: their messages only move. Nothing is asked. With [openChat] the rows stay until the work is done, no
     * snackbar is shown, and [notSpamDone] tells the blocked chat to open the normal chat. Returns false when there
     * was nothing to move (all of it is already on its way).
     */
    fun notSpam(addresses: Set<String>, openChat: Boolean = false): Boolean {
        val groups = state.value.groups.filter { g -> addresses.any(g::hasAddress) }
        // Messages already waiting on a restore are left out, so a second swipe cannot queue them twice (L4).
        val pending = pendingIds()
        val messages = groups.flatMap { it.messages }.filter { it.id !in pending }
        val ids = messages.map { it.id }
        if (ids.isEmpty()) return false
        val token = nextToken++
        val senders = groups.map { it.address }
        val restore = PendingRestore(
            ids, senders, senders.filterNot(SenderBlocking::isSpoofable), openChat,
            messages = messages.associateBy { it.id },
        )
        pendingRestores.update { it + (token to restore) }
        selected.value = emptySet()
        startRestore(token)
        return true
    }

    /**
     * Puts the messages in the inbox now, with the allow rules. Waiting for the undo window (as this once did) kept
     * the restore only in memory, so closing the app within it lost it. The repository restores each message
     * crash-safely, and the work is not cancelled with the screen; Undo reverses it instead.
     */
    private fun startRestore(token: Long) {
        val pending = pendingRestores.value[token] ?: return
        val messages = pending.ids.mapNotNull { pending.messages[it] }
        viewModelScope.launch {
            withContext(NonCancellable) {
                val work = restoreWork.withLock { restoreAndAllow(messages, pending.allowAddresses) }
                // The chat list (MainActivity) reloads on this event, so the restored text shows without a refresh.
                refreshConversations()
                if (cleared) {
                    finishInBackground(work.receipts.values)
                } else {
                    updateRestore(token) {
                        it.copy(receipts = work.receipts, logged = work.logged, allowChange = work.change)
                    }
                    announceIfReady(token)
                }
            }
        }
    }

    /**
     * "Always allow" of the Why-blocked dialog (spamui-21): stores an allow rule for [address] through the same call
     * "Not spam" uses (the sender's own block rules go, as they would be dead weight), without moving any message.
     * The snackbar is the allow-only line of [BlockedEvent.Restored], and its Undo removes the rule again.
     */
    fun alwaysAllow(address: String) {
        if (SenderBlocking.isSpoofable(address)) return
        val token = nextToken++
        viewModelScope.launch {
            withContext(NonCancellable) {
                restoreWork.withLock {
                    val change = runCatching { SenderBlocking.alwaysAllow(repository, listOf(address)) }.getOrNull()
                    if (change == null) {
                        events.trySend(BlockedEvent.Failed)
                    } else {
                        val pending = PendingRestore(
                            ids = emptyList(), addresses = listOf(address), allowAddresses = listOf(address),
                            receipts = emptyMap(), allowChange = change, announced = true,
                        )
                        pendingRestores.update { it + (token to pending) }
                        events.trySend(pending.event(token))
                    }
                }
            }
            if (pendingRestores.value.containsKey(token)) {
                delay(UNDO_WINDOW_MS)
                commitRestore(token)
            }
        }
    }

    /** The restore and its allow rules as one piece of work; no rule is added when nothing reached the inbox. */
    private suspend fun restoreAndAllow(messages: List<BlockedMessage>, allow: List<String>): RestoreWork {
        val (receipts, logged) = restore(messages)
        val inInbox = setOf(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val moved = receipts.values.any { it.outcome in inInbox }
        val change = if (allow.isEmpty() || !moved) {
            null
        } else {
            runCatching { SenderBlocking.alwaysAllow(repository, allow) }
                .onFailure { events.trySend(BlockedEvent.Failed) }
                .getOrNull()
        }
        return RestoreWork(receipts, logged, change)
    }

    private suspend fun restore(messages: List<BlockedMessage>): Pair<Map<Long, RestoreReceipt>, List<RestoredEntry>> {
        var error = false
        val receipts = messages.associate { message ->
            message.id to runCatching { repository.restore(message.id) }.getOrElse {
                error = true
                RestoreReceipt(null, RestoreOutcome.FAILED)
            }
        }
        if (error) events.trySend(BlockedEvent.Failed)
        val now = System.currentTimeMillis()
        val inInbox = setOf(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val logged = messages
            .filter { receipts[it.id]?.outcome in inInbox }
            .map { RestoredEntry(it.address, it.receivedAt, now) }
        restoredLog.record(logged)
        return receipts to logged
    }

    /** Shows the Undo snackbar once the restore is done, and starts the undo window. */
    private fun announceIfReady(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.ready } ?: return
        if (pending.silent) {
            val allowed = if (pending.allowChange != null) pending.allowAddresses else emptyList()
            doneEvents.trySend(NotSpamDone(pending.addresses, pending.toInbox, allowed))
        }
        if (pending.silent && pending.toInbox > 0) {
            // The blocked chat opens the normal chat: nothing here to undo, so the restore is final right away.
            pendingRestores.update { it - token }
            finishInBackground(pending.receipts.orEmpty().values)
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            return
        }
        if (pending.toInbox == 0 && pending.mediaLost == 0 && pending.allowChange == null) {
            // Nothing left Blocked and no rule was added: there is nothing to undo, only a failure to report.
            pendingRestores.update { it - token }
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            return
        }
        updateRestore(token) { it.copy(announced = true) }
        events.trySend(pending.event(token))
        // The snackbar normally ends the undo window, but it is cancelled unanswered when the user leaves the tab.
        // Close the window on our own once it has passed.
        viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitRestore(token)
        }
    }

    /** Ends the undo window of finished restores on a scope of their own, so closing the screen cannot cancel it. */
    private fun finishInBackground(receipts: Collection<RestoreReceipt>) {
        if (receipts.isEmpty()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            receipts.forEach { runCatching { repository.finishRestore(it) } }
        }
    }

    /** Undo: takes the messages back out of the inbox into Blocked, and removes the allow rules the restore added. */
    fun undoRestore(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.announced } ?: return
        if (!committing.add(token)) return
        viewModelScope.launch {
            val undone = withContext(NonCancellable) { restoreWork.withLock { undo(pending) } }
            if (!undone) events.trySend(BlockedEvent.Failed)
            pendingRestores.update { it - token }
            committing -= token
        }
    }

    /** False when something could not be taken back; what could not stays restored, so nothing is lost. */
    private suspend fun undo(pending: PendingRestore): Boolean {
        val receipts = pending.receipts.orEmpty().values
            .filter { it.outcome != RestoreOutcome.FAILED && it.outcome != RestoreOutcome.NOT_FOUND }
        val (back, stayed) = receipts.partition { runCatching { repository.undoRestore(it) }.getOrDefault(false) }
        val rulesBack = pending.allowChange
            ?.let { runCatching { SenderBlocking.undoAllow(repository, it) }.isSuccess } ?: true
        // Messages still in the inbox stay listed as restored, and their kept files are no longer needed.
        val returned = back.mapNotNull { it.message }.map { it.address to it.receivedAt }.toSet()
        restoredLog.remove(pending.logged.filter { (it.address to it.receivedAt) in returned })
        stayed.forEach { runCatching { repository.finishRestore(it) } }
        refreshConversations()
        return stayed.isEmpty() && rulesBack
    }

    /** The undo window closed: the restore and the rules stay, and the kept files the inbox copy has go. */
    fun commitRestore(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.announced } ?: return
        if (!committing.add(token)) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                restoreWork.withLock {
                    pending.receipts.orEmpty().values.forEach { runCatching { repository.finishRestore(it) } }
                }
            }
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            pendingRestores.update { it - token }
            committing -= token
        }
    }

    private fun updateRestore(token: Long, change: (PendingRestore) -> PendingRestore) =
        pendingRestores.update { map -> map[token]?.let { map + (token to change(it)) } ?: map }

    /** Hides the messages now and deletes them for real once the undo snackbar goes away. */
    fun delete(addresses: Set<String>) {
        hideAndDelete(idsOf(addresses))
        selected.value = emptySet()
    }

    /**
     * "Delete all": deletes every held message. When that really empties Blocked, the restored list is cleared with
     * them once the deletion is final (spamui-15), so no sender numbers stay behind.
     */
    fun deleteAll() {
        val ids = idsOf(state.value.groups.map { it.address }.toSet())
        hideAndDelete(ids, clearsRestoredLog = ids.size >= state.value.heldCount)
        selected.value = emptySet()
    }

    /** Delete of single messages from the sender thread, with the same Undo as [delete]. */
    fun deleteMessages(messageIds: Set<Long>) {
        val pending = pendingIds()
        val held = state.value.groups.flatMap { g -> g.messages.map { it.id } }.toSet()
        hideAndDelete(messageIds.filter { it in held && it !in pending })
    }

    private fun hideAndDelete(ids: List<Long>, clearsRestoredLog: Boolean = false) {
        if (ids.isEmpty()) return
        val token = nextToken++
        if (clearsRestoredLog) clearLogTokens += token
        pendingDeletes.update { it + (token to ids) }
        events.trySend(BlockedEvent.Deleted(token, ids.size))
        // The screen's snackbar normally commits; this makes the delete final even when the screen is left
        // with the model still alive (commitDelete ignores a token that is gone or already being committed).
        viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitDelete(token)
        }
    }

    fun undoDelete(token: Long) {
        clearLogTokens -= token
        pendingDeletes.update { it - token }
    }

    fun commitDelete(token: Long) {
        val ids = pendingDeletes.value[token] ?: return
        if (!deleting.add(token)) return
        viewModelScope.launch {
            runCatching { repository.deleteBlocked(ids) }
                .onSuccess { if (clearLogTokens.remove(token)) restoredLog.clear() }
                .onFailure { events.trySend(BlockedEvent.Failed) }
            pendingDeletes.update { it - token }
            deleting -= token
        }
    }

    /** Delete tokens whose commit is running, so the timer and the snackbar never delete twice. */
    private val deleting = HashSet<Long>()

    /**
     * Shows the Undo snackbar again for every action still waiting on one. Call when the screen starts collecting
     * [eventFlow]: after a rotation the old snackbar was cancelled without an answer, which left the rows hidden with
     * no way to undo or finish the action. Events still queued for the old screen are folded in, so none shows twice.
     */
    fun replayPending() {
        val queued = generateSequence { events.tryReceive().getOrNull() }.toList()
        queued.filter { it is BlockedEvent.Failed || it is BlockedEvent.RestoreFailed }.forEach { events.trySend(it) }
        val replays = pendingRestores.value.filter { it.value.announced && it.key !in committing }
            .map { (token, r) -> token to r.event(token) } +
            pendingDeletes.value.map { (token, ids) -> token to BlockedEvent.Deleted(token, ids.size) }
        replays.sortedBy { it.first }.forEach { events.trySend(it.second) }
    }

    override fun onCleared() {
        // Leaving the screen finalises deletions the user did not undo. Restores are done already; what is left is
        // deleting the kept files of those whose undo window was still open (one still running does that itself, and
        // one being committed or undone finishes on its own).
        cleared = true
        val leftovers = pendingDeletes.value.values.flatten()
        if (clearLogTokens.any { it in pendingDeletes.value }) restoredLog.clear()
        val finished = pendingRestores.value.filterKeys { it !in committing }.values
            .flatMap { it.receipts.orEmpty().values }
        if (leftovers.isNotEmpty() || finished.isNotEmpty()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                finished.forEach { runCatching { repository.finishRestore(it) } }
                if (leftovers.isNotEmpty()) runCatching { repository.deleteBlocked(leftovers) }
            }
        }
        super.onCleared()
    }

    /** Ids hidden while a restore or a delete waits for its undo window. */
    private fun pendingIds(): Set<Long> =
        (pendingRestores.value.values.flatMap { it.claimed } + pendingDeletes.value.values.flatten()).toSet()

    private fun idsOf(addresses: Set<String>): List<Long> =
        state.value.groups.filter { g -> addresses.any(g::hasAddress) }.flatMap { g -> g.messages.map { it.id } }

    companion object {
        /** A little longer than a Long snackbar (10 s), so the snackbar's own answer normally comes first. */
        const val UNDO_WINDOW_MS = 11_000L

        private const val FILTER_PREFS = "spamless_blocked_ui"
        private const val FILTER_KEY = "filter"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                BlockedViewModel(app, SpamRepositoryProvider.get(app))
            }
        }

        /** The sender thread's model: every held message of the sender, whatever the tab's chip. */
        val ThreadFactory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                BlockedViewModel(app, SpamRepositoryProvider.get(app), ignoreFilter = true)
            }
        }
    }
}
